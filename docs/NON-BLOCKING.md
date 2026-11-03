# Politique non bloquante

> Aucun appel bloquant sur le chemin des requêtes. Cette propriété est **vérifiée
> par BlockHound**, pas simplement affirmée.

---

## 1. La règle

Un service WebFlux sert toutes ses requêtes sur un petit pool d'EventLoops Netty
(typiquement un thread par cœur). Un seul appel bloquant y immobilise un thread et
dégrade **toutes** les requêtes en vol, pas seulement celle qui bloque.

D'où la règle : **rien de bloquant sur un thread réactif.** Ce qui ne peut pas être
rendu réactif est déporté sur `Schedulers.boundedElastic()`, le pool prévu pour
l'I/O bloquante.

---

## 2. Ce qui a été supprimé

| Où | Problème | Correction |
|---|---|---|
| `user-service` `SecurityConfig.reactiveJwtDecoder` | `Mono.fromCallable` **sans** `subscribeOn` autour d'un validateur bloquant → s'exécutait sur l'EventLoop à **chaque requête authentifiée** | chaînage direct de `validateTokenReactive` |
| `user-service` `ServiceTokenFilter` | validait le token **deux fois**, en bloquant à chaque fois | validation unique et réactive ; `isServiceToken` devient une fonction pure sur les claims déjà validés |
| `auth-service` `AuthController` | `validateToken` bloquant sur Redis à chaque requête de profil et à la déconnexion | `extractUserIdFromToken` renvoie un `Mono` |
| `auth-service` `JwtServiceImpl` | `generateServiceToken` faisait un **appel HTTP bloquant** à interface-service, invoqué depuis les intercepteurs Feign | supprimé au profit de `ServiceTokenProvider` (HMAC local, aucune I/O) |
| `auth-service` `JwtServiceImpl` | `generateTokenWithClaims` bloquait sur la signature | renvoie un `Mono` |
| `gateway-service` `GeoLocationService` | gauge Micrometer appelant Redis en bloquant — évalué **à chaque scrape Prometheus**, sur un thread Netty | `AtomicLong` rafraîchi en arrière-plan, le gauge se contente de le lire |
| `auth-service` `BiometricAuthServiceImpl` | `RedisTemplate` bloquant sur le parcours biométrique | `ReactiveRedisTemplate` |
| `user-service` `UserAdminServiceImpl`, `AdminUserManagementServiceImpl` | `@Cacheable`/`@CacheEvict` sur des méthodes réactives | invalidation réactive explicite (voir §4) |
| `auth-service` `JwtCacheService` | code mort appelant le validateur bloquant, retournait toujours `null` | supprimé |
| `interface-service` `DevJwtServiceImpl` | `validateToken` synchrone inutilisé, deux `.block()` sur Redis | supprimé |

---

## 3. Les blocages restants, et pourquoi ils sont acceptables

Quatre classes utilisent encore `.block()` :

- `AdminSeeder`, `MaintenanceSeeder` (interface-service)
- `MongoInitializer` (media-service)
- `RecoverySecretScheduler` (interface-service)

Elles s'exécutent **au démarrage** (`CommandLineRunner`) ou sur le pool de tâches
planifiées — jamais sur un EventLoop. Le blocage y est le comportement attendu :
le démarrage doit être séquentiel. Chacune porte un commentaire explicite pour
qu'un futur audit ne les confonde pas avec des oublis.

**Isolés sur `boundedElastic()`** — non réactifs par nature, mais sans effet sur
l'EventLoop :

| Appel | Où |
|---|---|
| Feign `UserServiceClient` | `UserRoleManagementServiceImpl`, `OAuth2ServiceImpl` |
| SMTP (`JavaMailSender`) | `EmailServiceImpl` |
| SMS (SDK Twilio) | `SmsServiceImpl` |
| Échange OAuth2 (`RestTemplate` vers Google/Facebook) | `OAuth2ServiceImpl` |
| Publication Kafka | `AuthServiceImpl`, `OAuth2ServiceImpl` |

---

## 4. Spring Cache et types réactifs : le piège

