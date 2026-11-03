# Sécurité du Gateway et contrôle d'accès

> Audit et corrections du 2026-09-03. Le Gateway est le seul point d'entrée :
> toute faille qui s'y trouve est une faille de toute la plateforme.

---

## 1. Ce qui a été trouvé

Par ordre de gravité. Les six premiers points sont corrigés et couverts par des
tests ; le dernier tableau liste ce qui reste ouvert.

### 1.1 Panne totale — aucun jeton légitime n'était accepté

`JwtWebFilter` lisait le rôle ainsi :

```java
String role = claims.get("roles", String.class);
```

L'autorité émet deux formes selon le porteur :

| Porteur | Claim | Type |
|---|---|---|
| Développeur | `role` | `String` |
| Utilisateur final | `roles` | `List<String>` |

Aucune des deux ne correspond. Pour un jeton utilisateur, JJWT lève
`RequiredTypeException` ; pour un jeton développeur, `roles` est absent, et
`new SimpleGrantedAuthority(null)` lève `IllegalArgumentException`. Les deux
étaient captées par `onErrorResume` et converties en **401**.

Autrement dit : **tout endpoint protégé de la plateforme renvoyait 401**. Seuls
les chemins publics (`/api/v1/auth/**`) fonctionnaient — ce qui explique que le
défaut ait pu passer inaperçu.

La lecture est désormais centralisée dans `JwtClaimsSupport`, qui accepte les
deux formes. `JwtWebFilterTest` échoue si la régression revient.

### 1.2 Chaque filtre de sécurité s'exécutait deux fois

WebFlux place automatiquement dans la chaîne HTTP **tout** bean de type
`WebFilter`. Spring Security en est un lui-même, et les filtres confiés à
`addFilterAt` s'exécutent à l'intérieur. Les sept filtres du Gateway étaient à
la fois `@Component` **et** ajoutés à la chaîne de sécurité : ils traversaient
donc les deux.

Conséquences :

- le compteur de rate limiting était incrémenté **deux fois par requête** — le
  plafond réel valait la moitié de l'annoncé, soit **2 tentatives de connexion
  accordées au lieu de 5** ;
- deux allers-retours Redis par requête et par contrôle ;
- chaque événement de sécurité journalisé en double, ce qui fausse toute alerte
  fondée sur un décompte.

Les filtres sont maintenant construits explicitement dans `SecurityConfig` et ne
sont plus des beans. `FilterWiringTest` échoue si l'un d'eux redevient
`@Component`.

### 1.3 Rate limiting, liste noire et géo-blocage tous contournables

Quatre filtres déterminaient l'IP du client ainsi :

```java
String xff = request.getHeaders().getFirst("X-Forwarded-For");
if (xff != null) return xff.split(",")[0].trim();
```

`X-Forwarded-For` est écrit par le client autant que par un proxy — rien ne les
distingue à l'arrivée. En changer la valeur à chaque requête suffisait à :

- obtenir un compteur de rate limiting neuf **à chaque appel** ;
- se soustraire à la liste noire d'IP ;
- contourner le blocage géographique ;
- **attribuer ses propres violations à l'IP d'un tiers**, et la faire
  blacklister à sa place.

`ClientIpResolver` ne croit ces en-têtes que si le pair TCP figure dans
`gateway.trusted-proxies`, et lit la chaîne **de droite à gauche** — seules les
entrées ajoutées par nos propres proxys font foi. Par défaut la liste est vide :
seule l'adresse de la socket compte.

> ⚠️ **En production derrière un ALB, Nginx ou Cloudflare**, renseigner
> `gateway.trusted-proxies` — sinon toutes les requêtes seront comptées sous
> l'IP unique du proxy, et le rate limiting deviendra un plafond global.

### 1.4 La révocation ne s'appliquait pas au trafic entrant

