# API Restart — intégration frontend

Tout ce qu'il faut savoir pour appeler la plateforme, et les quelques contrats
qui ne se devinent pas.

La référence complète des endpoints est en fin de document, **générée depuis le
code** par `scripts/postman/generer_reference_frontend.py`. Des collections
Postman prêtes à importer sont dans `postman/`, générées depuis la même source.

---

## 1. Un seul point d'entrée : la passerelle

Tous les appels passent par la passerelle, sur le port **8081**. Les ports des
services (8082, 8083, …) ne sont pas joignables depuis l'extérieur, et un appel
direct est refusé : la passerelle signe chaque requête transmise avec un HMAC
que les services vérifient.

```
http://localhost:8081/api/v1/...
```

### Le piège des préfixes

**`/api/v1/auth/**` ne va PAS vers auth-service.** Les deux services exposent
`/auth/**` et `/admin/**`, donc retirer `/api/v1` ne suffit pas à les
distinguer. La passerelle les sépare par des préfixes publics distincts :

| Préfixe à appeler | Service atteint | Chemin reçu par le service |
|---|---|---|
| `/api/v1/auth-sdk/**` | auth-service | `/auth/**` |
| `/api/v1/auth-admin/**` | auth-service | `/admin/**` |
| `/api/v1/auth-users/**` | auth-service | `/auth-users/**` |
| `/api/v1/auth/**` | **interface-service** | `/auth/**` |
| `/api/v1/admin/users/**` | user-service | `/admin/users/**` |
| `/api/v1/admin/quotas/**` | quotas-service | `/admin/quotas/**` |
| `/api/v1/admin/settings/**` | setting-service | `/admin/settings/**` |
| `/api/v1/admin/documents/**` | media-service | `/admin/documents/**` |
| `/api/v1/admin/**` | **interface-service** | `/admin/**` |
| `/api/v1/security-admin/**` | security-service | `/security-admin/**` |
| `/api/v1/users/**` | user-service | `/users/**` |
| `/api/v1/media/**` | media-service | `/media/**` |
| `/api/v1/quotas/**` | quotas-service | `/quotas/**` |
| `/api/v1/settings/**` | setting-service | `/settings/**` |

**L'ordre de ce tableau compte.** `/api/v1/admin/**` est la règle générale et
vient en dernier : les quatre lignes au-dessus sont des exceptions, et la
passerelle les évalue avant elle. `/api/v1/admin/users/**` n'était pas déclarée
du bon côté de la passerelle, et toute l'administration des comptes — blocage,
bannissement, suspension, révocation, listes — partait chez interface-service,
qui ne la porte pas. La réponse était un `404`, impossible à distinguer d'un
chemin qui n'existe pas.

Concrètement : la connexion d'un utilisateur final est
`POST /api/v1/auth-sdk/login`, pas `/api/v1/auth/login`.

### Deux surfaces pour les comptes, et une seule par rôle

`/api/v1/users/**` ne porte que **le compte de l'appelant** : `/users/me` et sa
photo de profil. Il n'y a aucun accès par identifiant, et c'est voulu — un
client n'a pas de raison légitime de lire le compte d'un autre.

Toute l'administration des comptes est sous `/api/v1/admin/users/**` et exige le
rôle `ADMIN`. Lister, chercher, consulter, modifier et supprimer un compte
existaient auparavant **deux fois**, sous `/users` et sous
`/admin/management/users`, avec deux comportements différents : l'une des deux
versions listait les comptes révoqués, l'autre non ; l'une faisait un effacement
logique, l'autre supprimait la ligne. Ces deux surfaces n'existent plus.

Les réversions `unblock`, `unban` et `unsuspend` ont été remplacées par un seul
`POST /api/v1/admin/users/{id}/restore`, qui ramène le compte à `ACTIVE` depuis
n'importe lequel des quatre autres états.

---

## 2. Authentification

### Les deux jetons

| Jeton | Durée | Transport |
|---|---|---|
| `accessToken` | 1 h | en-tête `Authorization: Bearer <jeton>` |
| `refreshToken` | 7 j | corps de `POST /api/v1/auth-sdk/refresh` |

`POST /api/v1/auth-sdk/login` rend les deux. Sur `401`, appeler `/refresh` avec
le `refreshToken`, puis rejouer la requête. Si `/refresh` échoue aussi, la
session est terminée : retour à l'écran de connexion.

