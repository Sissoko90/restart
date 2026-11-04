# Contrat JWT de la plateforme

> Document normatif. Toute modification d'un claim, de l'émetteur, des audiences ou de
> l'algorithme de signature DOIT être répercutée ici **et** dans les services listés
> en section 6 avant d'être déployée.

---

## 1. Autorité de signature

**`interface-service` (port 8088) est la seule autorité de signature de la plateforme.**

| Rôle | Service | Détail |
|---|---|---|
| Détient la clé privée RSA | `interface-service` | `DevKeyRotationServiceImpl`, clés dans `resources/keys/dev_*.pem` |
| Signe les tokens | `interface-service` | `DevJwtServiceImpl` |
| Expose les clés publiques | `interface-service` | `GET /dev/.well-known/jwks.json` (standard JWKS) |
| Signe pour le compte d'autrui | `auth-service` → `interface-service` | `POST /internal/jwt/sign` (header `X-Internal-Secret`) |
| Valident uniquement | gateway, auth, user, media, quotas, setting | via leur `JwksKeyProvider` |

Aucun autre service ne possède de clé privée. Keycloak n'est plus utilisé.

---

## 2. En-tête JWS

| Champ | Valeur | Obligatoire |
|---|---|---|
| `alg` | `RS256` | oui |
| `typ` | `JWT` | oui |
| `kid` | Key ID de la clé de signature courante | **oui** |

Le `kid` est calculé par `DevKeyRotationServiceImpl.generateKeyId()` :
`base64url(SHA-256(publicKey.getEncoded())[0..8])`.

**Le `kid` doit être dans l'EN-TÊTE, pas dans le corps.** Les consommateurs résolvent
la clé publique via `JwksKeyProvider.getPublicKey(kid)`. Pendant une rotation, plusieurs
clés sont valides simultanément (période de grâce de 7 jours par défaut) : sans `kid`,
la validation échoue de façon non déterministe.

---

## 3. Claims standards

| Claim | Type | Contenu |
|---|---|---|
| `iss` | String | `interface-service` — configuré par `jwt.dev.issuer` (émission) / `jwt.issuer` (validation) |
| `aud` | Array&lt;String&gt; | **toutes** les audiences de `jwt.dev.audience` (voir §4) |
| `sub` | String | identifiant du porteur (développeur ou utilisateur final) |
| `jti` | String (UUID) | identifiant unique du token, clé de la blacklist Redis |
| `iat` | Date | émission |
| `exp` | Date | expiration — 1 h par défaut (access), 7 j (refresh) |

---

## 4. Audiences

`interface-service` émet un tableau `aud` contenant **toutes** les audiences configurées :

```yaml
jwt:
  dev:
    audience: restart-developers,gateway-service,auth-service,user-service,media-service,quotas-service,setting-service
```

Chaque consommateur exige que **sa** propre audience figure dans le tableau :

| Service | Clé de config | Valeur attendue |
|---|---|---|
| gateway-service | `jwt.audience` (liste) | au moins une audience de la liste |
| media-service | `jwt.audience` | `media-service` |
| user-service | — (non vérifiée aujourd'hui) | — |
| auth-service | — (non vérifiée aujourd'hui) | — |

> ⚠️ Ajouter un service consommateur impose d'ajouter son audience dans
> `jwt.dev.audience` **avant** de déployer le service, sinon il rejette tous les tokens
> avec `Invalid JWT audience`.

---

## 5. Claims applicatifs, par type de token

Le claim `type` discrimine la nature du token. Il est **obligatoire**.

### 5.1 `type: developer` — développeur du portail
Émis par `DevJwtServiceImpl.generateToken(User)` lors du login sur interface-service.

| Claim | Type | Note |
|---|---|---|
| `type` | `"developer"` | exigé par `auth-service` pour accéder au SDK `/auth/**` |
| `developerId` | String (UUID) | |
| `email` | String | |
| `username` | String | |
| `role` | String | `DEVELOPER` par défaut |

### 5.2 `type: user` — utilisateur final d'un projet
Émis par `DevJwtServiceImpl.generateTokenForUser(...)`, appelé par `auth-service`
via `POST /internal/jwt/sign`.

| Claim | Type | Note |
|---|---|---|
| `type` | `"user"` | |
| `userId` | String | identique à `sub` |
| `projectId` | String \| null | `null` pour les utilisateurs internes |
| `email`, `username` | String | optionnels |
| `roles` | Array&lt;String&gt; | rôles actifs, préfixés `ROLE_` |
| `primaryRole` | String | rôle principal, ajouté par `auth-service` |
| `phone` | String | ajouté par `auth-service` |

### 5.3 `type: service` — token de service (machine-to-machine)
| Claim | Valeur |
|---|---|
| `type` | `"service"` |
| `serviceName` | nom du service appelant |

### 5.4 `type: temp_2fa` — token temporaire de vérification 2FA
Durée de vie : 5 minutes. Ne donne accès à aucune ressource métier.

### 5.5 `type: refresh` / `refresh_user` — tokens de rafraîchissement
Ne doivent **jamais** être acceptés comme access token. Contiennent uniquement
`userId`/`developerId`, `projectId` et `type`.

---

## 6. Résolution du rôle (ordre normatif)

Tout service dérivant un rôle DOIT suivre cet ordre, **depuis les claims validés** :

1. `primaryRole` (String)
2. `roles` (String, ou premier élément si c'est une liste)
3. `role` (String)

**Interdit** : lire le rôle depuis un header HTTP (`X-User-Role`) ou depuis un payload
JWT décodé sans vérification de signature. Les headers `X-Auth-User-Id` et `X-User-Role`
sont produits par le Gateway à partir des claims validés et sont purgés de toute requête
entrante (`GatewaySignatureFilter.TRUSTED_IDENTITY_HEADERS`).

---

## 7. Révocation

Deux niveaux, tous deux dans Redis :

1. **Par token** : `jti` ajouté à la blacklist jusqu'à son `exp`.
2. **Par utilisateur** : marqueur « tous les tokens de `sub` sont révoqués ».

Chaque consommateur vérifie les deux après la validation cryptographique.

---

## 8. Rotation des clés

| Paramètre | Défaut | Clé |
|---|---|---|
| Activation | `false` | `jwt.dev.key.rotation.enabled` |
| Intervalle | 90 jours | `jwt.dev.key.rotation.interval-days` |
| Période de grâce | 7 jours | `jwt.dev.key.rotation.grace-period-days` |
| Rafraîchissement JWKS côté consommateurs | 300 s | `jwt.jwks.refresh-interval-seconds` |

Séquence : nouvelle paire générée → signature avec la nouvelle clé → l'ancienne clé
publique reste exposée dans le JWKS pendant la période de grâce → suppression.

La période de grâce ne fonctionne que si les consommateurs résolvent la clé par `kid` (§2).

---

## 9. Points de vérification avant tout déploiement

- [ ] `iss` émis == `jwt.issuer` de chaque consommateur
- [ ] L'audience de chaque consommateur figure dans `jwt.dev.audience`
- [ ] Le `kid` de l'en-tête est présent et exposé par `/dev/.well-known/jwks.json`
- [ ] Le claim `type` correspond à l'usage attendu par le service cible
- [ ] Aucun rôle n'est dérivé d'un header HTTP