Une signature RSA valide prouve que l'autorité a émis le jeton, **pas qu'il est
encore en vigueur**. Le Gateway ne consultait aucune liste de révocation :
déconnexion, révocation d'urgence et suspension de compte restaient sans effet
sur tout le trafic routé vers user-service, media-service ou quotas-service.

`TokenRevocationChecker` interroge la liste publiée par l'autorité
(`dev:token:blacklist:<jti>`, `dev:revoked:<sub>`). En cas de panne Redis, il
**refuse** par défaut : une révocation ignorée est une porte ouverte.
`gateway.revocation.fail-open=true` inverse le compromis — c'est un choix
légitime, mais il doit être conscient.

### 1.5 Trois listes noires d'IP qui s'ignoraient

| Écrivain | Clé | Base Redis |
|---|---|---|
| Gateway | ensemble `ip_blacklist` | 0 |
| Console d'admin | `ip:blacklist:<ip>` | 3 |
| Filtre interface-service | `blacklist:ip:<ip>` | 3 |

Bloquer une IP depuis la console d'administration **n'avait aucun effet sur le
trafic entrant**. Le blocage était écrit, jamais lu — et le commentaire du code
affirmait le contraire (« compatible avec le Gateway »).

Les trois utilisent désormais `security:ip:blacklist:<ip>` dans une base de
sécurité partagée (`gateway.shared-security.redis-database`, alignée sur
interface-service).

### 1.6 Deux contrôles JWT manquants

| Manque | Conséquence |
|---|---|
| L'audience n'était validée que **si le claim existait** | Un jeton sans `aud` passait sans contrôle : tout jeton signé par l'autorité était accepté, quel qu'en soit l'usage prévu |
| Aucune vérification du claim `type` | Un **jeton de rafraîchissement** — même signature, même audience, mais durée de vie bien plus longue — servait de jeton d'accès, annulant l'intérêt de la courte durée de vie des jetons d'accès |

### 1.7 Le secret partagé voyageait en clair à chaque requête

`GatewaySignatureFilter` ajoutait `X-Secret-Key: <clé>` sur chaque requête
proxifiée, « pour compatibilité ». Le moindre journal d'en-têtes, trace de
débogage ou capture réseau interne divulguait la clé qui authentifie **tout** le
trafic inter-services. Les services ne validaient déjà que la signature HMAC :
l'en-tête est supprimé.

---

## 2. IDOR — références directes à des objets

Un identifiant présent dans le chemin est une donnée fournie par le client, au
même titre qu'un paramètre. Vérifier l'authentification dit **qui** est
l'appelant ; il reste à vérifier que la ressource **lui appartient**.

### 2.1 `GET /files/{userId}/{filename}` — corrigé

L'endpoint était en `permitAll` et ne vérifiait aucune appartenance. Remplacer
le segment `userId` par celui d'un autre suffisait à lire ses fichiers —
**sans même être authentifié**.

Il exige désormais une authentification et que l'appelant soit le propriétaire
ou un administrateur.

> Si le produit veut des avatars visibles par les autres utilisateurs, c'est une
> décision explicite à prendre : elle passe par une URL publique dédiée portant
> un identifiant non devinable, pas par la levée de ce contrôle.

### 2.2 `GET /users/{id}` — corrigé

L'annotation `@PreAuthorize("hasAnyRole('USER','ADMIN')")` n'exigeait qu'un
compte authentifié. **N'importe quel utilisateur pouvait lire le profil complet
de n'importe quel autre** — nom, adresse, téléphone, date de naissance — en
faisant varier l'identifiant.

Un rôle dit ce que l'appelant *est*, pas ce à quoi il a *droit d'accéder*.

Le refus se présente en **404 et non 403** : un 403 confirmerait l'existence du
compte et permettrait d'énumérer les utilisateurs par identifiant.

### 2.3 Ce qui était déjà correct

