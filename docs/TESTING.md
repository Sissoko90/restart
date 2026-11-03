# Stratégie de test

> État au 2026-09-04 : **206 tests** (118 → 206), couverture **18,7 %** des
> instructions, **11,6 %** des branches.

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

| Service | Instructions | Branches | Évolution |
|---|---|---|---|
| `config-service` | 88,7 % | — | trivial (2 classes) |
| `setting-service` | 52,2 % | 13,3 % | écrit avec ses tests |
| `quotas-service` | 44,5 % | 18,1 % | écrit avec ses tests |
| `gateway-service` | 33,4 % | 17,4 % | 23,6 % → 33,4 % |
| `platform-commons` | 27,5 % | 43,2 % | nouveau module |
| `interface-service` | 21,2 % | 8,5 % | 18,5 % → 21,2 % |
| `auth-service` | 16,1 % | 12,0 % | + tests d'intégration |
| `media-service` | 9,3 % | 7,5 % | ⚠️ 7,3 % → 9,3 % |
| `user-service` | 9,1 % | 5,7 % | ⚠️ |

**La couverture de branches reste le vrai signal.** À 11,6 %, les chemins
d'erreur — token expiré, quota dépassé, compte verrouillé, document altéré —
restent majoritairement non testés. C'est là que se logent les failles.

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
