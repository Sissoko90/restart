# Stratégie de test

> État au 2026-11-12 : **3028 tests** (206 → 3028), couverture **52,7 %**
> des instructions, **47,3 %** des branches.
>
> Mesure précédente, 2026-09-04 : 206 tests, 18,7 % et 11,6 %. Le nombre de
> tests est donc multiplié par quatorze, la couverture d'instructions par
> moins de trois, et celle des branches par quatre. C'est cette dernière qui
> compte — voir §2.

---

## 1. Mesurer

```bash
./scripts/coverage-report.sh --run    # relance les tests puis agrège
./scripts/coverage-report.sh          # agrège les rapports existants
```

Le rapport HTML ligne par ligne est dans `<module>/target/site/jacoco/index.html`.

Les DTO, entités, enums, constantes et événements sont **exclus** de la mesure :
ce sont des porteurs de données sans logique, les inclure gonflerait
artificiellement le pourcentage sans rien dire de la qualité.

---

## 2. Où en est-on

Mesure du 2026-11-11, agrégée par `./scripts/coverage-report.sh`.

| Service | Instructions | Branches | Évolution des instructions |
|---|---|---|---|
| `config-service` | 100,0 % | 83,3 % | 88,7 % → 100,0 % (trivial, 2 classes) |
| `gateway-service` | 65,7 % | 53,0 % | 33,4 % → 65,7 % |
| `setting-service` | 65,5 % | 47,4 % | 52,2 % → 65,5 % |
| `notification-service` | 63,5 % | 58,4 % | entré dans le réacteur depuis |
| `quotas-service` | 61,6 % | 45,8 % | 44,5 % → 61,6 % |
| `user-service` | 60,5 % | 42,8 % | 9,1 % → 60,5 % |
| `interface-service` | 54,2 % | 42,7 % | 21,2 % → 54,2 % |
| `platform-commons` | 53,8 % | 62,8 % | 27,5 % → 53,8 % |
| `auth-service` | 52,2 % | 50,1 % | 16,1 % → 52,2 % |
| `security-service` | 48,9 % | 50,3 % | module neuf |
| `market-service` | 58,4 % | 57,2 % | module neuf |
| `media-service` | 31,1 % | 23,2 % | 9,3 % → 31,1 % |
| **TOTAL** | **52,7 %** | **47,3 %** | 18,7 % → 52,7 % |

**La couverture de branches reste le vrai signal**, et c'est elle qui a le plus
progressé : 11,6 % → 46,6 %. Les chemins d'erreur — jeton expiré, quota
dépassé, compte verrouillé, document altéré — sont désormais exercés sur près
de la moitié de leurs embranchements. C'est là que se logent les failles, et
c'est ce qui explique que les défauts trouvés cette année l'aient presque tous
été par un test écrit pour autre chose.

`media-service` est le dernier retardataire, et son classement est trompeur :
quatre de ses classes les moins couvertes sont à **0 %**
(`UserActivityLogServiceImpl`, `DocumentRequirementServiceImpl`,
`DocumentEventPublisherImpl`), soit plus de 1500 instructions jamais
exécutées.

Le motif observé depuis le début se confirme : **les tests écrits pour une
classe donnée y trouvent des défauts réels**. Sur les huit suites ajoutées, six
ont mis au jour un bug en production. Ce n'est pas un effet du hasard — c'est ce
qui arrive quand du code sensible n'a jamais été exercé autrement qu'à la main.

---

## 3. Ce qui est couvert

| Suite | Tests | Ce qu'elle verrouille | Défaut trouvé |
|---|---|---|---|
| `JwtClaimsReaderTest` *(commons)* | 20 | Lecture unique des rôles, deux formes de claim, comparaison par égalité | 3 lectures cassées dans 3 services |
| `TotpServiceImplTest` | 18 | Conformité RFC 6238 par vecteur d'essai officiel, fenêtres d'horloge, rejet des codes anciens | URI d'enrôlement non encodée |
| `DevJwtServiceImplTest` | 18 | Contrat JWT : `kid` en en-tête, audiences, rotation de clés | — |
| `UnifiedEncryptionServiceImplTest` | 17 | AES-256-GCM : IV distinct, authentification, séparation des clés | Collision de dérivation de clé |
| `JwtWebFilterTest` | 12 | Jeton forgé, expiré, sans audience, de rafraîchissement, révoqué | **Tous les jetons refusés en 401** |
| `VerificationCodeServiceImplTest` | 13 | OTP : anti-spam, usage unique, expiration | — |
| `SettingServiceImplTest` | 13 | Défaut vs surcharge, liste blanche, validation de type | — |
| `NoBlockingCallsTest` ×3 | 12 | Absence de blocage sur login, upload, création de projet | — |
| `ClientIpResolverTest` | 10 | Confiance aux en-têtes de transfert, sens de lecture de la chaîne | IP falsifiable dans 4 filtres |
| `QuotaServiceImplTest` | 10 | Paliers, dépassement, fenêtre horaire | — |
| `RefreshTokenServiceImplTest` | 10 | Révocation vs expiration | — |
| `AuthServiceImplLoginTest` | 9 | Ordre des contrôles, verrouillage | — |
| `AccessControlIdorTest` | 8 | Lecture du profil et des fichiers d'autrui | 2 IDOR |
| `AuthPersistenceIntegrationTest` | 7 | **Intégration** : schéma, mapping, SQL natif | `save()` en double insertion |
| `FilterWiringTest` | 2 | Double exécution des filtres | 7 filtres exécutés deux fois |

