<div align="center">

# Restart

**Authentification en tant que service**

Un portail développeur qui délivre des identités, des jetons et des quotas à des
applications tierces, bâti sur dix modules réactifs.

<br>

[![CI](https://github.com/Sissoko90/restart/actions/workflows/ci.yml/badge.svg)](https://github.com/Sissoko90/restart/actions/workflows/ci.yml)
[![Sécurité](https://github.com/Sissoko90/restart/actions/workflows/security.yml/badge.svg)](https://github.com/Sissoko90/restart/actions/workflows/security.yml)

[![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![WebFlux](https://img.shields.io/badge/architecture-r%C3%A9active-6DB33F)](docs/NON-BLOCKING.md)
[![Licence](https://img.shields.io/badge/licence-propri%C3%A9taire-red)](LICENSE)

</div>

---

## Sommaire

- [Ce que fait Restart](#ce-que-fait-restart)
- [Architecture](#architecture)
- [Les modules](#les-modules)
- [Démarrage](#démarrage)
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
vérification d'identité et la gestion des rôles de **ses propres** utilisateurs.

| | |
|---|---|
| **Authentification** | mot de passe, code à usage unique par SMS ou courriel, OAuth2 (Google, Facebook, Apple), biométrie |
| **Jetons** | JWT signés RS256, rotation de clés sans interruption, révocation immédiate |
| **Vérification d'identité** | dépôt de pièces justificatives, chiffrement AES-256-GCM, circuit de validation |
| **Rôles et permissions** | affectation multiple, portée par projet |
| **Quotas** | plafonds par palier d'abonnement, comptage temps réel |
| **Second facteur** | TOTP conforme RFC 6238, codes de secours |
| **Politique de sécurité** | règles d'adresses, pays refusés, fenêtres de maintenance, seuils |

### Ce qui structure la conception

**Un seul détenteur par règle.** C'est le principe qui tient l'édifice, et il
vient d'une expérience répétée : le défaut le plus fréquent de ce dépôt a
toujours été une protection écrite, présente, et jamais atteinte — ou deux
mécanismes pour une même règle, dont l'un masque l'autre en silence.

**Une seule autorité de signature.** `interface-service` est le seul service à
détenir des clés privées. Les autres valident les jetons via son point JWKS. Un
service compromis ne peut donc pas fabriquer d'identité : il n'a pas de quoi
signer.

**Un seul point d'entrée.** Tout passe par le Gateway. Les services refusent les
appels directs : chaque requête proxifiée porte une signature HMAC-SHA256
horodatée que le Gateway seul sait produire.

**Décider et appliquer sont séparés.** `security-service` décide de la politique
et l'écrit en base ; le Gateway l'applique en lisant Redis. Il n'appelle jamais
le service de politique sur le chemin d'une requête — ce serait un saut réseau
par requête, et une panne de ce service arrêterait la plateforme.

**Aucun blocage sur le chemin d'une requête.** L'ensemble est réactif de bout en
bout — WebFlux, R2DBC, pilotes MongoDB et Cassandra réactifs. L'absence d'appel
bloquant est vérifiée par des tests BlockHound, pas seulement par relecture.

---

## Architecture

```
                         ┌──────────────────┐
      Client ───────────▶│  gateway-service │ :8081
                         │                  │
                         │  règles d'IP     │
                         │  pays refusés    │
                         │  débit (Redis)   │
                         │  traversée/XSS   │
                         │  JWT + révocation│
                         │  maintenance     │
                         │  signature HMAC  │
                         └────────┬─────────┘
                                  │  Consul (découverte)
   ┌───────────┬─────────┬────────┼───────┬──────────┬──────────┐
   ▼           ▼         ▼        ▼       ▼          ▼          ▼
┌─────────┐ ┌────────┐ ┌───────┐ ┌─────┐ ┌────────┐ ┌────────┐
│interface│ │  auth  │ │ user  │ │media│ │security│ │ quotas │
│  :8088  │ │ :8082  │ │ :8083 │ │:8087│ │ :8091  │ │ :8089  │
│         │ │        │ │       │ │     │ │        │ │        │
│AUTORITÉ │ │connexion│ │profil │ │ KYC │ │politiq.│ │plafonds│
│   JWT   │ │OTP OAuth│ │rôles  │ │chiff│ │ décide │ │ usage  │
│JWKS/kid │ │biométrie│ │       │ │     │ │        │ │        │
└────┬────┘ └───┬────┘ └───┬───┘ └──┬──┘ └───┬────┘ └───┬────┘
     │          │          │        │        │          │
 PostgreSQL PostgreSQL PostgreSQL MongoDB PostgreSQL Cassandra
     │          │          │        │        │          │
     └──────────┴──────────┴────────┴────────┴──────────┘
                      Redis  ·  Kafka
                            │
                   ┌────────┴─────────┐
                   │ setting  :8090   │  config-service :8888
                   └──────────────────┘
```

### Le chemin d'une requête authentifiée

1. Le Gateway écarte les adresses bloquées et les pays refusés, et répond à la
   place du service si une fenêtre de maintenance court.
2. Il applique le plafond de débit, indexé sur l'**adresse réelle** —
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

## Les modules

| Module | Port | Rôle | Persistance | Endpoints |
|---|---|---|---|---|
| **gateway-service** | 8081 | Point d'entrée unique : filtrage, débit, JWT, routage, disjoncteurs | Redis | — |
| **interface-service** | 8088 | **Autorité JWT** (JWKS, rotation), portail développeur, projets, console d'administration | PostgreSQL | 36 |
| **auth-service** | 8082 | Connexion, inscription, OTP, OAuth2, biométrie, TOTP, sessions, verrouillage | PostgreSQL | 99 |
| **media-service** | 8087 | Pièces d'identité chiffrées, circuit de validation, expiration | MongoDB + MinIO | 27 |
| **user-service** | 8083 | Profils, photos, administration des comptes | PostgreSQL | 20 |
| **security-service** | 8091 | **Décide** la politique : adresses, pays, maintenance, seuils | PostgreSQL | 13 |
| **setting-service** | 8090 | Paramètres par projet, valeurs par défaut | PostgreSQL | 9 |
| **quotas-service** | 8089 | Plafonds par palier, comptage d'usage | Cassandra | 6 |
| **config-service** | 8888 | Configuration centralisée (Spring Cloud Config) | `config-repo/` | — |
| **platform-commons** | — | Contrat partagé entre services | — | — |

> **`platform-commons` n'est pas un fourre-tout.** Il ne contient que ce dont une
> divergence entre services créerait une faille : chemins publics, clés Redis,
> signature HMAC, lecture d'adresse IP, lecture des claims JWT, empreinte de
> secret. La lecture des rôles existait auparavant en quatre exemplaires, dont
> trois étaient cassés.

### Squelettes, hors construction

`geo-service` (8084), `payment-service` (8085) et `notification-service` (8086)
existent comme répertoires mais **ne sont pas déclarés dans le POM parent** : ils
ne sont jamais compilés. Les aborder sera du développement neuf, pas de la
consolidation.

### Tableau de bord des modules

```bash
./scripts/etat-des-modules.sh
```

Note chaque module sur sept critères, **recalculés depuis le code** : déclaré au
build, routé, signature de passerelle vérifiée, chaîne Spring Security, test de
chaîne HTTP, test BlockHound, collection Postman. Chaque critère correspond à un
défaut que ce dépôt a réellement connu.

---

## Démarrage

### Prérequis

JDK 17 · Maven 3.9+ · Docker

### 1. Infrastructure

```bash
cp .env.example .env    # renseigner les valeurs
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

### 3. Démarrer, dans cet ordre

```bash
./scripts/start-platform.sh --check    # état de l'infrastructure et des services
```

L'ordre n'est pas arbitraire — deux dépendances le contraignent :

| Ordre | Module | Pourquoi à cette place |
|---|---|---|
| 1 | `config-service` (8888) | sert la configuration de tous les autres. Sans lui, chacun démarre sur ses valeurs par défaut — **sans erreur visible**, ce qui est pire qu'un échec franc |
| 2 | `interface-service` (8088) | **autorité JWT**. Les autres démarrent sans elle, mais tant qu'elle est absente ils **refusent tous les jetons** |
| 3 | `gateway-service` (8081) | point d'entrée unique |
| 4+ | auth, user, media, security, setting, quotas | indépendants les uns des autres |

> **Un 401 systématique sur une plateforme en apparence démarrée vient presque
> toujours d'`interface-service` absent.** Les journaux des autres services le
> disent — « Échec du rafraîchissement JWKS : Connection refused … :8088 ». Ils
> continuent de démarrer, le chargement des clés étant non bloquant et réessayé,
> mais sans clé publique aucune signature n'est vérifiable.

### 4. Vérifier

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8088/dev/.well-known/jwks.json    # les clés publiques
```

Les [collections Postman](postman/) couvrent les **211 requêtes**, avec l'ordre de
test conseillé module par module.

> `baseUrl` pointe sur le **Gateway**. Un appel direct à un service renvoie 403 :
> c'est le comportement attendu, pas une panne.

### Secrets

Aucune valeur réelle ne doit figurer dans le dépôt. `.env` est ignoré par Git, la
CI refuse toute PR qui en committerait un, et la protection de push de GitHub
analyse chaque poussée.

> Cette dernière barrière est celle qui compte. Elle a arrêté un script portant
> vingt-cinq identifiants réels alors que nos propres vérifications l'avaient
> déclaré propre : elles contrôlaient des noms de fichiers et quelques motifs, et
> ne connaissaient ni Twilio ni Stripe. **Ne jamais la désactiver, ni suivre les
> liens « allow secret » qu'elle propose.**

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

**2512 tests**, 0 échec. Répartition : auth 1127 · platform-commons 277 ·
gateway 262 · interface 231 · user 205 · security 145 · media 132 · setting 61 ·
quotas 59 · config 13.

Plancher de couverture : **21,41 %** des instructions, **13,60 %** des branches.

La couverture est basse et [docs/TESTING.md](docs/TESTING.md) ne le dissimule
pas. Ce qui compte davantage, c'est **où** portent les tests : le contrat JWT,
les filtres du Gateway, le chiffrement des documents, le contrôle d'accès, la
persistance. Plusieurs suites écrites ici ont révélé un défaut réel — dont un qui
rejetait la totalité des jetons légitimes, et un autre qui rendait un module
entièrement injoignable.

Le garde-fou de CI n'exige pas un seuil absolu : il interdit que la couverture
**baisse**. Un seuil à 80 % sur une base à 21 % serait désactivé en deux jours.

### Deux références engendrées, jamais écrites à la main

`postman/` (211 requêtes) et [docs/API-FRONTEND.md](docs/API-FRONTEND.md) (203
endpoints) sont **dérivées** des contrôleurs, de `CheminCible` et des
`SecurityConfig`. À régénérer après tout ajout d'endpoint :

```bash
./scripts/postman/generate.sh
python3 scripts/postman/generer_reference_frontend.py
```

> Ce n'est pas une formalité. C'est la référence engendrée — et non un test — qui
> a trouvé un chemin d'endpoint faux : les listes de chemins publics portaient la
> bonne valeur, donc la garde qui les compare passait, tandis que l'annotation du
> contrôleur en produisait un autre.

---

## Sécurité

### Ce qui est en place

| Mesure | Détail |
|---|---|
| **Point d'entrée unique** | signature HMAC-SHA256 horodatée, fenêtre anti-rejeu de 5 s |
| **Jetons** | RS256, `kid` en en-tête JWS, rotation avec période de grâce, audiences multiples |
| **Révocation** | par `jti` et par porteur, appliquée **au Gateway** sur tout le trafic |
| **Jetons de rafraîchissement** | seule leur empreinte SHA-256 est stockée ; le secret ne l'est jamais |
| **Débit** | plafonds par type d'endpoint, distribués via Redis, repli local en cas de panne |
| **Adresse client** | `X-Forwarded-For` cru uniquement depuis un proxy déclaré |
| **Second facteur** | TOTP RFC 6238, vérifié contre les vecteurs d'essai officiels, codes de secours |
| **Documents** | AES-256-GCM, clé dérivée par HMAC propre à chaque couple (utilisateur, document) |
| **Mots de passe** | BCrypt, verrouillage de compte après tentatives échouées |
| **En-têtes** | CSP, HSTS, `X-Frame-Options: DENY`, CORS à liste blanche explicite |
| **Documentation OpenAPI** | joignable **seulement** par la passerelle, sous authentification HTTP dédiée |

### Ce qui a été trouvé et corrigé

Les audits successifs ont mis au jour des défauts sérieux, tous corrigés et
verrouillés par des tests. Ils partagent presque tous la même forme : une
protection écrite, présente, et jamais atteinte.

- **le Gateway rejetait en 401 la totalité des jetons légitimes** — une lecture
  de claim qui n'était jamais vraie ;
- **chaque filtre de sécurité s'exécutait deux fois**, divisant par deux le
  plafond réel de tentatives de connexion ;
- **débit, règles d'adresses et pays refusés étaient tous contournables** en
  modifiant un en-tête HTTP ;
- **media-service était entièrement injoignable** — deux défauts indépendants, et
  aucun test ne le disait, parce qu'aucun test n'appelait un endpoint comme la
  passerelle l'appelle ;
- **les documents étaient servis chiffrés** : le déchiffrement était écrit et
  jamais appelé ;
- **security-service n'avait aucune chaîne de sécurité**, alors qu'il décide de
  la politique de toute la plateforme ;
- **les jetons OAuth2 voyageaient dans l'URL** de redirection, donc dans
  l'historique du navigateur, les journaux de tout proxy et l'en-tête `Referer` ;
- **un appel DNS bloquant de 113 ms** sur le chemin de chaque requête du Gateway,
  déclenchable par un en-tête forgé ;
- **deux IDOR** : lecture du profil et des fichiers d'autrui ;
- **une collision de dérivation de clé** dans le chiffrement des pièces d'identité.

Le récit complet, avec la cause de chacun, est dans
[docs/GATEWAY-SECURITY.md](docs/GATEWAY-SECURITY.md) et
[docs/SECURITY-CHAIN.md](docs/SECURITY-CHAIN.md).

### Signaler une faille

Voir [SECURITY.md](SECURITY.md). **Jamais dans une issue publique.**

---

## Conformité

[docs/COMPLIANCE.md](docs/COMPLIANCE.md) détaille l'état réel, sans complaisance.

| Référentiel | État |
|---|---|
| **OWASP Top 10 (2021)** | 7 catégories satisfaites, 3 partielles |
| **MITRE CWE Top 25** | analysé par CodeQL ; 7 CWE effectivement corrigés |
| **RGPD — droit à l'effacement (art. 17)** | complet, les 6 services détenteurs |
| **RGPD — rectification (art. 16)** | complet |
| **RGPD — portabilité (art. 20)** | absent |

### Droit à l'effacement

`POST /auth/erasure/me` efface réellement les données de la personne, puis
propage la demande par événement Kafka aux services qui en détiennent d'autres.
Chacun accuse réception ; le registre d'exécution permet de **prouver**
l'effacement — l'article 5.2 l'exige, et une intention ne se démontre pas.

Effacer n'est pas supprimer la ligne : supprimer casserait les références des
journaux d'audit et des écritures de facturation. Les champs identifiants sont
écrasés, l'identifiant technique est conservé.

Les **développeurs du portail** disposent de leur propre point d'entrée,
`POST /dev/erasure/me` : ils forment une population distincte, dont l'identité
vit dans `interface-service` et non dans `auth-service`.

---

## Intégration continue

| Workflow | Déclenchement | Bloquant |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | push, PR | compilation, tests, non-régression de couverture, `.env` ou clé privée committés |
| [`security.yml`](.github/workflows/security.yml) | push, PR, hebdomadaire | secrets (Gitleaks), vulnérabilités CVSS ≥ 7 (Trivy), licences incompatibles |
| [`dependency-review.yml`](.github/workflows/dependency-review.yml) | PR | dépendance ajoutée en gravité élevée ou sous licence interdite |

**Politique de blocage** : critique et élevé bloquent, le reste est signalé. Une
CI qui échoue pour tout est une CI qu'on désactive.

CodeQL alimente l'onglet Sécurité sans bloquer la fusion — le durcir viendra
quand la base d'alertes existantes aura été traitée.

### Protection de `main`

Une règle de protection ne peut pas être posée par un workflow : le jeton
d'Actions n'a pas les droits d'administration, et c'est voulu — sinon un workflow
modifiable par une PR pourrait lever ses propres garde-fous.

```bash
gh auth login
./scripts/github/protect-main.sh
```

### Dépendances

Dependabot ouvre des PR hebdomadaires, groupées par thème. Les montées
**majeures** sont ignorées : elles se décident, elles ne se subissent pas.

---

## Contribuer

Le projet est sous licence propriétaire ([LICENSE](LICENSE)). Les contributions
externes ne sont pas ouvertes.

1. Brancher depuis `main` — `main` est protégée.
2. Écrire le test **avant** le correctif quand il s'agit d'un défaut : c'est ce
   qui prouve qu'on a compris la cause, et non seulement le symptôme.
3. Remplir le [modèle de PR](.github/pull_request_template.md) — ses listes de
   contrôle reprennent les pièges qui ont déjà coûté cher ici.
4. Attendre le vert de la CI et l'approbation d'un `CODEOWNER`.

### Les pièges du dépôt

Chacun a déjà causé un défaut réel.

| Piège | Conséquence |
|---|---|
| Un `WebFilter` annoté `@Component` **et** ajouté à la chaîne Spring Security | s'exécute deux fois par requête |
| `@PreAuthorize` sans `@EnableReactiveMethodSecurity` | l'annotation est **inerte**, en silence |
| Lire `X-Forwarded-For` directement | débit et règles d'adresses contournables |
| `claims.get("roles", String.class)` | lève sur un jeton utilisateur — passer par `JwtClaimsReader` |
| Un rôle dans `@PreAuthorize` sur un endpoint à identifiant de chemin | IDOR : un rôle n'est pas une autorisation |
| `switchIfEmpty` sur un `Mono<Void>` final | il se termine vide en cas de **succès** : le repli s'exécute à chaque requête |
| Un `Mono` vide traversant un `WebFilter` | **200 OK avec un corps vide**, pas un blocage |
| Remplacer `@{argLine}` dans le surefire parent | couverture à zéro, sans erreur |
| `.then(x)` en Reactor | évalue `x` à l'assemblage — utiliser `Mono.defer` |
| Un endpoint ouvert d'un seul côté | public dans le service mais pas au Gateway : 401 inexplicable |
| Signer sur le chemin décodé | la signature porte sur le chemin **brut** transformé |

---

## Feuille de route

| Échéance | Sujet |
|---|---|
| **Immédiat** | Faire tourner les identifiants ayant vécu dans l'ancien dépôt |
| **Immédiat** | Test de chaîne HTTP pour interface, user, setting et quotas |
| **Court terme** | Auditer `interface-service` — 36 endpoints, jamais audité, et c'est l'autorité JWT |
| **Court terme** | BlockHound dans security, setting et quotas |
| **Court terme** | Chaîne de sécurité pour `config-service`, qui n'en a aucune |
| **Court terme** | Unifier les quatre filtres de liste d'adresses divergents |
| **Court terme** | Portabilité des données (RGPD art. 20) |
| **Court terme** | Purger les comptes inactifs et la consommation d'API |
| **Moyen terme** | `geo-service`, `payment-service`, `notification-service` |
| **Moyen terme** | SBOM CycloneDX, signature des artefacts |
| **Moyen terme** | Remplacer le substitut WebAuthn par une vraie vérification |

---

## Documentation

| Document | Contenu |
|---|---|
| [docs/API-FRONTEND.md](docs/API-FRONTEND.md) | Référence engendrée : 203 endpoints, chemins publics, authentification |
| [docs/GATEWAY-SECURITY.md](docs/GATEWAY-SECURITY.md) | Audit de sécurité, failles trouvées et corrigées, IDOR, injections |
| [docs/SECURITY-CHAIN.md](docs/SECURITY-CHAIN.md) | Audit de la chaîne : filtres, frontière de confiance, interservice |
| [docs/COMPLIANCE.md](docs/COMPLIANCE.md) | RGPD, OWASP, MITRE, registre des données |
| [docs/ROLES.md](docs/ROLES.md) | Modèle de rôles : SUPER_ADMIN, ADMIN, DEVELOPER |
| [docs/TESTING.md](docs/TESTING.md) | Stratégie de test, couverture, conventions |
| [docs/JWT-CONTRACT.md](docs/JWT-CONTRACT.md) | Contrat des jetons : claims, audiences, rotation |
| [docs/NON-BLOCKING.md](docs/NON-BLOCKING.md) | Politique réactive, vérification BlockHound |
| [docs/QUOTAS.md](docs/QUOTAS.md) · [docs/SETTINGS.md](docs/SETTINGS.md) | Quotas et paramètres |
| [docs/AUTH-SERVICE-R2DBC-MIGRATION.md](docs/AUTH-SERVICE-R2DBC-MIGRATION.md) | Migration JPA vers R2DBC |
| [postman/README.md](postman/README.md) | Collections et ordre de test |
| [SECURITY.md](SECURITY.md) | Signalement de failles |

---

<div align="center">

Tous droits réservés — voir [LICENSE](LICENSE)

</div>