### Ce que « déconnecter » fait, et ne fait pas

La révocation coupe le **renouvellement**. Un `accessToken` déjà émis reste
valable jusqu'à son échéance — c'est la nature d'un JWT, et le raccourcir
demanderait une lecture en base à chaque requête. Un frontend qui déconnecte
doit donc **jeter le jeton localement**, sans attendre que le serveur l'invalide.

### Le second facteur sur le chemin de connexion

`POST /api/v1/auth-sdk/login` peut échouer en demandant un second facteur
(`AUTH_1009`). Rejouer alors le même appel avec `totpCode` renseigné.

Ce champ accepte **un code TOTP ou un code de secours** : inutile de deviner
lequel, le serveur essaie les deux. Un même écran de saisie suffit.

Le champ `deviceToken` dispense du second facteur quand l'appareil a été déclaré
de confiance (voir §4). Il ne dispense **que** de cela : le mot de passe reste
exigé.

---

## 3. Erreurs

Toutes les erreurs ont la même forme, et un **code stable** à tester plutôt que
le message, qui peut être reformulé.

```json
{ "errorCode": "AUTH_1001", "message": "Identifiants invalides" }
```

| Code | Signification pour l'écran |
|---|---|
| `AUTH_1001` | identifiants refusés — ne pas préciser lequel des deux |
| `AUTH_1009` | second facteur requis : afficher la saisie du code |
| `AUTH_1004` | compte verrouillé : afficher la durée restante |
| `REG_2001` | ce numéro est déjà pris |
| `VAL_6007` | dernier moyen de connexion : refuser la déliaison, proposer d'en ajouter un |
| `VAL_6008` | adresse non vérifiée : proposer la confirmation avant le réglage |
| `VAL_6009` | ce compte n'a pas de mot de passe (créé par OAuth2) |
| `2FA_9001` | second facteur inactif : proposer de l'activer |

### Deux comportements voulus, qui ressemblent à des bugs

**`POST /forgot-password` répond toujours `200`**, que le compte existe ou non.
Le message est au conditionnel. C'est délibéré : répondre `404` sur un compte
inconnu permettait d'énumérer les numéros inscrits, un appel à la fois. L'écran
ne doit donc **pas** en déduire que le compte existe.

**Un refus d'accès à une ressource d'autrui rend `404`, pas `403`.** Dire
« interdit » confirmerait que la ressource existe. Un `404` sur une ressource
qu'on croit sienne signifie « pas à vous », pas « disparue ».

---

## 4. Les contrats qui ne se devinent pas

### `DELETE` avec un corps

`DELETE /api/v1/auth-sdk/totp` et `DELETE /api/v1/auth-sdk/trusted-devices/{id}`
prennent un corps JSON. Inhabituel, mais préférable aux deux solutions de
rechange : un code en paramètre de requête atterrirait dans les journaux
d'accès, et un `POST /totp/disable` décrirait mal une suppression. Certains
clients HTTP omettent le corps d'un `DELETE` par défaut — à vérifier.

### L'en-tête `X-Refresh-Token`

`GET /api/v1/auth-sdk/sessions` accepte cet en-tête **optionnel** pour marquer
la session courante (`courante: true` dans la réponse). En en-tête et non en
paramètre : dans une chaîne de requête, un justificatif finirait dans
l'historique du navigateur, l'en-tête `Referer` et les journaux.

### Les valeurs rendues une seule fois

| Endpoint | Valeur | Conséquence |
|---|---|---|
| `POST /auth-sdk/totp/enroll` | `secret`, `qrCodeUrl` | non remis ensuite |
| `POST /auth-sdk/backup-codes` | `codes[]` | hachés en base, irrécupérables |
| `POST /auth-sdk/trusted-devices` | `jeton` | haché en base |

L'écran doit les afficher **avant** toute navigation, et le dire à la personne.
Un rechargement les perd définitivement.

### L'enrôlement du second facteur a deux étapes

`POST /totp/enroll` pose le secret et **n'active rien**. `POST /totp/confirm`
active, après preuve qu'un code valide est produisible. Un écran qui s'arrête
après l'étape 1 laisse la personne convaincue d'être protégée alors que sa
connexion continue de passer sans code.

---

## 5. Les réglages de sécurité du compte

