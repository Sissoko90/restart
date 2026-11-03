# 📡 API Endpoints - Auth Service

> **Base URL Gateway:** `http://localhost:8081/auth`  
> **Base URL Direct:** `http://localhost:8082/auth`  
> **⚠️ Utiliser le Gateway pour tous les tests**

---

## 📋 Table des Matières

1. [Flux d'Inscription](#flux-dinscription)
2. [Flux de Connexion](#flux-de-connexion)
3. [OAuth2 (Google/Facebook)](#oauth2-providers)
4. [Authentification Biométrique](#authentification-biométrique)
5. [Libre-service du compte](#libre-service-du-compte)
6. [Gestion de Profil](#gestion-de-profil)
7. [Logs d'Authentification (Admin)](#logs-dauthentification-admin)

---

## 🔐 Flux d'Inscription

### 1️⃣ Inscription par Téléphone (OTP)

**Processus complet en 2 étapes :**

#### **ÉTAPE 1 : Demander un code OTP**

```bash
POST /auth/phone/register-request
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "countryCode": "+223"
}
```

**Response 200:**
```json
{
  "message": "Code de vérification envoyé",
  "phoneNumber": "+223****8697",
  "expiresIn": 600
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/phone/register-request \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "countryCode": "+223"
  }'
```

---

#### **ÉTAPE 2 : Vérifier le code et créer le compte**

```bash
POST /auth/phone/register-verify
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "otp": "123456",
  "password": "SecurePass123!",
  "desiredRole": "DRIVER"
}
```

**Champs:**
- `phoneNumber` (requis) : Numéro de téléphone
- `otp` (requis) : Code de vérification reçu par SMS
- `password` (requis) : Mot de passe (min 8 caractères, 1 majuscule, 1 minuscule, 1 chiffre, 1 caractère spécial)
- `desiredRole` (optionnel) : Rôle souhaité
  - Si non fourni → `USER` par défaut
  - Valeurs possibles : `USER`, `DRIVER`, `MERCHANT`, `PHARMACY`, `RESTAURANT`, `SUPERMARKET`, `DISPATCHER`
  - ⚠️ `ADMIN` ne peut pas être auto-attribué (sera forcé à `USER`)

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "user-123",
    "phoneNumber": "+22300000000",
    "email": "john@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "role": "USER",
    "isPhoneVerified": true,
    "isEmailVerified": false,
    "isActive": true
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/phone/register-verify \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "otp": "123456",
    "password": "SecurePass123!",
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com"
  }'
```

---

### 2️⃣ Inscription par Email (OTP)

**Processus complet en 2 étapes :**

#### **ÉTAPE 1 : Demander un code OTP par email**

```bash
POST /auth/email/register
```

**Request Body:**
```json
{
  "email": "user@example.com"
}
```

**Response 200:**
```json
{
  "message": "Code de vérification envoyé à votre email",
  "email": "u***@example.com",
  "expiresIn": 600
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/email/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com"
  }'
```

---

#### **ÉTAPE 2 : Vérifier le code et compléter l'inscription**

```bash
POST /auth/email/register-verify
```

**Request Body:**
```json
{
  "email": "user@example.com",
  "otp": "123456",
  "firstName": "Jane",
  "lastName": "Smith",
  "phoneNumber": "+33612345678",
  "countryCode": "+33",
  "password": "SecurePass123!",
  "desiredRole": "MERCHANT"
}
```

**Champs:**
- `email` (requis) : Adresse email
- `otp` (requis) : Code de vérification reçu par email
- `firstName` (requis) : Prénom
- `lastName` (requis) : Nom
- `phoneNumber` (requis) : Numéro de téléphone
- `countryCode` (requis) : Code pays (ex: +33)
- `password` (requis) : Mot de passe (min 8 caractères, 1 majuscule, 1 minuscule, 1 chiffre, 1 caractère spécial)
- `desiredRole` (optionnel) : Rôle souhaité
  - Si non fourni → `USER` par défaut
  - Valeurs possibles : `USER`, `DRIVER`, `MERCHANT`, `PHARMACY`, `RESTAURANT`, `SUPERMARKET`, `DISPATCHER`
  - ⚠️ `ADMIN` ne peut pas être auto-attribué (sera forcé à `USER`)

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "user-456",
    "phoneNumber": "+33612345678",
    "email": "user@example.com",
    "firstName": "Jane",
    "lastName": "Smith",
    "role": "USER",
    "isPhoneVerified": false,
    "isEmailVerified": true,
    "isActive": true
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/email/register-verify \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "otp": "123456",
    "firstName": "Jane",
    "lastName": "Smith",
    "phoneNumber": "+33612345678",
    "countryCode": "+33",
    "password": "SecurePass123!",
    "desiredRole": "MERCHANT"
  }'
```

---

## 🔑 Flux de Connexion

### 1️⃣ Connexion Classique (Phone/Email + Password)

```bash
POST /auth/login
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "password": "SecurePass123!"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "user-123",
    "phoneNumber": "+22300000000",
    "email": "john@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "role": "USER"
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "password": "SecurePass123!"
  }'
```

---

### 2️⃣ Connexion par Phone OTP (Avec mot de passe)

**Processus en 2 étapes :**

#### **ÉTAPE 1 : Demander un code OTP**

```bash
POST /auth/phone/auth-request
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "countryCode": "+223"
}
```

**Response 200:**
```json
{
  "message": "Code d'authentification envoyé",
  "phoneNumber": "+223****8697",
  "expiresIn": 300
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/phone/auth-request \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "countryCode": "+223"
  }'
```

---

#### **ÉTAPE 2 : Vérifier le code et se connecter**

```bash
POST /auth/phone/auth-verify
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "otp": "123456",
  "password": "SecurePass123!"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "user-123",
    "phoneNumber": "+22300000000",
    "email": "john@example.com",
    "firstName": "John",
    "lastName": "Doe"
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/phone/auth-verify \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "otp": "123456",
    "password": "SecurePass123!"
  }'
```

---

### 3️⃣ Connexion par Email OTP (Avec mot de passe)

**Processus en 2 étapes :**

#### **ÉTAPE 1 : Demander un code OTP par email**

```bash
POST /auth/email/auth-request
```

**Request Body:**
```json
{
  "email": "user@example.com"
}
```

**Response 200:**
```json
{
  "message": "Code d'authentification envoyé à votre email",
  "email": "u***@example.com",
  "expiresIn": 300
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/email/auth-request \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com"
  }'
```

---

#### **ÉTAPE 2 : Vérifier le code et se connecter**

```bash
POST /auth/email/auth-verify
```

**Request Body:**
```json
{
  "email": "user@example.com",
  "otp": "123456",
  "password": "SecurePass123!"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "user": {
    "id": "user-456",
    "email": "user@example.com",
    "firstName": "Jane",
    "lastName": "Smith"
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/email/auth-verify \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user@example.com",
    "otp": "123456",
    "password": "SecurePass123!"
  }'
```

---

### 4️⃣ Rafraîchir le Token

```bash
POST /auth/refresh
```

**Request Body:**
```json
{
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "660e8400-e29b-41d4-a716-446655440001",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "550e8400-e29b-41d4-a716-446655440000"
  }'
```

---

### 5️⃣ Déconnexion

```bash
POST /auth/logout
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
{
  "message": "Déconnexion réussie"
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/logout \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

---

## 🌐 OAuth2 Providers

### 1️⃣ Connexion avec Google

**Processus en 2 étapes :**

#### **ÉTAPE 1 : Rediriger vers Google**

```bash
curl -X GET "http://localhost:8081/api/v1/auth/oauth2/authorize/google"
```

**Paramètres:**
- `redirect_uri` : URL de redirection après authentification

**Réponse:** Redirection vers Google OAuth2

**cURL:**
```bash
curl -X GET "http://localhost:8081/api/v1/auth/oauth2/authorize/google"
```

---

#### **ÉTAPE 2 : Callback après authentification Google**

```bash
POST /auth/oauth2/callback/google
```

**Request Body:**
```json
{
  "code": "4/0AY0e-g7...",
  "redirectUri": "http://localhost:3000/callback"
}
```

**Response 200 (Profil complet):**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "profileComplete": true,
  "user": {
    "id": "user-789",
    "email": "user@gmail.com",
    "firstName": "John",
    "lastName": "Doe",
    "phoneNumber": "+22300000000",
    "provider": "GOOGLE"
  }
}
```

**Response 200 (Profil incomplet - téléphone manquant):**
```json
{
  "tempToken": "temp_oauth2_xyz123",
  "profileComplete": false,
  "missingFields": ["phoneNumber", "countryCode"],
  "message": "Veuillez compléter votre profil",
  "user": {
    "email": "user@gmail.com",
    "firstName": "John",
    "lastName": "Doe"
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/api/v1/auth/oauth2/callback/google \
  -H "Content-Type: application/json" \
  -d '{
    "code": "4/0AY0e-g7...",
    "redirectUri": "http://localhost:3000/callback"
  }'
```

---

#### **ÉTAPE 3 : Compléter le profil (si nécessaire)**

```bash
POST /auth/oauth2/complete-profile
```

**Request Body:**
```json
{
  "tempToken": "temp_oauth2_xyz123",
  "phoneNumber": "+22300000000",
  "countryCode": "+223"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "profileComplete": true,
  "user": {
    "id": "user-789",
    "email": "user@gmail.com",
    "firstName": "John",
    "lastName": "Doe",
    "phoneNumber": "+22300000000",
    "provider": "GOOGLE"
  }
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/auth/oauth2/complete-profile \
  -H "Content-Type: application/json" \
  -d '{
    "tempToken": "temp_oauth2_xyz123",
    "phoneNumber": "+22300000000",
    "countryCode": "+223"
  }'
```

---

### 2️⃣ Connexion avec Facebook

**Même processus que Google, remplacer `/google` par `/facebook`**

#### **ÉTAPE 1 : Rediriger vers Facebook**

```bash
GET /api/v1/auth/oauth2/authorize/facebook
```

#### **ÉTAPE 2 : Callback après authentification**

```bash
curl -X GET "http://localhost:8081/api/v1/auth/oauth2/authorize/facebook"
```

**Request Body:**
```json
{
  "authorizationUrl": "https://www.facebook.com/v18.0/dialog/oauth?client_id=1405548874287722&redirect_uri=http://localhost:8081/api/v1/auth/oauth2/callback/facebook&response_type=code&scope=email,public_profile",
  "provider": "facebook",
  "message": "Redirigez l'utilisateur vers cette URL"
}
```

---

### 3️⃣ Connexion avec Apple (Sign in with Apple)

Apple ne suit pas le même parcours que Google et Facebook. Trois différences
changent le contrat côté client, et elles ne sont pas facultatives.

#### Ce qui diffère

| | Google / Facebook | Apple |
|---|---|---|
| Méthode du rappel | `GET`, paramètres de requête | **`POST`**, corps de formulaire |
| Source de l'identité | point de profil (`userinfo`) | le **`id_token`** — Apple n'a aucun point de profil |
| Nom de la personne | à chaque connexion | **une seule fois**, à la première autorisation |
| Secret client | chaîne fixe | **JWT ES256** produit et renouvelé par le service |

#### ÉTAPE 1 : Rediriger vers Apple

```bash
GET /api/v1/auth/oauth2/authorize/apple
```

Réponse identique aux autres fournisseurs : une `authorizationUrl`. Elle porte
`response_type=code id_token` et `response_mode=form_post`, qu'Apple **exige**
dès que le périmètre demande `name` ou `email`.

Si Apple n'est pas configuré sur l'installation, l'appel répond **400** avec le
code `VAL_6006`. Le service démarre et fonctionne sans configuration Apple :
seuls les parcours Apple sont refusés.

#### ÉTAPE 2 : Rappel en `form_post`

```bash
POST /auth/oauth2/callback/apple
Content-Type: application/x-www-form-urlencoded
```

Champs du formulaire envoyés par Apple :

| Champ | Présence | Rôle |
|---|---|---|
| `code` | toujours | code d'autorisation à échanger |
| `state` | toujours | valeur anti-falsification, **vérifiée** |
| `id_token` | toujours | porte l'identité |
| `user` | **première autorisation seulement** | JSON contenant le nom |
| `error` | en cas de refus | ex. `user_cancelled_authorize` |

Réponse : **302** vers le frontend, comme pour les autres fournisseurs.

#### Le champ `user` ne repasse jamais

```json
{"name":{"firstName":"Awa","lastName":"Diallo"},"email":"awa@privaterelay.appleid.com"}
```

Apple ne l'envoie qu'à la **première** autorisation de cette personne pour cette
application. Les connexions suivantes arrivent sans lui. Un nom non enregistré
ce jour-là est perdu définitivement, sauf si la personne révoque l'accès dans
les réglages de son appareil puis se reconnecte. C'est la cause connue des
comptes Apple anonymes.

#### Adresses relayées

Quand la personne choisit « Masquer mon adresse », Apple fournit une adresse en
`@privaterelay.appleid.com`, qui relaie le courrier vers la vraie. Elle est
valide et acceptée : c'est le choix proposé par défaut par Apple, et la refuser
reviendrait à refuser la connexion à une grande partie des utilisateurs Apple.

#### Configuration requise

Cinq valeurs, toutes dans l'environnement — **jamais** dans un fichier du
dépôt :

| Variable | Où la trouver |
|---|---|
| `APPLE_TEAM_ID` | en haut à droite de la console Apple Developer |
| `APPLE_SERVICES_ID` | **le Services ID, pas l'App ID** — voir ci-dessous |
| `APPLE_KEY_ID` | affiché à la création de la clé de signature |
| `APPLE_PRIVATE_KEY` | contenu du `.p8`, téléchargeable **une seule fois** |
| `APPLE_REDIRECT_URI` | domaine joignable — Apple **refuse** `localhost` |

La confusion entre Services ID et App ID est la première cause d'échec de cette
intégration, et Apple n'y répond que par un `invalid_client`, qui ne désigne pas
la cause.

---

## ⚠️ Le paramètre `state` est désormais vérifié

Cela concerne **les trois fournisseurs**, pas seulement Apple.

Jusqu'ici l'URL d'autorisation ne portait aucun `state` et le rappel n'en
vérifiait aucun. Le service en produit maintenant un, le retient, et le
**consomme** au début du rappel — avant l'échange du code.

Conséquences pour un client :

- la valeur rendue par le fournisseur doit être transmise **telle quelle** ;
- elle ne sert qu'**une fois** : un rappel rejoué est refusé ;
- une valeur émise pour un fournisseur n'est pas recevable au rappel d'un
  autre ;
- un rappel sans `state` est refusé avec `VAL_6004`.

Un client qui laissait tomber ce paramètre doit le conserver. Sans cette
vérification, un tiers pouvait faire aboutir son propre parcours dans le
navigateur de la victime : selon le cas, elle se retrouvait connectée au compte
de l'attaquant, ou le compte social de l'attaquant se liait au sien — ce qui lui
en donnait l'accès durable.

---

## 🔐 Authentification Biométrique

### 1️⃣ Enregistrer une Credential Biométrique

**Processus en 2 étapes :**

#### **ÉTAPE 1 : Initier l'enregistrement**

```bash
POST /auth/biometric/register/initiate?type=FACE_ID
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Query Params:**
- `type` : `FACE_ID`, `TOUCH_ID`, `FINGERPRINT`, `WINDOWS_HELLO`

**Response 200:**
```json
{
  "challenge": "random_base64_challenge_string",
  "userId": "user-123",
  "rpId": "localhost",
  "rpName": "Auth Service",
  "timeout": 60000
}
```

**cURL Exemples:**

**Face ID (iOS/macOS):**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/register/initiate?type=FACE_ID" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

**Touch ID (iOS/macOS):**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/register/initiate?type=TOUCH_ID" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

**Fingerprint (Android/Windows):**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/register/initiate?type=FINGERPRINT" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

**Windows Hello:**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/register/initiate?type=WINDOWS_HELLO" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

#### **ÉTAPE 2 : Compléter l'enregistrement**

```bash
POST /auth/biometric/register/complete
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Request Body:**
```json
{
  "credentialId": "base64_credential_id",
  "publicKey": "base64_public_key",
  "attestationObject": "base64_attestation",
  "clientDataJSON": "base64_client_data",
  "challenge": "random_base64_challenge_string",
  "biometricType": "FACE_ID"
}
```

**Response 200:**
```json
{
  "message": "Credential biométrique enregistrée avec succès",
  "credentialId": "cred-456",
  "biometricType": "FACE_ID"
}
```

**cURL:**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/register/complete" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "type": "FACE_ID",
    "credentialId": "cred_abc123xyz",
    "publicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA...",
    "attestationObject": "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YVikSZYN5...",
    "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdl...",
    "deviceName": "iPhone 15 Pro",
    "challenge": "Y2hhbGxlbmdlX2Jhc2U2NF9zdHJpbmc="
  }'
```

---

### 2️⃣ Authentification avec Biométrie

#### **ÉTAPE 1 : Initier l'authentification**

```bash
POST /api/v1/auth/biometric/authenticate/initiate
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
{
  "challenge": "YXV0aF9jaGFsbGVuZ2VfYmFzZTY0",
  "allowedCredentials": [
    {
      "credentialId": "cred_abc123xyz",
      "biometricType": "FACE_ID",
      "deviceName": "iPhone 15 Pro"
    },
    {
      "credentialId": "cred_def456uvw",
      "biometricType": "TOUCH_ID",
      "deviceName": "MacBook Pro"
    }
  ],
  "timeout": 60000,
  "rpId": "localhost",
  "message": "Sélectionnez une méthode biométrique pour vous authentifier"
}
```

**cURL:**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/authenticate/initiate" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

#### **ÉTAPE 2 : Vérifier l'authentification**

```bash
POST /api/v1/auth/biometric/authenticate/verify
```

**Request Body:**
```json
{
  "credentialId": "cred_abc123xyz",
  "authenticatorData": "SZYN5YgOjGh0NBcPZHZgW4/krrmihjLHmVzzuoMdl2MFAAAAAQ==",
  "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiWVhWMGFGOWphR0ZzYkdWdVoyVmZZbUZ6WlRZMCIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6MzAwMCJ9",
  "signature": "MEUCIQDqF7xqQJW3J5qF8xqQJW3J5qF8xqQJW3J5qF8xqQJW3AIgR5qF8xqQJW3J5qF8xqQJW3J5qF8xqQJW3J5qF8xqQ==",
  "challenge": "YXV0aF9jaGFsbGVuZ2VfYmFzZTY0"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000",
  "expiresIn": 3600,
  "tokenType": "Bearer",
  "biometricType": "FACE_ID",
  "deviceName": "iPhone 15 Pro",
  "authenticatedAt": "2026-02-13T18:35:00Z",
  "message": "Authentification biométrique réussie",
  "success": true
}
```

**cURL:**
```bash
curl -X POST "http://localhost:8081/api/v1/auth/biometric/authenticate/verify" \
  -H "Content-Type: application/json" \
  -d '{
    "credentialId": "cred_abc123xyz",
    "authenticatorData": "SZYN5YgOjGh0NBcPZHZgW4/krrmihjLHmVzzuoMdl2MFAAAAAQ==",
    "clientDataJSON": "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiWVhWMGFGOWphR0ZzYkdWdVoyVmZZbUZ6WlRZMCIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6MzAwMCJ9",
    "signature": "MEUCIQDqF7xqQJW3J5qF8xqQJW3J5qF8xqQJW3J5qF8xqQJW3AIgR5qF8xqQJW3J5qF8xqQJW3J5qF8xqQJW3J5qF8xqQ==",
    "challenge": "YXV0aF9jaGFsbGVuZ2VfYmFzZTY0"
  }'
```

---

### 3️⃣ Lister les Credentials Biométriques

```bash
GET /auth/biometric/credentials
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
[
  {
    "id": "cred-456",
    "biometricType": "FACE_ID",
    "createdAt": "2026-02-13T17:00:00Z",
    "lastUsed": "2026-02-13T18:30:00Z"
  },
  {
    "id": "cred-789",
    "biometricType": "TOUCH_ID",
    "createdAt": "2026-02-10T10:00:00Z",
    "lastUsed": "2026-02-13T09:15:00Z"
  }
]
```

**cURL:**
```bash
curl -X GET "http://localhost:8081/api/v1/auth/biometric/credentials" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

### 4️⃣ Supprimer une Credential Biométrique

```bash
DELETE /auth/biometric/credentials/{credentialId}
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
{
  "message": "Credential biométrique supprimée avec succès"
}
```

**cURL:**
```bash
curl -X DELETE "http://localhost:8081/api/v1/auth/biometric/credentials/cred_abc123xyz" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

### 5️⃣ Supprimer Toutes les Credentials

```bash
DELETE /api/v1/auth/biometric/credentials
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
{
  "message": "Toutes les credentials biométriques ont été supprimées"
}
```

**cURL:**
```bash
curl -X DELETE "http://localhost:8081/api/v1/auth/biometric/credentials" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

### 6️⃣ Vérifier l'Existence de Credentials

```bash
GET /api/v1/auth/biometric/has-credentials
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
true
```

**cURL:**
```bash
curl -X GET "http://localhost:8081/api/v1/auth/biometric/has-credentials" \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

## 🔒 Libre-service du compte

Tout ce qui suit agit sur **le compte de l'appelant**, dont l'identité vient du
jeton. Aucun chemin, aucun corps ne porte d'identifiant de compte : c'est ce qui
rend l'IDOR impossible par construction plutôt que par vigilance.

Tous ces endpoints exigent `Authorization: Bearer {accessToken}`.

### Changer son mot de passe

```bash
POST /auth/password/change
```

```json
{
  "currentPassword": "...",
  "newPassword": "...",
  "confirmPassword": "..."
}
```

L'ancien mot de passe est **exigé**, et ce n'est pas une formalité : l'appelant
est déjà authentifié, mais s'en contenter rendrait un jeton d'accès volé
suffisant pour *prendre* le compte. Un échec est compté comme un échec de
connexion — sinon ce point d'entrée devient un oracle à mots de passe.

**Toutes les sessions sont fermées**, celle de l'appelant comprise. Si le
changement est motivé par un soupçon, laisser vivre les autres sessions
annulerait l'opération.

Jusqu'ici, seul `reset-password` existait : une personne connectée qui
connaissait son mot de passe devait réclamer un SMS pour en changer.

### Second facteur (TOTP) — deux étapes

Le second facteur est **facultatif**. Sans lui, `/auth/login` passe sans code.

```bash
POST /auth/totp/enroll     # 1. produit le secret, N'ACTIVE PAS
POST /auth/totp/confirm    # 2. active, après preuve
DELETE /auth/totp          # retire, code valide exigé
```

Les étapes 2 et 3 prennent `{"code": "123456"}`.

**Pourquoi deux étapes.** Activer dès la première requête enfermerait la
personne hors de son compte au moindre écart — téléphone à l'heure décalée,
secret mal recopié, application fermée avant d'enregistrer — et la seule sortie
serait un retrait par un `SUPER_ADMIN`. L'étape 1 le dit explicitement dans sa
réponse : le second facteur n'est pas encore actif.

C'est le compromis **inverse** de `/auth/admin/totp/enroll`, qui active aussitôt.
Ce choix se justifie pour un compte d'administration — une fenêtre sans second
facteur y coûte plus qu'un enrôlement raté — et pas pour un client.

**La désactivation exige un code valide.** L'appelant est authentifié, mais s'en
contenter viderait le second facteur de son sens : qui vole un jeton pourrait
retirer la protection qui existe précisément pour que le vol d'un jeton ne
suffise pas.

**Qui a perdu son appareil présente un code de secours.** `DELETE /auth/totp`
accepte un code TOTP **ou** l'un des codes de secours du compte — voir la
section ci-dessous. Auparavant, la seule sortie était un retrait par un
`SUPER_ADMIN` : une intervention humaine par client bloqué, et surtout un
support obligé de juger de l'identité de quelqu'un qui ne peut plus la prouver.
C'est le chemin exact des attaques par ingénierie sociale.

`POST /auth/totp/confirm` est la seule exception : il exige un code TOTP, et
**refuse** un code de secours. Son objet est de prouver que le *nouvel* appareil
produit les bons codes ; un code de secours ne prouve rien de cela, et
l'enrôlement serait déclaré confirmé alors que l'application d'authentification
n'a jamais été vérifiée — laissant la personne avec un second facteur actif
qu'elle ne peut pas produire.

### Sessions ouvertes

```bash
GET    /auth/sessions
DELETE /auth/sessions/{sessionId}
DELETE /auth/sessions?conserverLaCourante=true
```

| En-tête optionnel | Rôle |
|---|---|
| `X-Refresh-Token` | marque la session de l'appelant (`courante: true`) |

En en-tête et non en paramètre de requête : c'est un justificatif, et dans une
chaîne de requête il finirait dans l'historique du navigateur, l'en-tête
`Referer` et les journaux d'accès.

`conserverLaCourante` vaut `true` par défaut — « déconnecter mes autres
appareils », l'usage courant. À `false`, l'appelant est déconnecté aussi.

**Ce que « fermer » fait et ne fait pas.** La révocation coupe le
**renouvellement**. Le jeton d'accès déjà émis reste valable jusqu'à son
échéance : c'est la nature d'un JWT, et le raccourcir demanderait une
consultation en base à chaque requête.

### Confirmer son numéro et son adresse

```bash
POST /auth/email/confirm-request
POST /auth/email/confirm-verify    # {"code": "123456"}
POST /auth/phone/confirm-request
POST /auth/phone/confirm-verify    # {"code": "123456"}
```

L'inscription se fait par **un** canal. Le canal utilisé ressort confirmé ;
l'autre restait non confirmé et **aucun** parcours ne permettait de le
confirmer. Une personne inscrite par téléphone avait un `isEmailVerified` faux
pour toujours.

Un compte inscrit par adresse seule porte un numéro de substitution préfixé
`temp_` : la demande de confirmation du téléphone est alors refusée, plutôt que
de tenter un SMS qui échouerait chez le fournisseur.

Les codes utilisent des types distincts de ceux de la connexion par OTP. Les
réutiliser aurait rendu un code de connexion échangeable contre une
confirmation de contact, et inversement.

### Appareils de confiance

```bash
POST   /auth/trusted-devices          # {"code": "123456", "nom": "Portable"}
GET    /auth/trusted-devices
DELETE /auth/trusted-devices/{id}
DELETE /auth/trusted-devices
```

Un appareil de confiance **dispense du second facteur**, et de lui seul : le mot
de passe reste exigé. Un jeton volé ne suffit donc pas à entrer.

**Le code TOTP est exigé pour accorder la confiance** (`POST`). L'accorder sans
l'avoir exigé une fois donnerait à qui détient le mot de passe un moyen de s'en
dispenser définitivement : la protection s'annulerait elle-même. L'appelant est
déjà authentifié, mais sa session a peut-être été ouverte depuis un autre
appareil de confiance — donc sans code.

Sans second facteur activé, la demande est **refusée** (`2FA_9001`) : un appareil
de confiance ne dispense de rien quand il n'y a rien à dispenser.

**Le jeton est remis une seule fois.** Il n'est stocké que haché : personne ne
peut le relire, administrateur compris.

```json
{
  "id": "...",
  "jeton": "...",
  "nom": "Portable",
  "expireLe": "2026-11-12T10:00:00",
  "message": "Conservez ce jeton maintenant : il ne sera plus affiché..."
}
```

Aux connexions suivantes, le présenter dans le champ `deviceToken` de
`POST /auth/login`. Un jeton absent, périmé, révoqué ou appartenant à un autre
compte fait simplement **demander le code** — ce n'est pas une erreur.

| Propriété | Raison |
|---|---|
| Jeton aléatoire du serveur, pas une empreinte d'appareil | l'IP change à chaque déplacement, et l'User-Agent n'est pas unique |
| Stocké haché (SHA-256) | une sauvegarde ou une injection SQL ne doit pas livrer des dispenses utilisables |
| Échéance obligatoire, **jamais prolongée** par l'usage | une échéance repoussée à chaque connexion ne s'atteint jamais : la confiance deviendrait perpétuelle |

La liste n'expose **ni le jeton ni son empreinte**, et écarte les appareils
échus — les montrer pousserait à « révoquer » ce qui ne vaut plus rien pendant
que le vrai appareil reste.

`DELETE /auth/trusted-devices` n'exige **pas** de code : c'est l'opération à
faire après la perte d'un appareil, et exiger un code pour *renforcer* la
sécurité serait un obstacle au mauvais moment — la personne vient peut-être de
perdre l'appareil qui le produit.

### Codes de secours

```bash
POST /auth/backup-codes    # {"code": "123456"} — rend les codes UNE SEULE fois
GET  /auth/backup-codes    # état : combien restent, lesquels ont servi
```

C'est la sortie de celui qui perd l'appareil produisant ses codes TOTP. Les
codes sont remis **à l'avance**, au moment où la personne peut encore prouver
qui elle est — et non après l'incident, quand elle ne peut plus.

**Un code de secours vaut preuve partout où un code TOTP vaut preuve** :
`/auth/login`, `DELETE /auth/totp`, `POST /auth/trusted-devices`,
`POST /auth/backup-codes` lui-même. La seule exception est
`POST /auth/totp/confirm`, expliquée plus haut.

| Propriété | Raison |
|---|---|
| Haché en **BCrypt**, pas en SHA-256 | un code court et lisible a un espace minuscule : un SHA-256 se casse par force brute en quelques secondes. Le coût de BCrypt est acceptable ici — un code de secours sert quelques fois dans la vie d'un compte, pas à chaque connexion |
| Usage **unique** | un code intercepté une fois n'ouvre pas le compte indéfiniment |
| Quatre premiers caractères en clair | sans eux, « code consommé le 3 mars depuis 41.223.10.5 » n'apprend rien d'exploitable : la personne ne sait pas *lequel* de ses dix codes a servi |
| Alphabet sans `O`, `0`, `I`, `1`, `L` | un code est recopié à la main : la confusion entre O et zéro est la première cause de saisie fausse, et une saisie fausse compte comme un échec |
| Ligne **conservée** après usage | un code consommé que la personne ne reconnaît pas est le signal le plus clair qu'elle puisse recevoir d'une intrusion. « Effacer n'est pas supprimer la ligne » |

**La régénération invalide le lot précédent.** Deux lots valides à la fois
doubleraient la surface sans que la personne le sache : elle croirait avoir
remplacé ses codes alors que les anciens fonctionneraient encore.

**Le lot meurt avec le secret qu'il secourt.** Désactivation, retrait
administratif, réinitialisation : chacun invalide le lot. Sans cela, les codes
imprimés du temps de l'appareil perdu resteraient utilisables après un
ré-enrôlement — qui exige un code du *nouvel* appareil, donc ne les touche pas.
Un lot oublié dans un tiroir serait une clef valide des années plus tard.

Les codes invalidés sont marqués **`remplace`**, distinct de « a servi ». La
distinction est visible par la personne : confondre les deux lui ferait
soupçonner une intrusion alors qu'elle a simplement demandé de nouveaux codes.

**Ce que le temps de réponse laisse voir.** Un échec compare le code présenté à
tous les codes restants, donc révèle leur nombre. Fuite admise, et non ignorée :
les codes sont des tirages indépendants de `SecureRandom`, et connaître leur
nombre ne réduit en rien l'espace à deviner.

### Alertes de sécurité

Elles ne bloquent rien. Elles rendent **visible** ce qui serait passé inaperçu —
et c'est toute la différence entre un détournement de compte réussi et un
détournement repéré.

Un attaquant qui obtient un mot de passe change l'adresse, puis le mot de passe.
Chaque étape est légitime vue du système : la bonne session, les bons
justificatifs. La seule chose qui trahit l'opération est que le propriétaire en
soit informé **pendant**, pas le jour où il n'arrive plus à se connecter.

| Événement | Destinataire | Conditionnel ? |
|---|---|---|
| Mot de passe modifié (`/auth/password/change`) | coordonnée vérifiée actuelle | **non** |
| Mot de passe réinitialisé (`/auth/reset-password`) | coordonnée vérifiée actuelle | **non** |
| Adresse modifiée (`PUT /auth/profile`) | l'**ANCIENNE** adresse | **non** |
| Connexion aboutie | adresse vérifiée | **oui**, sur demande |

**La règle qui gouverne tout : l'ANCIENNE coordonnée.** Quand une adresse
change, c'est l'ancienne qui est prévenue. Prévenir la nouvelle ne préviendrait
que l'attaquant, qui vient justement de la choisir. C'est une inversion facile à
écrire sans y penser — l'entité en mémoire porte déjà la nouvelle valeur au
moment où on voudrait l'envoyer — donc l'ancienne valeur est capturée **avant**
toute mutation, et un test de mutation vérifie que l'ordre tient.

**Rien n'est envoyé à une coordonnée non vérifiée.** Une adresse que personne
n'a prouvée n'appartient pas forcément au titulaire : elle peut avoir été posée
par l'attaquant au tour précédent, ce qui ferait de l'alerte un accusé de
réception à son intention.

**Courriel d'abord, SMS en repli.** Le courriel est gratuit et porte les
circonstances complètes ; le SMS coûte et ne tient pas en 160 caractères.
Le repli n'est pas un confort : sans lui, un compte inscrit par téléphone — cas
courant ici — ne serait prévenu de rien. L'alerte de connexion n'a **pas** de
repli SMS : la personne a demandé un courriel, et basculer en SMS lui enverrait
un message payant à chaque connexion.

**Un échec d'envoi ne fait jamais échouer l'opération**, qui est déjà
enregistrée : remonter l'erreur ferait croire à la personne que son mot de passe
est inchangé, et elle réessaierait avec l'ancien. L'erreur est donc avalée —
mais **comptée** (`auth_security_alerte_total{resultat="echec"}`), avec une
alerte Prometheus au premier échec. Un envoi avalé et seulement journalisé est
une protection qui s'éteint sans bruit.

**Aucun lien, aucun code, aucun jeton dans le texte.** Un courriel d'alerte est
lu par qui a accès à la boîte — et c'est précisément ce dont on n'est pas sûr à
l'instant où on l'envoie. Un lien « ce n'était pas moi, réinitialiser ici »
offrirait à l'attaquant un chemin de reprise en main.

#### Le réglage de l'alerte de connexion

```bash
GET /auth/alerts/new-login
PUT /auth/alerts/new-login    # {"actif": true}
```

`PUT` et non `POST`/`DELETE` : rien n'est créé ni supprimé, un booléen change
d'état. Avec la valeur voulue dans le corps, l'opération est idempotente — une
bascule sans valeur (« inverse le réglage ») ne l'est pas, et un client qui
réessaie après un délai d'attente inverserait deux fois.

**Pourquoi celle-ci est optionnelle et les autres non.** Une connexion est
fréquente et ordinaire : alerter à chaque fois pour tout le monde atteindrait un
volume dont le résultat connu est que les gens cessent de lire les alertes, puis
les désactivent. Les alertes de changement de mot de passe et d'adresse sont
rares, toujours notables, et sur le chemin obligatoire de tout détournement
durable — sans changer l'adresse ni le mot de passe, un attaquant perd l'accès
dès que la session expire.

**L'activation exige une adresse vérifiée** (`VAL_6008`). Accepter
silencieusement produirait un réglage affiché comme actif qui n'enverrait jamais
rien : la personne se croirait prévenue de chaque connexion et ne chercherait
aucun autre moyen de surveiller son compte. La réponse porte
`adresseVerifiee` pour que l'écran puisse dire la seule chose utile — « confirmez
votre adresse pour activer cette alerte ». La **désactivation**, elle, n'exige
rien : sinon un changement d'adresse, qui remet le drapeau de vérification à
faux, enfermerait la personne dans un réglage qu'elle ne peut plus retirer.

#### Ce qui n'est PAS couvert : le changement de numéro

Aucun endpoint ne permet de changer son numéro de téléphone. L'alerte « ancien
numéro prévenu » n'existe donc pas, faute d'événement à signaler — et non par
oubli. La confirmation de contact (`/auth/phone/confirm-*`) **vérifie** le numéro
courant, elle ne le remplace pas.

Un tel changement demande son propre parcours : code envoyé au **nouveau**
numéro, numéro en attente conservé jusqu'à confirmation, puis alerte à l'ancien.
C'est le prochain élément de cette liste.

### Comptes sociaux liés

```bash
GET    /auth/oauth2/accounts
DELETE /auth/oauth2/accounts/{provider}
GET    /auth/oauth2/providers        # PUBLIC
```

La liste ne contient **aucun** jeton du fournisseur : `oauth_accounts` porte un
`access_token` et un `refresh_token` valables chez Google ou chez Apple.

**Délier le dernier moyen de connexion est refusé** (`VAL_6007`). Une
inscription par Google suivie d'une déliaison de Google laisserait un compte sans
mot de passe — les comptes créés par OAuth2 ont `passwordHash` à `null` — et sans
fournisseur : personne ne pourrait plus y entrer. Le message indique la sortie :
définir un mot de passe, ou lier un autre fournisseur d'abord.

La déliaison prévient le fournisseur (révocation Apple) avant de supprimer la
liaison, et un échec de révocation ne l'empêche pas.

`GET /auth/oauth2/providers` est **public** : l'écran de connexion en a besoin
avant toute session, pour n'afficher que les boutons qui fonctionnent.

### ⚠️ Changements de contrat sur des endpoints existants

| Endpoint | Ce qui change |
|---|---|
| `POST /auth/refresh` | **refuse un compte verrouillé.** Le verrou n'était pas consulté ici : qui détenait un refresh token renouvelait son accès indéfiniment après le verrouillage |
| `POST /admin/accounts/{id}/lock` | **ferme les sessions ouvertes.** Le verrou ne portait que sur la connexion |
| `PUT /auth/profile` | changer l'adresse remet `isEmailVerified` à **faux** ; elle restait à vrai, donc une adresse jamais prouvée héritait du drapeau |
| `POST /auth/resend-code` | accepte `phoneNumber` **ou** `email`, un seul des deux. Il appelait toujours le parcours téléphone malgré son nom générique |

---

## 👤 Gestion de Profil

### 1️⃣ Obtenir le Profil

```bash
GET /auth/profile
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Response 200:**
```json
{
  "user": {
    "id": "user-123",
    "phoneNumber": "+22300000000",
    "email": "john@example.com",
    "firstName": "John",
    "lastName": "Doe",
    "role": "USER",
    "isPhoneVerified": true,
    "isEmailVerified": true,
    "isActive": true,
    "createdAt": "2026-01-15T10:00:00Z",
    "lastLogin": "2026-02-13T17:30:00Z"
  }
}
```

**cURL:**
```bash
curl -X GET http://localhost:8081/auth/profile \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN"
```

---

### 2️⃣ Mettre à Jour le Profil

```bash
PUT /auth/profile
```

**Headers:**
```
Authorization: Bearer {accessToken}
```

**Request Body:**
```json
{
  "firstName": "John",
  "lastName": "Smith",
  "email": "john.smith@example.com"
}
```

**Response 200:**
```json
{
  "message": "Profil mis à jour avec succès",
  "user": {
    "id": "user-123",
    "firstName": "John",
    "lastName": "Smith",
    "email": "john.smith@example.com"
  }
}
```

**cURL:**
```bash
curl -X PUT http://localhost:8081/auth/profile \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Smith",
    "email": "john.smith@example.com"
  }'
```

---

### 3️⃣ Mot de Passe Oublié

#### **ÉTAPE 1 : Demander un code de réinitialisation**

```bash
POST /auth/forgot-password
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000"
}
```

**Response 200:**
```json
{
  "message": "Code de réinitialisation envoyé",
  "phoneNumber": "+223****8697",
  "expiresIn": 600
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000"
  }'
```

---

#### **ÉTAPE 2 : Réinitialiser le mot de passe**

```bash
POST /auth/reset-password
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "code": "123456",
  "newPassword": "NewSecurePass123!",
  "confirmPassword": "NewSecurePass123!"
}
```

**Response 200:**
```json
{
  "message": "Mot de passe réinitialisé avec succès"
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "code": "123456",
    "newPassword": "NewSecurePass123!",
    "confirmPassword": "NewSecurePass123!"
  }'
```

---

### 4️⃣ Renvoyer un Code de Vérification

```bash
POST /auth/resend-code
```

**Request Body:**
```json
{
  "phoneNumber": "+22300000000",
  "type": "REGISTRATION"
}
```

**Types disponibles:**
- `REGISTRATION` : Code d'inscription
- `PASSWORD_RESET` : Code de réinitialisation
- `PHONE_VERIFICATION` : Vérification téléphone
- `EMAIL_VERIFICATION` : Vérification email

**Response 200:**
```json
{
  "message": "Code renvoyé avec succès",
  "expiresIn": 600
}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/auth/resend-code \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+22300000000",
    "type": "REGISTRATION"
  }'
```

---

## 📊 Logs d'Authentification (Admin)

### 1️⃣ Obtenir Tous les Logs

```bash
GET /admin/logs
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Response 200:**
```json
[
  {
    "id": "log-123",
    "userId": "user-456",
    "phoneNumber": "+22300000000",
    "eventType": "LOGIN",
    "status": "SUCCESS",
    "ipAddress": "192.168.1.100",
    "userAgent": "Mozilla/5.0...",
    "timestamp": "2026-02-13T17:30:00Z",
    "details": "Authentification réussie"
  }
]
```

**cURL:**
```bash
curl -X GET http://localhost:8081/admin/logs \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 2️⃣ Filtrer les Logs (Avancé)

```bash
GET /admin/logs/filter
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Query Params:**
- `eventType` : `LOGIN`, `LOGOUT`, `REGISTRATION`, `PASSWORD_RESET`
- `status` : `SUCCESS`, `FAILED`
- `userId` : ID de l'utilisateur
- `phoneNumber` : Numéro de téléphone
- `ipAddress` : Adresse IP
- `startDate` : Date de début (ISO 8601)
- `endDate` : Date de fin (ISO 8601)
- `page` : Numéro de page (défaut: 0)
- `size` : Taille de page (défaut: 50)
- `sortDirection` : `ASC` ou `DESC` (défaut: DESC)

**Response 200:**
```json
[
  {
    "id": "log-123",
    "userId": "user-456",
    "eventType": "LOGIN",
    "status": "SUCCESS",
    "timestamp": "2026-02-13T17:30:00Z"
  }
]
```

**cURL:**
```bash
curl -X POST http://localhost:8081/admin/logs/filter \
  -H "Content-Type: application/json" \
  -d '{"eventType":"LOGIN","status":"SUCCESS","page":0,"size":20}' \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 3️⃣ Logs par Utilisateur

```bash
GET /admin/logs/user/{userId}
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**cURL:**
```bash
curl -X GET http://localhost:8081/admin/logs/user/user-456 \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 4️⃣ Logs par Téléphone

```bash
POST /admin/logs/search/by-phone   (numéro dans le CORPS, pas dans l'URL)
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/admin/logs/search/by-phone \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+22300000000"}' \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 5️⃣ Logs par Statut

```bash
GET /admin/logs/by-status/{status}
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Statuts disponibles:** `SUCCESS`, `FAILED`

**cURL:**
```bash
curl -X GET http://localhost:8081/admin/logs/by-status/FAILED \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 6️⃣ Logs par Type d'Événement

```bash
GET /admin/logs/by-event/{eventType}
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Types disponibles:** `LOGIN`, `LOGOUT`, `REGISTRATION`, `PASSWORD_RESET`

**cURL:**
```bash
curl -X GET http://localhost:8081/admin/logs/by-event/LOGIN \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 7️⃣ Logs par Adresse IP

```bash
POST /admin/logs/search/by-ip   (adresse dans le CORPS : une IP est une donnée personnelle)
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**cURL:**
```bash
curl -X POST http://localhost:8081/admin/logs/search/by-ip \
  -H "Content-Type: application/json" \
  -d '{"ipAddress":"192.168.1.100"}' \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 8️⃣ Logs par Plage de Dates

```bash
GET /admin/logs/by-date-range
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Query Params:**
- `startDate` : Date de début (ISO 8601) - **Requis**
- `endDate` : Date de fin (ISO 8601) - **Requis**

**cURL:**
```bash
curl -X GET "http://localhost:8081/admin/logs/by-date-range?startDate=2026-02-01T00:00:00Z&endDate=2026-02-13T23:59:59Z" \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 9️⃣ Statistiques des Logs

```bash
GET /admin/logs/stats
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Response 200:**
```json
{
  "totalLogs": 1500,
  "successCount": 1350,
  "failedCount": 150,
  "successRate": 90.0,
  "eventTypeCounts": {
    "LOGIN": 800,
    "LOGOUT": 400,
    "REGISTRATION": 200,
    "PASSWORD_RESET": 100
  },
  "statusCounts": {
    "SUCCESS": 1350,
    "FAILED": 150
  }
}
```

**cURL:**
```bash
curl -X GET http://localhost:8081/admin/logs/stats \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

### 🔟 Nettoyer les Anciens Logs

```bash
DELETE /admin/logs/cleanup
```

**Headers:**
```
Authorization: Bearer {admin_access_token}
```

**Query Params:**
- `daysToKeep` : Nombre de jours à conserver (défaut: 90)

**Response 200:**
```json
{
  "message": "Logs nettoyés avec succès",
  "daysKept": "90"
}
```

**cURL:**
```bash
curl -X DELETE "http://localhost:8081/admin/logs/cleanup?daysToKeep=30" \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

---

## 📝 Codes d'Erreur

| Code | Message | Description |
|------|---------|-------------|
| `AUTH_1001` | Identifiants invalides | Email/téléphone ou mot de passe incorrect |
| `AUTH_1002` | Compte verrouillé | Trop de tentatives échouées |
| `AUTH_1003` | Compte désactivé | Le compte a été désactivé |
| `AUTH_1004` | Téléphone non vérifié | Le numéro n'a pas été vérifié |
| `AUTH_1005` | Email non vérifié | L'email n'a pas été vérifié |
| `AUTH_1006` | Token expiré | Le token JWT a expiré |
| `REG_2001` | Téléphone déjà utilisé | Ce numéro existe déjà |
| `REG_2002` | Email déjà utilisé | Cet email existe déjà |
| `VER_3001` | Code invalide | Le code de vérification est incorrect |
| `VER_3002` | Code expiré | Le code a expiré |
| `VER_3004` | Trop de tentatives | Limite de tentatives atteinte |

---

## 🎯 Résumé des Flux

### **Inscription**
1. **Phone OTP** : `/phone/register-request` → `/phone/register-verify`
2. **Email OTP** : `/email/register` → `/email/register-verify`

### **Connexion**
1. **Classique** : `/login` (phone/email + password)
2. **Phone OTP** : `/phone/auth-request` → `/phone/auth-verify`
3. **Email OTP** : `/email/auth-request` → `/email/auth-verify`
4. **Google** : `/oauth2/authorize/google` → `/oauth2/callback/google` → (optionnel) `/oauth2/complete-profile`
5. **Facebook** : `/oauth2/authorize/facebook` → `/oauth2/callback/facebook` → (optionnel) `/oauth2/complete-profile`
6. **Biométrique** : `/biometric/register/initiate` → `/biometric/register/complete` → `/biometric/verify`

### **Gestion**
- **Profil** : `GET /profile`, `PUT /profile`
- **Mot de passe** : `/forgot-password` → `/reset-password`
- **Tokens** : `/refresh`, `/logout`

### **Admin**
- **Logs** : Filtrage avancé, statistiques, nettoyage

---

**🚀 Service prêt pour la production !**
