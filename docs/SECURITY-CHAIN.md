# Audit de la chaîne de sécurité

> Audit du 2026-09-30. Porte sur le trajet d'une requête, la frontière de
> confiance, la communication interservice et l'IDOR sur l'ensemble des
> endpoints.
>
> Complète [GATEWAY-SECURITY.md](GATEWAY-SECURITY.md), qui raconte les correctifs
> antérieurs.

---

## 1. Le trajet d'une requête

Deux chaînes se succèdent. Les confondre est la première source d'erreur, parce
que l'ordre entre elles n'est pas celui qu'on croit.

### Chaîne globale WebFlux — avant tout le reste

| Ordre | Filtre | Rôle |
|---|---|---|
| `MIN_VALUE + 10` | `GatewaySignatureFilter` | **purge les en-têtes d'identité du client**, puis signe la requête en HMAC |
| défaut | `ZipBombProtectionFilter` | refuse une charge compressée disproportionnée |

`GatewaySignatureFilter` s'exécute **avant** Spring Security (`-100`). C'est
voulu : les en-têtes d'identité doivent disparaître avant que quoi que ce soit ne
les lise.

### Chaîne Spring Security — ordre `-100`

| Position | Filtre | Rôle |
|---|---|---|
| `FIRST` | `PathTraversalWebFilter` | `../`, encodages, octet nul |
| `FIRST + 1` | `IpBlacklistWebFilter` | IP en liste noire (Redis partagé) |
| `FIRST + 1` | `GeoLocationWebFilter` | pays bloqués |
| `FIRST + 1` | `RedisRateLimitWebFilter` | plafond de débit |
| `FIRST + 1` | `XssProtectionWebFilter` | motifs dans l'URL et les paramètres |
| `FIRST + 1` | `BodyXssScannerWebFilter` | motifs dans le corps |
| `FIRST + 1` | `ApiVersionWebFilter` | version d'API |
| `AUTHENTICATION` | `JwtWebFilter` | signature RS256, émetteur, audience, type, **révocation** |

> **Observation.** Les six filtres ajoutés par `addFilterAfter(..., FIRST)`
> reçoivent tous la même position : leur ordre relatif n'est pas défini. Ce n'est
> pas une faille — chacun bloque indépendamment — mais une IP en liste noire peut
> voir son corps analysé avant d'être refusée. Du travail inutile, pas une fuite.

### Ce que valide `JwtWebFilter`, dans l'ordre

1. signature RS256, clé résolue par le `kid` de l'en-tête JWS ;
2. émetteur attendu ;
3. audience — le jeton doit désigner ce service ;
4. **type** — `user` ou `developer` ; un jeton de rafraîchissement est refusé ;
5. **révocation** — par `jti` et par porteur, dans le Redis partagé.

Les quatre premiers points sont locaux. Le cinquième exige Redis : c'est le seul
contrôle qui puisse échouer pour une raison d'infrastructure.

---

## 2. La frontière de confiance

**Le Gateway remplace les en-têtes d'identité du client par les siens**, dérivés
des claims validés. Deux temps :

**1. Purge**, dans `GatewaySignatureFilter`, avant tout le reste :

```java
ServerHttpRequest mutatedRequest = request.mutate()
    .headers(headers -> TRUSTED_IDENTITY_HEADERS.forEach(headers::remove))
    .header("X-Gateway-Timestamp", timestamp)
    .header("X-Gateway-Signature", signature)
    .header("X-Gateway-Request-Id", requestId)
```

L'ordre importe : `headers(...)` s'applique avant `header(...)`. Inversé, un
client envoyant `X-Auth-User-Id` aurait vu sa valeur recopiée telle quelle.

**2. Réécriture**, dans `GatewayRoutesConfig`, à partir des claims validés :

```java
if (userId != null)   headers.set("X-Auth-User-Id", userId);
if (userRole != null) headers.set("X-User-Role", userRole);
headers.set("X-Forwarded-For", clientIp);   // IP résolue, jamais celle du client
```

Les en-têtes que seul le Gateway a le droit d'émettre sont listés dans
`GATEWAY_OWNED_HEADERS` et exclus de la recopie : une valeur fournie par le client
ne peut donc pas passer, même si la purge amont était contournée.

> Deux mécanismes redondants portent désormais l'IP : `X-Forwarded-For`, écrasé
> par le routage, et `X-Client-Ip`, posé par le filtre de signature. La redondance
> n'est pas gênante — les deux portent la valeur résolue — mais `X-Client-Ip` est
> le contrat explicite, et le seul que les services doivent lire.

### L'IP sur laquelle reposaient les décisions était fournie par le client

Huit emplacements lisaient `X-Forwarded-For` directement — quatre filtres du
Gateway, le contrôle d'accès direct et la journalisation d'audit d'auth-service,
trois filtres d'interface-service, plus media, quotas, setting et user.