Tout est sous `/api/v1/auth-sdk/`, et l'identifiant du compte n'est **jamais**
dans l'URL ni dans le corps : il vient du jeton. Il n'y a donc pas de variante
« pour un autre utilisateur ».

| Réglage | Endpoints |
|---|---|
| Mot de passe | `POST /password/change` |
| Second facteur | `POST /totp/enroll`, `POST /totp/confirm`, `DELETE /totp` |
| Codes de secours | `POST /backup-codes`, `GET /backup-codes` |
| Appareils de confiance | `POST`/`GET`/`DELETE /trusted-devices`, `DELETE /trusted-devices/{id}` |
| Sessions ouvertes | `GET /sessions`, `DELETE /sessions`, `DELETE /sessions/{id}` |
| Confirmer ses coordonnées | `POST /email/confirm-request`, `/email/confirm-verify`, `/phone/confirm-request`, `/phone/confirm-verify` |
| Changer de numéro | `POST /phone/change-request`, `POST /phone/change-verify` |
| Alerte de connexion | `GET /alerts/new-login`, `PUT /alerts/new-login` |
| Comptes sociaux | `GET /oauth2/accounts`, `DELETE /oauth2/accounts/{provider}` |

### Changer de numéro : deux étapes, et le code part au NOUVEAU numéro

`POST /phone/change-request` prend `{nouveauNumero, indicatif,
motDePasseActuel}` et envoie le code **au nouveau numéro** — lui seul prouve
qu'on le détient. Le compte garde l'ancien jusqu'à `POST /phone/change-verify`.
L'écran doit le dire : tant que la confirmation n'a pas eu lieu, rien n'a changé.

Le mot de passe actuel est exigé parce que le numéro est le canal de
`/forgot-password` : qui le remplace prend le contrôle de la réinitialisation.
Un compte créé par OAuth2 n'a pas de mot de passe et reçoit `VAL_6009`.

### Les alertes que la personne recevra

Changement de mot de passe et changement d'adresse ou de numéro envoient un avis
**sans réglage possible**, à l'ancienne coordonnée. Seule l'alerte de nouvelle
connexion est optionnelle, et son activation exige une adresse vérifiée
(`VAL_6008`) : la réponse porte `adresseVerifiee` pour que l'écran puisse
proposer la confirmation au lieu d'un échec sec.

---

## 6. État de cette surface

Les endpoints listés ci-dessous sont **câblés et couverts par des tests** :
1908 tests sur 11 modules, dont 1095 pour auth-service, et les décisions de
sécurité sont vérifiées par mutation. Ce qui reste connu et non résolu :

- **9 endpoints rendent `(non routé par la passerelle)`**, tous dans
  interface-service : les six de `/maintenance/**` et trois de `/dev/**`. Un
  seul cas reste donc ouvert, et c'est une décision, pas un oubli :
  `/maintenance/**` est une surface de dernier recours, et la router la rendrait
  atteignable depuis l'extérieur au moment précis où le reste est verrouillé.
  Les deux autres cas de cette liste ont été tranchés : `/dev/erasure/me` est
  routé — un droit à l'effacement que son titulaire ne pouvait pas exercer
  n'était pas un droit — et `/dev/.well-known/jwks.json` est joignable par
  `/.well-known/jwks.json`, qui pointe bien vers interface-service, détenteur
  des clés. La version précédente de ce paragraphe affirmait l'inverse pour ce
  dernier point, et annonçait 12 endpoints dont trois de user-service
  (`/files/**`) qui sont routés depuis.
- Les jetons OAuth2 **ne voyagent plus** dans la chaîne de requête de la
  redirection de rappel. Le rappel rend un code de remise à usage unique,
  échangé contre les jetons par un appel distinct (`CodeDeRemiseOAuth2Redis`).
  Un jeton en query string finissait dans les journaux d'accès et l'historique
  du navigateur.
- `geo-service` et `payment-service` sont des squelettes sans logique : les
  endpoints de paiement n'existent pas. **notification-service n'en est plus
  un** — cinq canaux de remise, moteur de modèles à deux couches, boîte de
  réception, enregistrement d'appareil pour le push, flux WebSocket,
  administration des modèles et des envois. Ses 17 endpoints figurent
  ci-dessous ; ils n'apparaissaient pas jusqu'ici parce que le générateur
  omettait le module de sa liste.

## Référence des endpoints

Générée depuis les contrôleurs par `scripts/postman/generer_reference_frontend.py`. Ne pas modifier à la main : la prochaine génération écrase.

