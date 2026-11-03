<div align="center">

# Restart

**Plateforme d'authentification en tant que service — AbdaTech**

Un portail développeur qui délivre des identités, des jetons et des quotas à des
applications tierces, bâti sur neuf microservices réactifs.

<br>

[![CI](https://github.com/Sissoko90/micro-service-restart/actions/workflows/ci.yml/badge.svg)](https://github.com/Sissoko90/micro-service-restart/actions/workflows/ci.yml)
[![Sécurité](https://github.com/Sissoko90/micro-service-restart/actions/workflows/security.yml/badge.svg)](https://github.com/Sissoko90/micro-service-restart/actions/workflows/security.yml)
[![Couverture](https://img.shields.io/badge/couverture-20.7%25-orange)](docs/TESTING.md)
[![Tests](https://img.shields.io/badge/tests-337-brightgreen)](docs/TESTING.md)

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.0-6DB33F?logo=spring&logoColor=white)](https://spring.io/projects/spring-cloud)
[![WebFlux](https://img.shields.io/badge/architecture-r%C3%A9active-6DB33F)](docs/NON-BLOCKING.md)
[![Plateforme](https://img.shields.io/badge/plateforme-Docker%20%7C%20Linux%20%7C%20macOS-2496ED?logo=docker&logoColor=white)](#démarrage-rapide)

[![Licence](https://img.shields.io/badge/licence-propri%C3%A9taire-red)](LICENSE)
[![OWASP](https://img.shields.io/badge/OWASP-Top%2010%20audit%C3%A9-000000?logo=owasp&logoColor=white)](docs/COMPLIANCE.md)
[![CWE](https://img.shields.io/badge/MITRE-CWE%20Top%2025-c00000)](docs/COMPLIANCE.md)
[![RGPD](https://img.shields.io/badge/RGPD-art.17%20complet%20%C2%B7%20art.20%20absent-orange)](docs/COMPLIANCE.md)

[![Dernier commit](https://img.shields.io/github/last-commit/Sissoko90/micro-service-restart)](https://github.com/Sissoko90/micro-service-restart/commits/main)
[![PRs](https://img.shields.io/github/issues-pr/Sissoko90/micro-service-restart)](https://github.com/Sissoko90/micro-service-restart/pulls)
[![Stars](https://img.shields.io/github/stars/Sissoko90/micro-service-restart?style=flat)](https://github.com/Sissoko90/micro-service-restart/stargazers)
[![Forks](https://img.shields.io/github/forks/Sissoko90/micro-service-restart?style=flat)](https://github.com/Sissoko90/micro-service-restart/network/members)

</div>

---

## Sommaire

- [Ce que fait Restart](#ce-que-fait-restart)
- [Architecture](#architecture)
- [Les services](#les-services)
- [Démarrage rapide](#démarrage-rapide)
- [Construire et tester](#construire-et-tester)
- [Sécurité](#sécurité)
- [Conformité](#conformité)
- [Intégration continue](#intégration-continue)
- [Contribuer](#contribuer)
- [Feuille de route](#feuille-de-route)
- [Documentation](#documentation)

---

## Ce que fait Restart

Un développeur s'inscrit sur le portail, crée un projet, et reçoit des clés. Son
application peut dès lors déléguer à Restart l'inscription, la connexion, la
vérification d'identité et la gestion des rôles de **ses propres** utilisateurs —
sans écrire une ligne de code d'authentification.

La plateforme fournit :

| | |
|---|---|
| **Authentification** | mot de passe, code à usage unique par SMS ou courriel, OAuth2 (Google, Facebook), biométrie |
| **Jetons** | JWT signés RS256, rotation de clés sans interruption, révocation immédiate |
| **Vérification d'identité** | dépôt de pièces justificatives, chiffrement AES-256-GCM, circuit de validation |
| **Rôles et permissions** | affectation multiple, portée par projet |
| **Quotas** | plafonds par palier d'abonnement, comptage temps réel |
| **Second facteur** | TOTP conforme RFC 6238, compatible Google Authenticator |

### Ce qui structure la conception

**Une seule autorité de signature.** `interface-service` est le seul service à
détenir des clés privées. Les autres valident les jetons via son point JWKS. Un
service compromis ne peut donc pas fabriquer d'identité — il n'a pas de quoi
signer.

**Un seul point d'entrée.** Tout passe par le Gateway. Les services refusent les
appels directs : chaque requête proxifiée porte une signature HMAC-SHA256
horodatée que le Gateway seul sait produire. Un appel direct reçoit un 403.

**Aucun blocage sur le chemin d'une requête.** L'ensemble est réactif de bout en
bout — WebFlux, R2DBC, pilotes MongoDB et Cassandra réactifs. L'absence d'appel
bloquant est vérifiée par des tests BlockHound, pas seulement par relecture.

---

## Architecture

```
                         ┌──────────────────┐
      Client ───────────▶│  gateway-service │ :8081
                         │                  │
                         │  liste noire IP  │
                         │  géo-blocage     │
                         │  débit (Redis)   │
                         │  traversée/XSS   │
                         │  JWT + révocation│
                         │  signature HMAC  │
                         └────────┬─────────┘
                                  │  Consul (découverte)
        ┌──────────────┬──────────┼──────────┬──────────────┐
        ▼              ▼          ▼          ▼              ▼
  ┌───────────┐  ┌──────────┐ ┌────────┐ ┌────────┐  ┌───────────┐
  │ interface │  │   auth   │ │  user  │ │ media  │  │  quotas   │
  │   :8088   │  │  :8082   │ │ :8083  │ │ :8087  │  │  :8089    │
  │           │  │          │ │        │ │        │  │           │
  │ AUTORITÉ  │  │ connexion│ │ profil │ │  KYC   │  │ plafonds  │
  │    JWT    │  │ OTP,OAuth│ │ rôles  │ │ chiffré│  │  usage    │
  │ JWKS/kid  │  │ biométrie│ │        │ │        │  │           │
  └─────┬─────┘  └────┬─────┘ └───┬────┘ └───┬────┘  └─────┬─────┘
        │             │           │          │             │
   PostgreSQL    PostgreSQL  PostgreSQL   MongoDB      Cassandra
        │             │           │          │             │
        └─────────────┴───────────┴──────────┴─────────────┘
                              Redis  ·  Kafka
                                  │
                         ┌────────┴─────────┐
                         │ setting  :8090   │  config-service :8888
                         └──────────────────┘
```

### Le chemin d'une requête authentifiée

1. Le Gateway écarte les IP en liste noire et les pays bloqués.
2. Il applique le plafond de débit, indexé sur l'**IP réelle** — l'en-tête
   `X-Forwarded-For` n'est cru que s'il vient d'un proxy déclaré.
3. Il valide le jeton : signature RS256 (clé résolue par le `kid` de l'en-tête
   JWS), émetteur, audience, expiration, **type**, puis **révocation**.
4. Il purge les en-têtes d'identité venus du client et les réécrit depuis les
   claims validés.
5. Il signe la requête en HMAC-SHA256 et la transmet au service résolu via Consul.
6. Le service vérifie la signature, sa fraîcheur (fenêtre de 5 s), et traite.

Chacune de ces étapes est couverte par des tests. Le détail — et les failles qui
y ont été trouvées — est dans [docs/GATEWAY-SECURITY.md](docs/GATEWAY-SECURITY.md).

---

## Les services

| Service | Port | Rôle | Persistance | Endpoints |
|---|---|---|---|---|
| **gateway-service** | 8081 | Point d'entrée unique : filtrage, débit, JWT, routage, disjoncteurs | Redis | — |
| **interface-service** | 8088 | **Autorité JWT** (JWKS, rotation), portail développeur, projets, console d'administration, TOTP | PostgreSQL | 34 |
| **auth-service** | 8082 | Connexion, inscription, OTP, OAuth2, biométrie, verrouillage de compte, rôles | PostgreSQL | 62 |
| **user-service** | 8083 | Profils, photos, administration des comptes | PostgreSQL | 27 |
| **media-service** | 8087 | Pièces d'identité chiffrées, circuit de validation, expiration | MongoDB + MinIO | 27 |
| **quotas-service** | 8089 | Plafonds par palier, comptage d'usage | Cassandra | 6 |
| **setting-service** | 8090 | Paramètres par projet, valeurs par défaut | PostgreSQL | 9 |
| **config-service** | 8888 | Configuration centralisée (Spring Cloud Config) | dépôt Git | — |
| **platform-commons** | — | Bibliothèque partagée : JWKS, HMAC, lecture des claims JWT | — | — |

> **`platform-commons` n'est pas un fourre-tout.** Il ne contient que ce dont une
> divergence entre services créerait une faille : la résolution des clés
> publiques, la signature HMAC, la comparaison à temps constant, et la lecture des
> rôles. Cette dernière existait auparavant en quatre exemplaires, dont trois
> étaient cassés.

### Squelettes, pas encore implémentés

`geo-service` (8084), `payment-service` (8085) et `notification-service` (8086)
existent comme modules mais ne sont pas fonctionnels. Ils ne sont pas dans la
construction Maven.

---

## Démarrage rapide

### Prérequis

- JDK 17 · Maven 3.9+ · Docker (pour les dépendances et les tests d'intégration)

### 1. Infrastructure — tout vient de Docker

```bash
cp .env.example .env    # renseigner les valeurs — voir §Secrets ci-dessous
docker compose -f docker-compose-minimal.yml up -d
```

Démarre PostgreSQL, MongoDB, Cassandra, Redis, Kafka, MinIO et Consul.

> Un PostgreSQL ou un MongoDB **installé localement** occupera le port avant le
> conteneur, qui refusera alors de démarrer. Arrêter le service local
> (`brew services stop postgresql@16`, `sudo systemctl stop postgresql`) ou
> changer `DB_PORT` dans `.env`.

### 2. Construire

```bash
mvn clean install       # depuis la RACINE : les modules dépendent du POM parent
```

> Construire un module seul échoue : les versions vivent dans le POM parent, et
> `platform-commons` doit être installé d'abord.

### 3. Démarrer les services, dans cet ordre

```bash
./scripts/start-platform.sh --check    # état de l'infrastructure et des services
```

L'ordre n'est pas arbitraire — deux dépendances le contraignent :

| Ordre | Service | Pourquoi à cette place |
|---|---|---|
| 1 | `config-service` (8888) | sert la configuration de tous les autres. Sans lui, chacun démarre sur ses valeurs par défaut — **sans erreur visible**, ce qui est pire qu'un échec franc |
| 2 | `interface-service` (8088) | **autorité JWT**. Les autres démarrent sans elle, mais tant qu'elle est absente ils **refusent tous les jetons** |
| 3 | `gateway-service` (8081) | point d'entrée unique |
| 4+ | auth, user, media, quotas, setting | indépendants les uns des autres |

```bash
mvn -pl config-service spring-boot:run
mvn -pl interface-service spring-boot:run
mvn -pl gateway-service spring-boot:run
# puis les autres, dans n'importe quel ordre
```

> **Un 401 systématique sur une plateforme en apparence démarrée vient presque
> toujours d'`interface-service` absent.** Les journaux des autres services le
> disent : `❌ Échec du rafraîchissement JWKS: Connection refused … :8088`. Ils
> continuent de démarrer — le chargement des clés est non bloquant et réessayé —
> mais sans clé publique, aucune signature n'est vérifiable.

### 4. Vérifier

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8088/dev/.well-known/jwks.json    # les clés publiques
```

Les [collections Postman](postman/) couvrent les 165 endpoints, avec l'ordre de
test conseillé service par service.

> `baseUrl` pointe sur le **Gateway**. Un appel direct à un service renvoie 403 :
> c'est le comportement attendu, pas une panne.

### Secrets

Aucune valeur réelle ne doit figurer dans le dépôt. `.env` est ignoré par Git et
la CI refuse toute PR qui en committerait un.

> ⚠️ Un `.env` a été committé dans le commit initial `71af837`. Il a été retiré du
> suivi, mais **les valeurs restent dans l'historique Git et doivent être
> changées** : les retirer d'un commit ne les rend pas secrètes.

---

## Construire et tester

```bash
mvn clean install                      # tout : compilation, tests, couverture
mvn -pl gateway-service test           # un seul module
./scripts/coverage-report.sh           # couverture agrégée
./scripts/coverage-gate.sh             # non-régression de la couverture
./scripts/postman/generate.sh          # régénérer les collections depuis le code
```

### État des tests

**337 tests**, couverture **20,7 %** des instructions, **12,3 %** des branches.

La couverture est basse et le document [docs/TESTING.md](docs/TESTING.md) ne le
dissimule pas. Ce qui compte davantage, c'est **où** portent les tests : le
contrat JWT, les filtres du Gateway, le chiffrement des documents, le contrôle
d'accès, la persistance. Sur les huit suites écrites lors de la dernière
campagne, **six ont révélé un défaut réel en production** — dont un qui rejetait
la totalité des jetons légitimes.

Le garde-fou de CI n'exige pas un seuil absolu : il interdit que la couverture
**baisse**. Un seuil à 80 % sur une base à 19 % serait désactivé en deux jours.

### Tests d'intégration

`AuthPersistenceIntegrationTest` démarre un vrai PostgreSQL via Testcontainers et
applique les migrations Flyway. Il se désactive de lui-même si Docker est absent.

---

## Sécurité

### Ce qui est en place

| Mesure | Détail |
|---|---|
| **Point d'entrée unique** | signature HMAC-SHA256 horodatée, fenêtre anti-rejeu de 5 s |
| **Jetons** | RS256, `kid` en en-tête JWS, rotation avec période de grâce, audiences multiples |
| **Révocation** | par `jti` et par porteur, appliquée **au Gateway** sur tout le trafic |
| **Débit** | plafonds par type d'endpoint, distribués via Redis, repli local en cas de panne |
| **IP client** | `X-Forwarded-For` cru uniquement depuis un proxy déclaré |
| **Second facteur** | TOTP RFC 6238, vérifié contre les vecteurs d'essai officiels |
| **Documents** | AES-256-GCM, clé dérivée par HMAC propre à chaque couple (utilisateur, document) |
| **Mots de passe** | BCrypt, verrouillage de compte après tentatives échouées |
| **En-têtes** | CSP, HSTS, `X-Frame-Options: DENY`, CORS à liste blanche explicite |

### Ce qui a été trouvé et corrigé

L'audit du 2026-09-03 a mis au jour des défauts sérieux, tous corrigés et
verrouillés par des tests :

- **le Gateway rejetait en 401 la totalité des jetons légitimes** — une lecture
  de claim qui n'était jamais vraie ;
- **chaque filtre de sécurité s'exécutait deux fois**, divisant par deux le
  plafond réel de tentatives de connexion ;
- **débit, liste noire et géo-blocage étaient tous contournables** en modifiant
  un en-tête HTTP ;
- **la révocation ne s'appliquait pas** au trafic entrant ;
- **deux IDOR** : lecture du profil et des fichiers d'autrui ;
- **une collision de dérivation de clé** dans le chiffrement des pièces d'identité.

Le récit complet, avec la cause de chacun, est dans
[docs/GATEWAY-SECURITY.md](docs/GATEWAY-SECURITY.md).

### Signaler une faille

Voir [SECURITY.md](SECURITY.md). **Jamais dans une issue publique.**

---

## Conformité

[docs/COMPLIANCE.md](docs/COMPLIANCE.md) détaille l'état réel, sans complaisance.

| Référentiel | État |
|---|---|
| **OWASP Top 10 (2021)** | 7 catégories satisfaites, 3 partielles |
| **MITRE CWE Top 25** | analysé par CodeQL ; 7 CWE effectivement corrigés |
| **RGPD — droit à l'effacement (art. 17)** | ✅ les 6 services détenteurs |
| **RGPD — rectification (art. 16)** | ✅ |
| **RGPD — portabilité (art. 20)** | ❌ absent |

### Droit à l'effacement

`POST /auth/erasure/me` efface réellement les données de la personne, puis
propage la demande par événement Kafka aux services qui en détiennent d'autres.
Chacun accuse réception ; le registre d'exécution permet de **prouver**
l'effacement — l'article 5.2 l'exige, et une intention ne se démontre pas.

Les **développeurs du portail** disposent de leur propre point d'entrée,
`POST /dev/erasure/me` : ils forment une population distincte, dont l'identité
vit dans interface-service et non dans auth-service. Ils n'avaient jusqu'ici
aucun moyen d'exercer ce droit.

Ce qui est effacé et ce qui est conservé, service par service, est détaillé dans
[docs/COMPLIANCE.md §1](docs/COMPLIANCE.md).

---

## Intégration continue

| Workflow | Déclenchement | Bloquant |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | push, PR | compilation, tests, non-régression de couverture, `.env` ou clé privée committés |
| [`security.yml`](.github/workflows/security.yml) | push, PR, hebdomadaire | secrets (Gitleaks), vulnérabilités CVSS ≥ 7 (Trivy), licences incompatibles |
| [`dependency-review.yml`](.github/workflows/dependency-review.yml) | PR | dépendance ajoutée en gravité élevée ou sous licence interdite |

**Politique de blocage** : critique et élevé bloquent, le reste est signalé. Une
CI qui échoue pour tout est une CI qu'on désactive.

CodeQL alimente l'onglet Sécurité (CWE, OWASP, MITRE) sans bloquer la fusion —
le durcir viendra quand la base d'alertes existantes aura été traitée.

### Protection de `main`

Une règle de protection ne peut pas être posée par un workflow : le jeton
d'Actions n'a pas les droits d'administration, et c'est voulu — sinon un workflow
modifiable par une PR pourrait lever ses propres garde-fous.

```bash
gh auth login
./scripts/github/protect-main.sh
```

Applique : fusion par PR uniquement, approbation d'un `CODEOWNER`, approbation
invalidée par tout nouveau commit, les sept contrôles de CI obligatoires,
historique linéaire, force-push et suppression interdits, **règles applicables
aux administrateurs**.

### Dépendances

Dependabot ouvre des PR hebdomadaires, groupées par thème (Spring, tests,
observabilité). Les montées **majeures** sont ignorées : elles se décident, elles
ne se subissent pas.

---

## Contribuer

Le projet est sous licence propriétaire ([LICENSE](LICENSE)). Les contributions
externes ne sont pas ouvertes ; le dépôt est visible à des fins d'évaluation,
d'audit et de signalement de failles.

Pour l'équipe :

1. Brancher depuis `main` — `main` est protégée.
2. Écrire le test **avant** le correctif quand il s'agit d'un défaut : c'est ce
   qui prouve qu'on a compris la cause, et non seulement le symptôme.
3. Remplir le [modèle de PR](.github/pull_request_template.md) — ses listes de
   contrôle reprennent les pièges qui ont déjà coûté cher ici.
4. Attendre le vert de la CI et l'approbation d'un `CODEOWNER`.

### Les pièges du dépôt

Chacun a déjà causé un défaut réel. Ils sont détaillés dans la documentation ;
en voici la liste courte :

| Piège | Conséquence |
|---|---|
| Un `WebFilter` annoté `@Component` **et** ajouté à la chaîne Spring Security | s'exécute deux fois par requête |
| Lire `X-Forwarded-For` directement | débit et liste noire contournables |
| `claims.get("roles", String.class)` | lève sur un jeton utilisateur — passer par `JwtClaimsReader` |
| Un rôle dans `@PreAuthorize` sur un endpoint à identifiant de chemin | IDOR : un rôle n'est pas une autorisation |
| Remplacer `@{argLine}` dans le surefire parent | couverture à zéro, sans erreur |
| `.then(x)` en Reactor | évalue `x` à l'assemblage — utiliser `Mono.defer` |

---

## Feuille de route

[![Playground](https://img.shields.io/badge/Playground-planifi%C3%A9-lightgrey)](#feuille-de-route)
[![Hermes](https://img.shields.io/badge/Hermes-planifi%C3%A9-lightgrey)](#feuille-de-route)

> Ces deux composants **n'existent pas encore**. Les badges ci-dessus les
> annoncent comme planifiés, non comme disponibles.

| Échéance | Sujet |
|---|---|
| **Immédiat** | Faire tourner les secrets du commit `71af837` |
| **Immédiat** | Purger les comptes inactifs et la consommation d'API |
| **Court terme** | Extraire l'autorité de signature JWT dans un service dédié |
| **Court terme** | Retirer la table `users` d'`interface-service` |
| **Court terme** | Portabilité des données (RGPD art. 20) |
| **Court terme** | Élever la couverture sur `OAuth2ServiceImpl` et la gestion des rôles |
| **Court terme** | Tests d'intégration pour `interface-service` (8 migrations non validées) |
| **Moyen terme** | **Playground** — console de test d'API dans le portail développeur |
| **Moyen terme** | **Hermes** — service de notification (SMS, courriel, push) |
| **Moyen terme** | `geo-service` et `payment-service` |
| **Moyen terme** | SBOM CycloneDX, signature des artefacts |
| **Moyen terme** | Remplacer le substitut WebAuthn par une vraie vérification |

---

## Documentation

| Document | Contenu |
|---|---|
| [docs/GATEWAY-SECURITY.md](docs/GATEWAY-SECURITY.md) | Audit de sécurité, failles trouvées et corrigées, IDOR, injections |
| [docs/COMPLIANCE.md](docs/COMPLIANCE.md) | RGPD, OWASP, MITRE, registre des données, chaîne d'approvisionnement |
| [docs/SECURITY-CHAIN.md](docs/SECURITY-CHAIN.md) | Audit de la chaîne : filtres, frontière de confiance, interservice, IDOR |
| [docs/ROLES.md](docs/ROLES.md) | Modèle de rôles : SUPER_ADMIN, ADMIN, DEVELOPER |
| [docs/TESTING.md](docs/TESTING.md) | Stratégie de test, couverture, conventions, priorités |
| [docs/JWT-CONTRACT.md](docs/JWT-CONTRACT.md) | Contrat des jetons : claims, audiences, rotation |
| [docs/NON-BLOCKING.md](docs/NON-BLOCKING.md) | Politique réactive, vérification BlockHound |
| [docs/QUOTAS.md](docs/QUOTAS.md) · [docs/SETTINGS.md](docs/SETTINGS.md) | Quotas et paramètres |
| [docs/AUTH-SERVICE-R2DBC-MIGRATION.md](docs/AUTH-SERVICE-R2DBC-MIGRATION.md) | Migration JPA → R2DBC |
| [postman/README.md](postman/README.md) | Collections et ordre de test |
| [SECURITY.md](SECURITY.md) | Signalement de failles |

---

<div align="center">

**AbdaTech Technologie** · Bamako, Mali · [contact@abdatech.ml](mailto:contact@abdatech.ml)

Tous droits réservés — voir [LICENSE](LICENSE)

</div>