Portée réelle, à ne pas exagérer : pour le trafic **routé par le Gateway**,
`GatewayRoutesConfig` écrasait déjà `X-Forwarded-For` avec l'IP résolue. L'IP était
donc honnête sur ce chemin. L'exploitation supposait un **appel direct** à un
service — lequel exige la signature HMAC, dont le secret est compromis.

Ce qui était perdu sans condition, en revanche : les journaux du Gateway lui-même
inscrivaient l'adresse choisie par l'appelant sur toute tentative d'attaque, et le
signal de détection disparaissait.

Tout est désormais centralisé : `ClientIpResolver` dans le Gateway,
`GatewayClientIp` dans les services aval. Plus aucune lecture directe.

---

## 3. Communication interservice

### Ce que couvre la signature

```java
buildSignatureData(timestamp, path, method, requestId)
```

La signature couvre **le chemin et la méthode**. Une signature capturée sur
`GET /auth/profile` ne peut donc pas être rejouée sur
`DELETE /internal/auth/users/{victime}` — c'est le point qui rend l'ensemble
tenable.

### Ce qu'elle ne couvre pas

Le **corps** de la requête. La signature authentifie l'origine et la cible, pas
le contenu. Un intermédiaire capable de modifier le corps en transit ne serait pas
détecté. En réseau interne chiffré, la portée est faible ; à documenter comme
limite assumée.

### Le secret est compromis

`GATEWAY_SECRET_KEY` figure dans le `.env` du commit `71af837`. Il doit être
considéré comme connu de quiconque a accès à l'historique. **Le faire tourner est
la mesure la plus urgente de ce dépôt** : c'est lui seul qui distingue un appel du
Gateway d'un appel direct.

### Les quatre services et leur contrôle d'accès direct

| Service | IP lue depuis | État |
|---|---|---|
| `auth-service` | pair TCP | corrigé ce jour — lisait `X-Forwarded-For` |
| `media-service` | pair TCP | correct |
| `quotas-service` | pair TCP | correct |
| `setting-service` | pair TCP | correct |

`auth-service` lisait `X-Forwarded-For` puis `X-Real-IP` avant de retomber sur
l'adresse réelle. Il suffisait d'envoyer `X-Forwarded-For: 127.0.0.1` pour
franchir la liste blanche d'IP et faire passer un appel direct pour un appel du
Gateway.

Le contrôle reposait alors entièrement sur la signature HMAC — solide, mais dont
le secret est compromis. Autre perte, moins visible : le journal
« Direct access blocked from IP » ne se déclenchait pas pour un appelant qui
usurpait l'en-tête. On perdait donc aussi le **signal de détection**.

Aucun proxy ne s'interpose entre le Gateway et les services : l'adresse du pair
TCP est la seule information fiable, et la seule nécessaire.

---

## 4. IDOR — revue complète

Méthode : recenser tout endpoint dont un identifiant vient du client
(`@PathVariable`, `@RequestParam`), puis établir sa protection **effective** —
chaîne de filtres, annotation, et vérification dans la couche service.

Une annotation absente ne signifie pas vulnérable : la chaîne peut couvrir. Une
annotation présente ne signifie pas protégé : `hasRole('USER')` sur un endpoint à
identifiant de chemin ne protège rien.

### Protégés par le chemin

| Service | Chemin | Règle |
|---|---|---|
| `auth-service` | `/admin/**` | `hasRole('ADMIN')` |
| `auth-service` | `/auth-users/**` | `hasRole('ADMIN')` |
| `auth-service` | `/auth/erasure/**` | `hasRole('SUPER_ADMIN')` |
| `auth-service` | `/internal/**` | signature HMAC |
| `user-service` | `/admin/**` | `hasRole('ADMIN')` |
| `user-service` | `/internal/**` | `hasRole('SERVICE')` |
| `quotas-service` | `/admin/quotas/**` | `hasRole('ADMIN')` |
| `setting-service` | `/admin/settings/**` | `hasRole('ADMIN')` |
| `interface-service` | `/dev/admin/keys/**` | `hasRole('SUPER_ADMIN')` |

### Protégés dans la couche service

`media-service` charge la ressource et compare son propriétaire :

```java
if (!document.getUserId().equals(userId)) {
    return Mono.error(new MediaNotFoundException("Document non trouvé"));
}
```

Les méthodes d'administration sont **séparées** (`downloadDocumentAdmin`,
`getDocumentDetailsAdmin`) et atteignables seulement par `/admin/documents`,
gardé par `RoleAuthorizationWebFilter`. C'est le modèle à suivre lorsque
l'identifiant du chemin désigne une ressource et non le porteur.