### auth-service (port 8082)

**AdminAccountController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/auth-admin/accounts/{identifier}/lockout-status` | admin | — |
| `POST` | `/api/v1/auth-admin/accounts/{identifier}/unlock` | admin | — |
| `POST` | `/api/v1/auth-admin/accounts/{identifier}/reset-attempts` | admin | — |
| `GET` | `/api/v1/auth-admin/accounts/{identifier}/remaining-attempts` | admin | — |
| `POST` | `/api/v1/auth-admin/accounts/{identifier}/lock` | admin | — |
| `POST` | `/api/v1/auth-admin/accounts/{identifier}/totp/reset` | admin | — |

**AdminAuthController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth-sdk/admin/login` | public | oui |
| `POST` | `/api/v1/auth-sdk/admin/totp/enroll` | public | oui |
| `POST` | `/api/v1/auth-sdk/admin/totp/reset` | public | oui |

**AuditController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/auth-admin/audit/logs` | admin | — |
| `GET` | `/api/v1/auth-admin/audit/logs/user/{userId}` | admin | — |
| `POST` | `/api/v1/auth-admin/audit/logs/search` | admin | oui |
| `GET` | `/api/v1/auth-admin/audit/failed-attempts` | admin | — |
| `GET` | `/api/v1/auth-admin/audit/security-stats` | admin | — |
| `DELETE` | `/api/v1/auth-admin/audit/logs/cleanup` | admin | — |

**AuthController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth-sdk/login` | public | oui |
| `POST` | `/api/v1/auth-sdk/logout` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/refresh` | public | oui |
| `POST` | `/api/v1/auth-sdk/forgot-password` | public | oui |
| `POST` | `/api/v1/auth-sdk/reset-password` | public | oui |
| `POST` | `/api/v1/auth-sdk/resend-code` | public | oui |
| `GET` | `/api/v1/auth-sdk/profile` | authentifie | oui |
| `PUT` | `/api/v1/auth-sdk/profile` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/phone/auth-request` | public | oui |
| `POST` | `/api/v1/auth-sdk/phone/auth-verify` | public | oui |
| `POST` | `/api/v1/auth-sdk/phone/register-request` | public | oui |
| `POST` | `/api/v1/auth-sdk/phone/register-verify` | public | oui |
| `POST` | `/api/v1/auth-sdk/email/auth-request` | public | oui |
| `POST` | `/api/v1/auth-sdk/email/auth-verify` | public | oui |
| `POST` | `/api/v1/auth-sdk/email/register` | public | oui |
| `POST` | `/api/v1/auth-sdk/email/register-verify` | public | oui |
| `GET` | `/api/v1/auth-sdk/oauth2/authorize/{provider}` | public | — |
| `GET` | `/api/v1/auth-sdk/oauth2/callback/{provider}` | public | — |
| `POST` | `/api/v1/auth-sdk/oauth2/callback/{provider}` | public | — |
| `POST` | `/api/v1/auth-sdk/oauth2/callback` | public | oui |
| `POST` | `/api/v1/auth-sdk/oauth2/exchange` | public | oui |
| `POST` | `/api/v1/auth-sdk/oauth2/complete-profile` | authentifie | oui |

