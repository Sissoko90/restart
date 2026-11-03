# Collections Postman

Une collection par service, **générée depuis le code source** — 165 endpoints, un
dossier par contrôleur.

```
postman/
├── Restart-Local.postman_environment.json   ← à importer une fois
├── interface-service/    34 endpoints
├── auth-service/         62 endpoints
├── user-service/         27 endpoints
├── media-service/        27 endpoints
├── quotas-service/        6 endpoints
└── setting-service/       9 endpoints
```

---

## 1. Import

1. Importer `Restart-Local.postman_environment.json` (Environments).
2. Importer les collections souhaitées (Collections → Import).
3. Sélectionner l'environnement **Restart — Local (via Gateway)** en haut à droite.

---

## 2. Toujours passer par le Gateway

`baseUrl` vaut `http://localhost:8081/api/v1` par défaut, et ce n'est pas un détail
de confort : **les services refusent les appels directs**. Chacun exige la signature
HMAC (`X-Gateway-Signature`, `X-Gateway-Timestamp`, `X-Gateway-Request-Id`) que seul
le Gateway sait produire — c'est ce qui empêche de contourner le rate limiting, le
géo-blocage et la validation JWT.

Appeler `http://localhost:8082/auth/login` directement renvoie donc **403**. C'est
le comportement attendu, pas une panne.

---

## 3. Le jeton se remplit tout seul

Les requêtes de connexion (`/auth/login`, `*-verify`, `/admin/login`,
`/maintenance/auth`) portent un script de test qui capture le jeton et le range dans
les variables de collection :

```javascript
pm.collectionVariables.set("accessToken", json.accessToken);
```

Toutes les requêtes protégées envoient ensuite `Authorization: Bearer {{accessToken}}`.
**Il suffit donc de jouer une connexion en premier**, puis n'importe quelle autre
requête de la collection.

---

## 4. Ordre de test conseillé, service par service

### interface-service — le point d'entrée
Rien ne fonctionne ailleurs sans un jeton signé par lui.

1. `POST /auth/register` → crée le compte développeur
2. `POST /auth/request-email-otp` → code par email
3. `POST /auth/verify-email` → active le compte
4. `POST /auth/login` → **capture `accessToken`**
5. `GET /dev/.well-known/jwks.json` → vérifie que les clés publiques sont exposées
6. `POST /projects` → crée un projet (appelle quotas-service au passage)

### auth-service — le SDK utilisateurs finaux
Nécessite un jeton **de type `developer`** obtenu à l'étape précédente.

1. `POST /auth/phone/register-request` → OTP par SMS
2. `POST /auth/phone/register-verify` → crée l'utilisateur final
3. `POST /auth/login` → connexion par mot de passe
4. `GET /auth/profile` puis `PUT /auth/profile`
5. `POST /auth/refresh`, puis `POST /auth/logout`

### quotas-service
1. `GET /quotas/{userId}` → palier FREE créé automatiquement
2. `GET /quotas/{userId}/check?resource=PROJECTS&amount=1`
3. `PUT /admin/quotas/{userId}/tier` (ADMIN) → passer en PREMIUM
4. Rejouer le `check` : la limite doit passer de 10 à 100

### setting-service
1. `GET /settings/system` → 14 paramètres, tous `overridden: false`
2. `PUT /admin/settings/system/system.maintenance-mode` avec `{"value":"true"}`
3. Rejouer le `GET` : `overridden: true`
4. `DELETE /admin/settings/system/system.maintenance-mode` → retour au défaut

### media-service
1. `POST /documents/upload` (form-data, joindre un vrai fichier)
2. `GET /documents/{documentId}` puis `GET /documents` (liste)
3. `GET /documents/{documentId}/download`

### user-service
Le profil est créé **automatiquement** par l'événement Kafka `user-events` émis à
l'inscription dans auth-service. Vérifier avec `GET /users/{userId}` — c'est le test
le plus parlant de la chaîne asynchrone.

---

## 5. Ce que vérifie chaque requête

Deux assertions sont attachées à toutes les requêtes :

```javascript
pm.test("Statut non 5xx", ...);   // aucune erreur serveur
pm.test("Réponse sous 2s", ...);  // pas de blocage
```

Volontairement laxistes : un **401** ou un **403** est une réponse valide (souvent
la bonne). Ce qu'on traque, c'est le 500 et la latence anormale.

Pour un jeu de non-régression complet, exécuter en ligne de commande :

```bash
npm install -g newman
newman run postman/auth-service/auth-service.postman_collection.json \
       -e postman/Restart-Local.postman_environment.json
```

---

## 6. Variables à renseigner

L'environnement laisse volontairement vides les valeurs sensibles — elles ne doivent
pas être versionnées :

| Variable | Où la trouver |
|---|---|
| `adminPhone`, `adminPassword` | `ADMIN_DEFAULT_*` du `.env` |
| `adminSecret` | `ADMIN_SECRET_KEY` du `.env` |
| `maintenanceSecret` | `MAINTENANCE_SECRET_KEY` du `.env` |

`userId`, `projectId` et `documentId` se remplissent au fil des réponses.

---

## 7. Régénérer les collections

Les collections sont dérivées du code : après un ajout ou un renommage d'endpoint,
elles doivent être régénérées plutôt que modifiées à la main, sinon elles divergent
silencieusement du code.

Les corps de requête réalistes sont maintenus dans le dictionnaire `BODIES` du
script de génération ; les endpoints non renseignés reçoivent un corps
`{"TODO": "compléter le corps"}` — c'est le signal qu'il reste à les documenter.