> `media-service` a `anyExchange().permitAll()` : aucune règle Spring Security.
> Ce n'est pas une faille — la vérification est faite dans la couche service, et
> le Gateway a déjà validé le jeton — mais la défense en profondeur voudrait des
> règles explicites. À reprendre.

### Deux IDOR trouvés et corrigés ce jour

| Service | Endpoints | Ce qu'un tiers pouvait faire |
|---|---|---|
| `quotas-service` | `GET /quotas/{userId}`, `/{userId}/check`, `POST /{userId}/consume`, `/{userId}/release` | lire la consommation d'autrui ; **épuiser le quota d'un client payant** — un déni de service à son insu ; corrompre ses compteurs de facturation |
| `setting-service` | `GET /settings/users/{userId}`, `/{userId}/{key}`, `PUT`, `DELETE` | lire et **modifier** les préférences d'autrui, dont la valeur est une chaîne libre pouvant contenir n'importe quelle donnée personnelle |

Ni la chaîne de filtres (`anyExchange().authenticated()`), ni le contrôleur, ni la
couche service ne vérifiaient l'appelant : les méthodes ne recevaient que
l'identifiant **cible**.

C'est la **quatrième et cinquième** occurrence du même schéma dans ce dépôt —
après le profil et les fichiers de `user-service`. Même cause chaque fois : un
rôle confondu avec une autorisation.

### La règle vit désormais à un seul endroit

`platform-commons/ResourceOwnershipGuard` :

```java
return ResourceOwnershipGuard.guard(userId, refus(userId),
        quotaService.consume(userId, request.getResource(), request.getAmount()));
```

| Choix | Raison |
|---|---|
| Refus traduit en **404**, jamais 403 | un 403 confirme l'existence du porteur, et permet d'énumérer les comptes sans lire une seule donnée |
| `ADMIN`, `SUPER_ADMIN`, `SERVICE` peuvent agir pour autrui | `DEVELOPER` non : c'est un droit de consommation d'API, pas d'administration |
| Sans contexte de sécurité, **refus** | refuser plutôt que laisser passer faute d'information |
| La suite est enveloppée dans `defer` | sans cela elle serait évaluée à l'assemblage, donc la requête partirait en base pendant que l'accès est refusé |
| La tentative est journalisée | c'est le signal qui permet de repérer un balayage d'identifiants |

15 tests verrouillent ces règles.

---

## 5. Cryptographie

### Ce qui est en place et correct

| Usage | Primitive | Verdict |
|---|---|---|
| Signature des jetons | RSA 2048, RS256 | conforme ; 3072 serait plus prospectif |
| Mots de passe | BCrypt, **coût 12** | durci — le défaut de Spring est 10 |
| Pièces d'identité | AES-256-GCM, IV aléatoire de 96 bits par chiffrement, étiquette 128 bits | correct, vérifié par 17 tests |
| Dérivation de clé documentaire | HMAC-SHA256 sur champs préfixés de leur longueur | corrigé — la concaténation précédente était ambiguë |
| Signature Gateway → service | HMAC-SHA256 sur `timestamp\|path\|method\|requestId` | correct |
| Second facteur | HMAC-SHA1, RFC 6238 | SHA-1 est **imposé par la spécification** pour l'interopérabilité ; ce n'est pas une faiblesse ici |
| Codes à usage unique | `SecureRandom.nextInt(900000) + 100000` | uniforme, **sans biais de modulo** |
| Empreintes de fichier | SHA-256 | correct |
| Comparaison de secrets | `MessageDigest.isEqual` via `SecretComparisonUtil` | à temps constant |
| Aléa | `SecureRandom` partout | aucun `Math.random()` ni `new Random()` dans le code de production |

### La faille la plus grave de l'audit

**La clé privée de signature était empaquetée dans le JAR livré.**

```
BOOT-INF/classes/keys/dev_private.pem     1704 octets
-----BEGIN PRIVATE KEY-----
```

Elle vivait dans `src/main/resources/keys/`, chargée par
`classpath:keys/dev_private.pem`. Conséquence : **quiconque disposait de
l'artefact** — une image de conteneur, une sortie de build, un artefact de CI —
pouvait l'extraire et **forger n'importe quel jeton**, pour n'importe quel porteur
et n'importe quel rôle, `SUPER_ADMIN` compris. Tout l'édifice d'authentification
s'effondre sur cette seule extraction.

La clé n'est **pas** dans l'historique Git (`.gitignore` couvre `*.pem`, et elle
est absente du commit `71af837`), ce qui limite l'exposition à ceux qui ont eu
l'artefact.

Corrections :