**AuthenticationLogController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/auth-admin/logs` | admin | — |
| `GET` | `/api/v1/auth-admin/logs/user/{userId}` | admin | — |
| `POST` | `/api/v1/auth-admin/logs/search/by-phone` | admin | oui |
| `GET` | `/api/v1/auth-admin/logs/event/{eventType}/status/{status}` | admin | — |
| `DELETE` | `/api/v1/auth-admin/logs/cleanup` | admin | — |
| `POST` | `/api/v1/auth-admin/logs/filter` | admin | oui |
| `GET` | `/api/v1/auth-admin/logs/by-status/{status}` | admin | — |
| `GET` | `/api/v1/auth-admin/logs/by-event/{eventType}` | admin | — |
| `POST` | `/api/v1/auth-admin/logs/search/by-ip` | admin | oui |
| `GET` | `/api/v1/auth-admin/logs/by-date-range` | admin | — |
| `GET` | `/api/v1/auth-admin/logs/stats` | admin | — |

**BiometricAuthController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth-sdk/biometric/register/initiate` | authentifie | — |
| `POST` | `/api/v1/auth-sdk/biometric/register/complete` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/biometric/authenticate/initiate` | public | oui |
| `POST` | `/api/v1/auth-sdk/biometric/authenticate/verify` | public | oui |
| `GET` | `/api/v1/auth-sdk/biometric/credentials` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/biometric/credentials/{credentialId}` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/biometric/credentials` | authentifie | — |
| `GET` | `/api/v1/auth-sdk/biometric/has-credentials` | authentifie | — |

**CompteUtilisateurController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth-sdk/password/change` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/totp/enroll` | authentifie | — |
| `POST` | `/api/v1/auth-sdk/totp/confirm` | authentifie | oui |
| `DELETE` | `/api/v1/auth-sdk/totp` | authentifie | oui |
| `GET` | `/api/v1/auth-sdk/sessions` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/sessions/{sessionId}` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/sessions` | authentifie | — |
| `POST` | `/api/v1/auth-sdk/email/confirm-request` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/email/confirm-verify` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/phone/confirm-request` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/phone/confirm-verify` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/trusted-devices` | authentifie | oui |
| `GET` | `/api/v1/auth-sdk/trusted-devices` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/trusted-devices/{appareilId}` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/trusted-devices` | authentifie | — |
| `POST` | `/api/v1/auth-sdk/backup-codes` | authentifie | oui |
| `GET` | `/api/v1/auth-sdk/backup-codes` | authentifie | — |
| `POST` | `/api/v1/auth-sdk/phone/change-request` | authentifie | oui |
| `POST` | `/api/v1/auth-sdk/phone/change-verify` | authentifie | oui |
| `GET` | `/api/v1/auth-sdk/alerts/new-login` | authentifie | — |
| `PUT` | `/api/v1/auth-sdk/alerts/new-login` | authentifie | oui |
| `GET` | `/api/v1/auth-sdk/oauth2/accounts` | authentifie | — |
| `DELETE` | `/api/v1/auth-sdk/oauth2/accounts/{provider}` | authentifie | — |
| `GET` | `/api/v1/auth-sdk/oauth2/providers` | public | — |

**ErasureController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth-sdk/erasure/me` | authentifie | — |
| `POST` | `/api/v1/auth-sdk/erasure/{subjectId}` | authentifie | — |
| `GET` | `/api/v1/auth-sdk/erasure/{requestId}/status` | authentifie | — |

**UserRoleManagementController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth-users` | authentifie | — |
| `GET` | `/api/v1/auth-users/{userId}` | authentifie | — |
| `POST` | `/api/v1/auth-users/{userId}/roles/{role}` | authentifie | — |
| `DELETE` | `/api/v1/auth-users/{userId}/roles/{role}` | authentifie | — |
| `PUT` | `/api/v1/auth-users/{userId}/primary-role/{role}` | authentifie | — |
| `GET` | `/api/v1/auth-users/by-role/{role}` | authentifie | — |

### interface-service (port 8088)

**AdminController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/admin/login` | public | oui |
| `GET` | `/api/v1/admin/info` | admin | — |
| `POST` | `/api/v1/admin/totp/enroll` | admin | oui |
| `GET` | `/api/v1/admin/confirm` | admin | — |

**AuthController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | public | oui |
| `POST` | `/api/v1/auth/login` | public | oui |
| `POST` | `/api/v1/auth/verify-email` | public | oui |
| `POST` | `/api/v1/auth/verify-phone` | public | oui |
| `POST` | `/api/v1/auth/request-email-otp` | public | oui |
| `POST` | `/api/v1/auth/request-phone-otp` | public | oui |
| `POST` | `/api/v1/auth/request-password-reset` | public | oui |
| `POST` | `/api/v1/auth/reset-password` | public | oui |
| `POST` | `/api/v1/auth/reset-password-otp` | public | oui |
| `POST` | `/api/v1/auth/refresh-token` | public | oui |
| `POST` | `/api/v1/auth/logout` | public | oui |

**DevKeyManagementController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/.well-known/jwks.json` | public | — |
| `POST` | `(non routé par la passerelle)` | non routé | — |
| `GET` | `(non routé par la passerelle)` | non routé | — |
| `DELETE` | `(non routé par la passerelle)` | non routé | — |

**DeveloperErasureController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/dev/erasure/me` | authentifie | — |

