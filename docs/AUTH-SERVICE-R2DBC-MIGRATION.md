# Migration auth-service : JPA/MySQL → R2DBC/PostgreSQL

> **État : appliquée.** auth-service est passé de JPA/MySQL à R2DBC/PostgreSQL.
> Feign est conservé tel quel, conformément à la décision de ne pas toucher aux
> contrats inter-services.

---

## 1. Ce qui a changé

| Avant | Après |
|---|---|
| `spring-boot-starter-data-jpa` + MySQL | `spring-boot-starter-data-r2dbc` + PostgreSQL |
| Schéma généré par Hibernate (`ddl-auto: update`) | Flyway, `V1__init_auth_schema.sql` (9 tables) |
| `JpaRepository`, `Optional<T>`, `List<T>` | `ReactiveCrudRepository`, `Mono<T>`, `Flux<T>` |
| Services renvoyant des types simples | Services renvoyant `Mono`/`Flux` |
| Contrôleurs en `Mono.fromCallable(...).subscribeOn(boundedElastic())` | Chaînes réactives directes |

**Périmètre réel** : 10 entités, 9 repositories, 12 services, 8 contrôleurs —
178 erreurs de compilation résorbées.

---

## 2. Feign : conservé, mais isolé

Décision : **aucun changement des contrats inter-services**. `UserServiceClient`
reste un `@FeignClient`.

Feign étant bloquant, ses 4 sites d'appel sont désormais exécutés sur
`Schedulers.boundedElastic()` — le pool prévu pour l'I/O bloquante. L'EventLoop
Netty n'est plus jamais retenu.

Même traitement pour les autres appels bloquants qui subsistent :

| Appel bloquant | Où | Traitement |
|---|---|---|
| `UserServiceClient` (Feign) | `UserRoleManagementServiceImpl`, `OAuth2ServiceImpl` | `boundedElastic` |
| SMTP (`JavaMailSender`) | `EmailServiceImpl` | `boundedElastic` |
| SMS (SDK Twilio) | `SmsServiceImpl` | `boundedElastic` |
| Échange de code OAuth2 (Google/Facebook) | `OAuth2ServiceImpl` | `boundedElastic` |
| Publication Kafka | `AuthServiceImpl`, `OAuth2ServiceImpl` | `boundedElastic` |

**Correction d'une analyse antérieure** : le chemin de login ne contenait aucun
appel Feign. `GatewayGeoLocationClient` utilisait déjà `WebClient`, avec un
`.block()` interne — il renvoie désormais un `Mono`, sans aucun changement du
contrat réseau vers le gateway.

---

## 3. Pièges R2DBC traités

### 3.1 Détection « entité neuve » — le piège silencieux

Avec un identifiant assigné par l'application (UUID textuel), Spring Data R2DBC
émettrait un `UPDATE` pour une entité jamais insérée : **le `save()` serait sans
effet, sans erreur**.

`BaseEntity` implémente donc `Persistable<String>` avec un drapeau transitoire
`newEntity`, remis à `false` après chaque lecture par un `AfterConvertCallback`
(`R2dbcAuditingConfig`).

### 3.2 Audit

`@CreationTimestamp`, `@UpdateTimestamp` et `@PrePersist` n'existent pas en R2DBC.
Un `BeforeConvertCallback` génère l'identifiant et pose `createdAt`/`updatedAt`
avant chaque écriture.

### 3.3 Relations

R2DBC ne gère ni relations ni chargement paresseux. Les deux `@ManyToOne`
deviennent des clés étrangères nues :

- `RefreshToken.user` → `RefreshToken.userId` (`token.getUser().getId()` devient `token.getUserId()`)
- `OAuthAccount.user` → `OAuthAccount.userId`

Les jointures nécessaires sont explicites (`userRepository.findById(...)`).

### 3.4 Cache Spring

`@Cacheable` / `@CacheEvict` ne gèrent pas les types réactifs : ils mettraient en
cache le `Mono` non souscrit, pas sa valeur. Retirés de
`MessageTemplateServiceImpl` et `VerificationCodeServiceImpl`. Un cache réactif
explicite (Redis reactive) pourra être réintroduit si le besoin se confirme.

---

## 4. Défauts trouvés et corrigés au passage

| Défaut | Fichier |
|---|---|
| `@Async` sur une méthode **privée** — Spring AOP ne proxifie pas les méthodes privées, l'envoi n'a jamais été asynchrone | `VerificationCodeServiceImpl.sendEmailAsync` |
| `@Cacheable` sur une méthode **privée** — même cause, sans effet | `OAuth2ServiceImpl.handleExistingOAuthUser` |
| `RedisTemplate` bloquant sur le chemin biométrique | `BiometricAuthServiceImpl` → `ReactiveRedisTemplate` |
| Comparaison de signature/challenge par `String.equals` (timing attack) | `BiometricAuthServiceImpl` → `SecretComparisonUtil` |
| Identifiant de repository incohérent (`UUID` vs `String`) | `OAuthAccountRepository` |
| `AdminSeeder` désactivé et dupliquant celui d'interface-service | supprimé |

**Limite non corrigée** : `BiometricAuthServiceImpl.computeExpectedSignature`
n'est pas une vérification WebAuthn réelle (encodage Base64 d'une concaténation).
`webauthn4j` est déjà au classpath ; à traiter avant toute mise en production de
ce parcours.

---

## 5. Tests

`AuthServiceImplLoginTest` — 9 tests couvrant les règles de sécurité qui devaient
survivre à la migration :

- connexion réussie, compteur d'échecs remis à zéro ;
- utilisateur inconnu → même erreur qu'un mot de passe invalide (pas de divulgation) ;
- mot de passe invalide → incrément du compteur ;
- verrouillage au seuil (`MAX_FAILED_LOGIN_ATTEMPTS`) ;
- compte verrouillé → refus **avant** vérification du mot de passe ;
- verrouillage échu → déverrouillage automatique ;
- téléphone non vérifié / compte désactivé → refus ;
- panne de géolocalisation → connexion inchangée.

auth-service passait de 1 à 10 tests.

---

## 6. Mise en service

```bash
# 1. Créer la base
createdb auth_service_db

# 2. Flyway applique le schéma au démarrage (spring.flyway.enabled: true)
cd auth-service && ./mvnw spring-boot:run
```

Variables attendues : `DB_HOST`, `DB_PORT`, `DB_NAME_AUTH`, `DB_USERNAME`,
`DB_PASSWORD` (voir `config-repo/dev/auth-service-dev.yml`).

⚠️ **Les données MySQL existantes ne sont pas migrées.** Un export/import est à
prévoir si la base de développement contient des comptes à conserver.

---

## 7. Reste à faire

- Tests d'intégration Testcontainers sur la persistance (le `contextLoads` actuel
  désactive R2DBC et mocke les repositories).
- Étendre la couverture aux parcours OTP, OAuth2 et biométrie.
- Remplacer le placeholder de vérification WebAuthn.
- Vérifier le comportement transactionnel : `@Transactional` fonctionne en R2DBC
  via le gestionnaire réactif, mais la sémantique diffère de JPA (pas de
  *persistence context*, pas de *dirty checking* : tout `save()` doit être explicite).
