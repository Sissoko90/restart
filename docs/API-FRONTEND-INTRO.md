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

- **12 endpoints rendent `(non routé par la passerelle)`** dans la référence,
  tous dans interface-service (`/dev/**`, `/maintenance/**`) et user-service
  (`/files/**`). Trois cas distincts, et ils ne se tranchent pas de la même
  façon : `/maintenance/**` est une surface de dernier recours qui n'a
  peut-être pas à être publique ; `/dev/erasure/me` est un **droit à
  l'effacement** que son titulaire ne peut donc pas exercer ; la route
  `/.well-known/jwks.json` de la passerelle pointe vers auth-service alors
  qu'interface-service détient les clés. À décider avant intégration de ces
  chemins.
- Les jetons OAuth2 voyagent dans la chaîne de requête de la redirection de
  rappel. Fonctionnel, mais un jeton en query string finit dans les journaux
  d'accès et l'historique.
- `geo-service`, `payment-service` et `notification-service` sont des squelettes
  sans logique. Les endpoints de paiement n'existent pas.