**MaintenanceController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `(non routé par la passerelle)` | non routé | oui |
| `POST` | `(non routé par la passerelle)` | non routé | oui |
| `POST` | `(non routé par la passerelle)` | non routé | oui |
| `POST` | `(non routé par la passerelle)` | non routé | oui |
| `GET` | `(non routé par la passerelle)` | non routé | — |
| `POST` | `(non routé par la passerelle)` | non routé | — |

**ProjectController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/projects` | authentifie | oui |
| `GET` | `/api/v1/projects` | authentifie | — |
| `GET` | `/api/v1/projects/{projectId}` | authentifie | — |
| `PUT` | `/api/v1/projects/{projectId}` | authentifie | oui |
| `DELETE` | `/api/v1/projects/{projectId}` | authentifie | — |
| `GET` | `/api/v1/projects/can-create` | authentifie | — |

### user-service (port 8083)

**FileReactiveController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/files/{userId}/{filename}` | authentifie | — |

**UserAdminReactiveController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/admin/users` | admin | — |
| `GET` | `/api/v1/admin/users/search` | admin | — |
| `GET` | `/api/v1/admin/users/status/{status}` | admin | — |
| `GET` | `/api/v1/admin/users/audit-logs` | admin | — |
| `GET` | `/api/v1/admin/users/{userId}/audit-logs` | admin | — |
| `GET` | `/api/v1/admin/users/{userId}` | admin | — |
| `PUT` | `/api/v1/admin/users/{userId}` | admin | — |
| `DELETE` | `/api/v1/admin/users/{userId}` | admin | — |
| `POST` | `/api/v1/admin/users/{userId}/block` | admin | oui |
| `POST` | `/api/v1/admin/users/{userId}/ban` | admin | oui |
| `POST` | `/api/v1/admin/users/{userId}/suspend` | admin | oui |
| `POST` | `/api/v1/admin/users/{userId}/revoke` | admin | — |
| `POST` | `/api/v1/admin/users/{userId}/restore` | admin | — |

**UserReactiveController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/users/me` | authentifie | — |
| `PUT` | `/api/v1/users/me` | authentifie | — |
| `GET` | `/api/v1/users/me/profile-picture` | authentifie | — |
| `DELETE` | `/api/v1/users/me/profile-picture` | authentifie | — |
| `POST` | `/api/v1/users/me/profile-picture` | authentifie | — |

### media-service (port 8087)

**AdminDocumentController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/admin/documents/{documentId}/approve` | admin | — |
| `POST` | `/api/v1/admin/documents/{documentId}/reject` | admin | — |
| `GET` | `/api/v1/admin/documents/pending` | admin | — |
| `GET` | `/api/v1/admin/documents/all` | admin | — |
| `GET` | `/api/v1/admin/documents/stats` | admin | — |
| `GET` | `/api/v1/admin/documents/user/{userId}` | admin | — |
| `GET` | `/api/v1/admin/documents/{documentId}/details` | admin | — |
| `GET` | `/api/v1/admin/documents/{documentId}/download` | admin | — |
| `DELETE` | `/api/v1/admin/documents/{documentId}` | admin | — |

**AdminDocumentTypeController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/admin/document-types` | admin | oui |
| `PUT` | `/api/v1/admin/document-types/{id}` | admin | oui |
| `DELETE` | `/api/v1/admin/document-types/{id}` | admin | — |
| `GET` | `/api/v1/admin/document-types/{id}` | admin | — |
| `GET` | `/api/v1/admin/document-types/code/{code}` | admin | — |
| `GET` | `/api/v1/admin/document-types` | admin | — |
| `POST` | `/api/v1/admin/document-types/{documentTypeId}/assign-role` | admin | — |
| `PUT` | `/api/v1/admin/document-types/{code}/roles/{role}` | admin | — |
| `DELETE` | `/api/v1/admin/document-types/{documentTypeId}/unassign-role` | admin | — |
| `GET` | `/api/v1/admin/document-types/roles/{role}` | admin | — |
| `GET` | `/api/v1/admin/document-types/{code}/roles` | admin | — |