`@Cacheable` et `@CacheEvict` **ne gèrent pas** `Mono`/`Flux`. Deux conséquences :

- `@Cacheable` met en cache le **publisher non souscrit**, pas la valeur. Sur un
  `Flux` issu de R2DBC — consommable une seule fois — tout hit de cache renvoie
  donc une **liste vide**.
- `@CacheEvict(beforeInvocation = true)` évince à l'invocation, avant même que la
  mutation ait abouti : un échec laisse le cache vidé à tort.

La convention du projet est l'invalidation réactive explicite, chaînée après le
succès réel :

```java
return userRepository.save(user)
        .map(userMapper::toDetailDTO)
        .flatMap(dto -> evictCache(userId, dto));   // clé "user:<id>"
```

Même remarque pour `@Async` : il ne fonctionne pas sur une méthode **privée**
(Spring AOP ne peut pas la proxifier). Deux occurrences de ce défaut ont été
trouvées et corrigées.

---

## 5. Défauts trouvés en étendant la couverture

L'extension de BlockHound à media-service et interface-service a mis au jour trois
défauts qu'aucune relecture n'avait vus :

| Défaut | Impact |
|---|---|
| `ProjectCreatedEvent.create()` appelait `UUID.randomUUID()` (donc `SecureRandom`, qui lit `/dev/random`) **hors** du scope `boundedElastic` | l'appel bloquant s'exécutait sur le thread réactif : envelopper seulement l'envoi Kafka ne suffisait pas, la construction de l'événement était évaluée avant |
| `KafkaTemplate.send()` non isolé dans interface-service | l'envoi peut attendre les métadonnées du topic ou la libération du buffer producteur (`max.block.ms` = 60 s) |
| `.then(x)` évalue `x` **à l'assemblage** dans `createProject` | la requête d'unicité du nom et la lecture de l'utilisateur partaient **même quand le quota était refusé** — deux appels inutiles sur le chemin de rejet. Corrigé par `Mono.defer` |

Le test a également révélé un bug fonctionnel sans rapport avec le blocage :
`createProject` faisait `.cast(User.class)` sur le résultat de
`userService.findById()`, qui renvoie un `UserResponse`. `Mono.cast` n'étant pas
vérifié à la compilation, **toute création de projet échouait** en
`ClassCastException` à l'exécution.

**Leçon générale** : envelopper l'appel bloquant ne suffit pas — tout ce qui est
construit *avant* le `subscribeOn` s'exécute sur le thread appelant. Le scope doit
englober la préparation autant que l'appel.

---

## 6. Vérification : BlockHound

BlockHound instrumente la JVM et lève une `BlockingOperationError` dès qu'un appel
bloquant survient sur un thread réactif.

- Dépendance de test gérée dans le POM parent.
- Le flag JVM `-XX:+AllowRedefinitionToAddDeleteMethods` est configuré dans le
  `maven-surefire-plugin` du parent — obligatoire sur JDK 13+, sans quoi
  `BlockHound.install()` échoue.

Trois services sont couverts par un `NoBlockingCallsTest` :

| Service | Chemin couvert |
|---|---|
| `auth-service` | connexion, en succès et en échec |
| `media-service` | upload : compression ImageIO, scan malware, hash, analyse qualité, chiffrement AES |
| `interface-service` | création de projet (appel synchrone quotas-service + publication Kafka), en succès et sur refus de quota |

Le premier test du fichier est un **contrôle négatif** : il vérifie qu'un
`Thread.sleep` sur un thread réactif est bien détecté. Sans lui, un test « aucun
blocage » ne prouverait rien — il pourrait juste signifier que BlockHound n'est
pas actif.

```bash
mvn test -Dtest=NoBlockingCallsTest
```

---

## 7. Étendre la couverture

Pour un nouveau chemin critique, reprendre le même schéma : monter le service avec
des mocks, souscrire sur `Schedulers.parallel()` — surveillé par BlockHound au même
titre que l'EventLoop — et laisser l'outil arbitrer.

Restent non couverts : le téléchargement de documents (media-service), les parcours
OTP et OAuth2 (auth-service), et le routage du Gateway.