`media-service` fait passer systématiquement l'identité de l'appelant à la
couche service à côté de l'identifiant de ressource, et `DocumentServiceImpl`
vérifie l'appartenance avant tout téléchargement. C'est le modèle à suivre :
**l'autorisation appartient à la couche qui connaît la ressource**, pas à une
annotation posée sur le contrôleur.

---

## 3. Injections SQL

**Aucune trouvée.** Toutes les requêtes utilisent des paramètres liés, y compris
celles qui en ont l'air :

```java
@Query("SELECT * FROM users WHERE auth_user_id LIKE CONCAT('%', :query, '%') AND deleted_at IS NULL")
Flux<User> searchUsers(String query);
```

`CONCAT` est évalué **par la base**, sur un paramètre lié : la valeur n'est
jamais interprétée comme du SQL. Aucune construction de requête par
concaténation, `String.format` ou `StringBuilder` n'existe dans le code.

Aucun tri ni ordre dynamique n'est exposé non plus — c'est la voie d'injection
qui subsiste habituellement malgré des paramètres liés, puisqu'un nom de colonne
ne peut pas en être un.

**Pour que cela le reste** : ne jamais introduire de fragment SQL construit à
partir d'une entrée. Si un tri dynamique devient nécessaire, valider le nom de
colonne contre une liste blanche explicite — jamais en l'échappant.

---

## 4. Configuration à revoir avant la production

| Clé | Défaut | Pourquoi y revenir |
|---|---|---|
| `gateway.trusted-proxies` | *(vide)* | **À renseigner derrière un proxy**, sinon le rate limiting devient un plafond global |
| `gateway.shared-security.redis-database` | `3` | Doit rester aligné sur interface-service. Un désalignement ne lève aucune erreur — seulement des listes vides, donc des contrôles qui ne bloquent plus rien |
| `gateway.revocation.fail-open` | `false` | Une panne Redis refusera les requêtes authentifiées. Compromis assumé |
| `cors.allowed-origins` | localhost | Le démarrage échoue si `*` est utilisé avec les identifiants autorisés |

---

## 5. Ce qui reste ouvert

| Sujet | Risque | Note |
|---|---|---|
| **Secrets du commit `71af837`** | 🔴 Élevé | Le `.env` a été retiré du suivi, mais les valeurs restent dans l'historique Git. **Elles doivent être changées** — les retirer d'un commit ne les rend pas secrètes |
| Signature HMAC ne couvrant pas le corps | 🟠 Moyen | Elle authentifie l'origine de la requête, pas son intégrité. Acceptable tant que le réseau Gateway↔services est maîtrisé |
| Pas de limitation par compte à la connexion | 🟠 Moyen | Le plafond est par IP : une attaque distribuée sur un seul compte passe sous le radar. Un compteur par identifiant serait complémentaire |
| `media-service` sans `@PreAuthorize` | 🟡 Faible | L'autorisation y est faite en couche service, ce qui est plus solide. Signalé pour éviter la conclusion inverse à la lecture |
| WebAuthn non vérifié | 🟠 Moyen | `BiometricAuthServiceImpl.computeExpectedSignature` est un substitut, pas une vérification de signature |
| Aucun test d'intégration | 🟠 Moyen | Voir [TESTING.md](TESTING.md) §4.4 |

---

## 6. Tests qui verrouillent ces corrections

| Suite | Tests | Ce qu'elle empêche de revenir |
|---|---|---|
| `JwtWebFilterTest` | 12 | Rejet des jetons légitimes ; acceptation d'un jeton forgé, expiré, sans audience, d'un autre émetteur, de type rafraîchissement, ou révoqué |
| `ClientIpResolverTest` | 10 | Confiance aveugle aux en-têtes de transfert ; lecture de la chaîne dans le mauvais sens ; élargissement de la confiance sur configuration invalide |
| `FilterWiringTest` | 2 | Retour de la double exécution des filtres |
| `AccessControlIdorTest` | 8 | Lecture du profil ou des fichiers d'autrui ; divulgation d'existence par le code de statut |