**DocumentController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/media/upload` | authentifie | — |
| `GET` | `/api/v1/media/download/{documentId}` | authentifie | — |
| `GET` | `/api/v1/media/{documentId}` | authentifie | — |
| `GET` | `/api/v1/media/user/documents` | authentifie | — |
| `GET` | `/api/v1/media/user/status` | authentifie | — |
| `GET` | `/api/v1/media/requirements` | authentifie | — |
| `DELETE` | `/api/v1/media/{documentId}` | authentifie | — |

### quotas-service (port 8089)

**AdminQuotaController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `PUT` | `/api/v1/admin/quotas/{userId}/tier` | admin | oui |
| `POST` | `/api/v1/admin/quotas/{userId}/reset` | admin | — |

**QuotaController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/quotas/{userId}` | authentifie | — |
| `GET` | `/api/v1/quotas/{userId}/check` | authentifie | — |
| `POST` | `/api/v1/quotas/{userId}/consume` | authentifie | oui |
| `POST` | `/api/v1/quotas/{userId}/release` | authentifie | oui |

### setting-service (port 8090)

**AdminSettingController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `PUT` | `/api/v1/admin/settings/system/{key}` | admin | oui |
| `DELETE` | `/api/v1/admin/settings/system/{key}` | admin | — |
| `DELETE` | `/api/v1/admin/settings/users/{userId}` | admin | — |

**SettingController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/settings/system` | authentifie | — |
| `GET` | `/api/v1/settings/system/{key}` | authentifie | — |
| `GET` | `/api/v1/settings/users/{userId}` | authentifie | — |
| `GET` | `/api/v1/settings/users/{userId}/{key}` | authentifie | — |
| `PUT` | `/api/v1/settings/users/{userId}/{key}` | authentifie | oui |
| `DELETE` | `/api/v1/settings/users/{userId}/{key}` | authentifie | — |

### security-service (port 8091)

**MaintenanceController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/security-admin/maintenance` | admin | — |
| `POST` | `/api/v1/security-admin/maintenance` | admin | — |
| `DELETE` | `/api/v1/security-admin/maintenance/{id}` | admin | — |

**PaysBloqueController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/security-admin/pays` | admin | — |
| `POST` | `/api/v1/security-admin/pays` | admin | — |
| `DELETE` | `/api/v1/security-admin/pays/{codeIso}` | admin | — |

**PolitiqueDeSecuriteController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/security-admin/politique` | admin | — |
| `GET` | `/api/v1/security-admin/politique/{cle}` | admin | — |
| `PUT` | `/api/v1/security-admin/politique/{cle}` | admin | — |
| `DELETE` | `/api/v1/security-admin/politique/{cle}` | admin | — |

**RegleDIpController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/security-admin/ip` | admin | — |
| `POST` | `/api/v1/security-admin/ip` | admin | — |
| `DELETE` | `/api/v1/security-admin/ip/{id}` | admin | — |

### notification-service (port 8086)

**AccuseDeReceptionSmsController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/sms/accuses/afriksms` | public | — |
| `POST` | `/api/v1/sms/accuses/afriksms` | public | — |

**AdminEnvoiController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/admin/notifications` | admin | oui |
| `POST` | `/api/v1/admin/notifications/groupe` | admin | oui |

**AdminModeleController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/admin/modeles` | admin | — |
| `PUT` | `/api/v1/admin/modeles` | admin | oui |
| `DELETE` | `/api/v1/admin/modeles` | admin | — |

**AdminSmsController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/admin/sms/solde` | admin | — |
| `POST` | `/api/v1/admin/sms/url-de-rappel` | admin | — |

**AppareilController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `POST` | `/api/v1/notifications/appareils` | authentifie | oui |
| `GET` | `/api/v1/notifications/appareils` | authentifie | — |
| `DELETE` | `/api/v1/notifications/appareils` | authentifie | — |
| `DELETE` | `/api/v1/notifications/appareils/tous` | authentifie | — |

**NotificationController**

| Méthode | Chemin appelé par le frontend | Accès | Corps |
|---|---|---|---|
| `GET` | `/api/v1/notifications` | authentifie | — |
| `GET` | `/api/v1/notifications/compteur` | authentifie | — |
| `POST` | `/api/v1/notifications/{id}/lue` | authentifie | — |

---

**211 endpoints appelables** depuis le frontend. 8 endpoints `/internal/**` sont exclus : aucune passerelle ne les route — c'est précisément ce qui les rend injoignables depuis l'extérieur. Un service les appelle en direct sur le port de son pair, avec l'en-tête `X-Internal-Secret`. La phrase précédente affirmait l'inverse — « derrière la signature HMAC de la passerelle, un appel direct est refusé » — et c'était faux dans les deux moitiés.