---

## 4. Tests d'intégration

`AuthPersistenceIntegrationTest` est le premier : PostgreSQL réel via
Testcontainers, migrations Flyway réellement appliquées, mapping R2DBC réellement
exercé.

Il a immédiatement révélé un défaut qu'aucun test unitaire ne pouvait voir :
`R2dbcAuditingConfig` ne remettait le drapeau « entité neuve » qu'après une
**lecture**, jamais après une **écriture**. Toute séquence `save()` → modifier →
`save()` sur la même instance émettait donc un second `INSERT` et échouait sur la
clé primaire.

### Écrire un nouveau test d'intégration

```java
@DataR2dbcTest                                    // tranche : ni web, ni Kafka, ni Redis
@Import({CacheStub.class, R2dbcAuditingConfig.class})
@Testcontainers(disabledWithoutDocker = true)     // se désactive sans Docker
class MonServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrations() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), ...).load().migrate();
    }
}
```

Trois pièges rencontrés, tous documentés dans le test :

1. **`@SpringBootTest` charge tout** — Redis, Kafka, Consul compris. La tranche
   `@DataR2dbcTest` évite ça, mais elle ne charge pas non plus les
   `@Configuration` du service : celles qui portent le comportement testé
   doivent être importées explicitement.
2. **`@EnableCaching` sur la classe principale** exige un `CacheManager` que la
   tranche ne fournit pas. Un `ConcurrentMapCacheManager` de test suffit.
3. **Docker 29 refuse les API antérieures à 1.44** et répond HTTP 400. Le
   diagnostic affiché — « Could not find a valid Docker environment » — oriente
   vers une fausse piste : le démon répond, il refuse la version demandée. D'où
   `src/test/resources/testcontainers.properties` avec `api.version=1.44`.

### Reste à couvrir en intégration

`interface-service` porte huit migrations Flyway et aucun test d'intégration.
C'est la prochaine cible : c'est aussi l'autorité JWT, donc le service dont un
schéma cassé arrête toute la plateforme.

---

## 5. Conventions

**Nommage.** `<Classe>Test` pour l'unitaire, `<Classe>IntegrationTest` pour
l'intégration — ce suffixe est reconnu par surefire, donc les deux tournent avec
`mvn test`. `@DisplayName` en français, décrivant le **comportement métier** et
non la méthode : « un code déjà utilisé est refusé » plutôt que
« testValidateCode2 ».

**Réactif.** `StepVerifier` pour les assertions sur `Mono`/`Flux`. Attention :
`StepVerifier` n'a pas de `withFailMessage` — pour un message d'échec explicite,
passer par `block()` puis une assertion AssertJ.

**Messages d'échec.** Écrire ce que la régression *coûte*, pas ce qui diffère.
« le jeton utilisateur a été refusé : tous les appels des utilisateurs finaux
échouent en 401 » vaut mieux que « expected true but was false ».

**Vérifier contre une référence externe, jamais contre soi-même.** Un test qui
chiffre puis déchiffre avec la même implémentation passe même si l'algorithme est
faux. `TotpServiceImplTest` compare à une implémentation de référence validée par
le vecteur d'essai officiel de la RFC 6238 : c'est ce qui prouve
l'interopérabilité avec Google Authenticator.

**Mocks.** `@MockitoSettings(strictness = LENIENT)` quand un `@BeforeEach` commun
prépare plus de stubs que chaque test n'en consomme.

**Ce qu'on teste en priorité** : les chemins d'erreur. Le chemin nominal est
exercé par le premier appel manuel ; les branches de rejet ne le sont jamais.

---

## 6. Priorités suivantes

Classées par ce qu'une régression coûterait, pas par le pourcentage manquant.