| Mesure | Détail |
|---|---|
| Chargement depuis le classpath **refusé** | `refuserClasspath()` lève au démarrage, avec le motif |
| Source privilégiée | `JWT_DEV_PRIVATE_KEY` / `JWT_DEV_PUBLIC_KEY`, contenu Base64 — compatible secrets Docker et Kubernetes |
| Alternative | chemin de fichier **hors du classpath**, typiquement un secret monté |
| Sans configuration | le service **refuse de démarrer**, avec la commande de génération |
| Paire éphémère | uniquement si `jwt.dev.key.allow-ephemeral=true`, avec un avertissement appuyé |
| Clés déplacées | `secrets/`, permissions `600`, répertoire ignoré par Git |

Une clé de signature ne doit jamais se trouver dans l'artefact qu'elle protège.

### Remplissage RSA : PKCS#1 v1.5 remplacé par OAEP

`RecoverySecretService` utilisait `RSA/ECB/PKCS1Padding`. Ce remplissage est
vulnérable aux attaques par oracle (Bleichenbacher) : un attaquant qui soumet des
chiffrés modifiés et distingue « remplissage invalide » de « déchiffrement
réussi » retrouve le clair, requête par requête, **sans jamais connaître la clé
privée**.

Remplacé par `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`.

> Conséquence : un secret chiffré avec l'ancien remplissage ne se déchiffre plus.
> Le secret de récupération étant renouvelé périodiquement, les anciennes valeurs
> s'éteignent d'elles-mêmes — mais il faut déclencher un renouvellement au
> déploiement pour ne pas rester sans secret utilisable.

### Comparaison du code TOTP

`totpCode.equals(expectedCode)` s'arrête au premier caractère différent : le temps
de réponse renseigne sur le nombre de caractères corrects. Sur six chiffres et à
travers un réseau, l'exploitation est peu réaliste — mais la correction ne coûte
rien, et un second facteur est précisément ce qu'on ne veut pas voir s'éroder.
Passé à `SecretComparisonUtil.matches`.

### Rotation de clés : correcte, mais à ne pas activer telle quelle

La période de grâce fonctionne : `getAllValidPublicKeysById()` exclut du JWKS les
clés dont `expiresAt` est passé, et la résolution par `kid` permet à un jeton
signé par l'ancienne clé de rester valide pendant la transition.

**Mais les clés tournées ne vivent qu'en mémoire.** Redis ne stocke que
l'identifiant de la clé courante, pas le matériel. Deux conséquences :

- un redémarrage perd la clé tournée : tous les jetons qu'elle a signés deviennent
  invérifiables ;
- deux instances tournent indépendamment : l'instance A signe avec une clé absente
  du JWKS de l'instance B, et les jetons sont refusés **au hasard** derrière un
  répartiteur de charge.

La rotation est désactivée par défaut (`jwt.dev.key.rotation.enabled=false`), ce
qui est prudent. **Ne pas l'activer avant d'avoir persisté le matériel de clé** —
en base chiffrée, ou dans un service de gestion de clés.

---

## 6. Le piège Reactor, cinq fois

Une même famille d'erreur est apparue cinq fois pendant cet audit et les
précédents. Elle mérite d'être connue avant d'écrire une ligne de code réactif
dans ce dépôt.

| Forme | Effet |
|---|---|
| `.then(x)` | `x` est évalué **à l'assemblage**, avant que l'étape précédente ait réussi |
| `f(service.appel())` en argument | même chose : l'argument est évalué à l'appel |
| `Mono.fromRunnable(() -> service.monoRetournant())` | le `Mono` est construit puis **jeté** — rien ne s'exécute |
| `Mono<Void>` + `switchIfEmpty` | un `Mono<Void>` est **toujours** vide : la branche de repli se déclenche toujours |

La dernière a été écrite dans `ResourceOwnershipGuard` lui-même, en connaissant le
piège : la garde refusait l'accès au porteur légitime. Le test l'a vue
immédiatement.

**Règles.** Envelopper dans `Mono.defer` ou passer un `Supplier`. Ne jamais
décider sur la vacuité d'un `Mono<Void>` — transformer en booléen d'abord.
Toujours souscrire ce qu'on construit.

---

## 7. Ce qui reste

| Sujet | Gravité |
|---|---|
| **Faire tourner `GATEWAY_SECRET_KEY`** et tous les secrets du commit `71af837` | urgent — c'est le seul secret qui distingue le Gateway d'un appel direct |
| Renseigner `gateway.trusted-proxies` si un proxy est mis en frontal | sans quoi le plafond de débit devient global |
| `media-service` : remplacer `anyExchange().permitAll()` par des règles explicites | défense en profondeur |
| Signature HMAC couvrant le corps de requête | limite assumée |
| Ordre relatif des six filtres à position égale | cosmétique |
| Retirer la constante `HEADER_X_AUTH_USER_ID` morte de `media-service` | hygiène |
| **Persister le matériel de clé avant d'activer la rotation** | bloquant pour la rotation |
| Déclencher un renouvellement du secret de récupération au déploiement | OAEP invalide les anciennes valeurs |
| RSA 3072 plutôt que 2048 | prospectif |