| Cible | Couverture | Enjeu |
|---|---|---|
| `auth/OAuth2ServiceImpl` | 0,3 % (1295 instr.) | Liaison de compte Google/Facebook — la plus grosse classe non testée |
| `auth/UserRoleManagementServiceImpl` | 0,5 % (754 instr.) | Attribution de rôles : élévation de privilèges |
| `media/DocumentRequirementServiceImpl` | 0 % (333 instr.) | Complétude KYC |
| `user/GlobalExceptionHandler` | 0 % (447 instr.) | Risque de divulgation d'internes dans les messages d'erreur |
| `interface-service` en intégration | — | Huit migrations non validées |
| `gateway/ZipBombProtection` | 9,9 % | Protection contre les charges compressées disproportionnées |

---

## 7. Tests manuels : collections Postman

165 requêtes, un dossier par contrôleur, générées depuis le code :

```bash
./scripts/postman/generate.sh    # régénérer après un changement d'endpoint
```

Voir [postman/README.md](../postman/README.md) pour l'ordre de test conseillé.

Les collections **ne remplacent pas** les tests automatisés : elles valident un
environnement en marche, pas une régression.

---

## 8. Le piège à connaître

`maven-surefire-plugin` reçoit `@{argLine}` dans le POM parent. Cette référence
reprend l'agent injecté par JaCoCo : la remplacer par une valeur littérale ferait
tomber **toute** la couverture à zéro, sans erreur ni avertissement.

Le second argument, `-XX:+AllowRedefinitionToAddDeleteMethods`, est requis par
BlockHound sur JDK 13+.

---

## 9. Les garde-fous structurels

Une famille de tests de ce dépôt ne teste pas du comportement : elle lit le
**dépôt lui-même** et refuse une forme. Ils existent parce que chacun d'eux
correspond à un défaut réellement survenu, qu'aucun test ordinaire n'aurait
attrapé — le code était juste, c'est son *branchement* qui ne l'était pas.

| Garde-fou | Ce qu'il refuse |
|---|---|
| `MetriquesDesAlertesTest` | une alerte ou un panneau sur une métrique que rien n'émet ; une métrique définie sans appelant ; un centile sur une métrique sans histogramme |
| `AnnotationsResilienceInertesTest` | une annotation Resilience4j nouvelle, puisque rien ne la tisse |
| `AppelsSortantsBornesTest` | un appel sortant sans délai maximal |
| `ModuleLivrableTest` | un module qui compile mais ne peut pas démarrer |
| `ValeursInjecteesResolublesTest` | un `@Value` sans défaut et sans source déclarée |
| `AucunCorpsHtmlEnJavaTest` | du balisage composé en Java |
| `RoutageCoherentTest`, `CheminCibleTest` | une route déclarée qui ne route rien |
| `RegleDeVisibiliteUniqueTest` | un second détenteur de la règle de visibilité |

### La règle qui les gouverne : lire la structure, pas le texte

Un garde-fou qui cherche une chaîne de caractères est satisfait par **sa propre
documentation**. Ce dépôt l'a payé plusieurs fois :

- un commentaire de POM qui contenait l'artefact exigé ;
- un test qui comptait des routes YAML sans voir leur indentation ;
- un garde-fou qui trouvait sa correspondance dans sa propre javadoc ;
- le 2026-11-13, le garde-fou des centiles acceptait
  `publishPercentileHistogram` **mentionné dans la javadoc** de la métrique qui
  ne le portait pas — puis, une fois le Java dépouillé, acceptait le nom de la
  métrique trouvé dans un **commentaire YAML**.

D'où `SourcesDuDepot.sansCommentairesJava`, un automate qui retire les
commentaires sans toucher aux chaînes — `/*` apparaît dans les littéraux de
chemin, et une regex avalerait tout le code jusqu'au prochain `*/`.

### Un garde-fou qu'on n'a pas vu échouer ne prouve rien

Chaque garde-fou ajouté ici est **soumis à mutation** : on casse volontairement
ce qu'il surveille, on vérifie qu'il rougit, on restaure. Trois des quatre
mutations passées le 2026-11-13 ont tué leur garde-fou du premier coup ; la
quatrième l'a traversé, et c'est elle qui a révélé la lecture par commentaire.

### Attention à la normalisation

Micrometer convertit un nom Java en nom Prometheus en remplaçant les points par
des soulignés. **L'opération n'est pas réversible** : `user_service.users.creation.duration`
devient `user_service_users_creation_duration`, et le chemin inverse donne
`user.service.users.creation.duration`, qui n'existe pas. Un garde-fou doit
normaliser dans le sens qui ne perd rien — du code vers la métrique — sans quoi
il accuse une métrique parfaitement déclarée.
