# 🔐 API Endpoints Admin - Auth Service

> **Base URL Gateway:** `http://localhost:8081/api/v1/auth/admin`  
> **Base URL Direct:** `http://localhost:8082/auth/admin`  
> **⚠️ Tous ces endpoints nécessitent le rôle ADMIN**

---

## 📊 Gestion des Comptes (AdminAccountController)

### 1. Vérifier le Statut de Verrouillage

**Endpoint:** `GET /admin/accounts/{identifier}/lockout-status`

**Description:** Vérifie si un compte est verrouillé et obtient les détails.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/accounts/+22300000000/lockout-status \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "identifier": "+22300000000",
  "isLocked": true,
  "lockedUntil": "2026-02-12T15:30:00",
  "failedAttempts": 5,
  "maxAttempts": 5
}
```

---

### 2. Déverrouiller un Compte

**Endpoint:** `POST /admin/accounts/{identifier}/unlock`

**Description:** Déverrouille manuellement un compte bloqué.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/accounts/+22300000000/unlock \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "message": "Compte déverrouillé avec succès",
  "identifier": "+22300000000"
}
```

---

### 3. Réinitialiser les Tentatives Échouées

**Endpoint:** `POST /admin/accounts/{identifier}/reset-attempts`

**Description:** Remet à zéro le compteur de tentatives de connexion échouées.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/accounts/+22300000000/reset-attempts \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "message": "Compteur de tentatives réinitialisé",
  "identifier": "+22300000000"
}
```

---

### 4. Obtenir les Tentatives Restantes

**Endpoint:** `GET /admin/accounts/{identifier}/remaining-attempts`

**Description:** Consulte le nombre de tentatives de connexion restantes.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/accounts/+22300000000/remaining-attempts \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "identifier": "+22300000000",
  "failedAttempts": 2,
  "maxAttempts": 5,
  "remainingAttempts": 3
}
```

---

### 5. Verrouiller un Compte (Suspension Admin)

**Endpoint:** `POST /admin/accounts/{identifier}/lock`

**Description:** Verrouille manuellement un compte pour une durée spécifiée.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/accounts/+22300000000/lock \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "durationMinutes": 60,
    "reason": "Activité suspecte détectée"
  }'
```

**Response 200:**
```json
{
  "message": "Compte verrouillé avec succès",
  "identifier": "+22300000000",
  "lockedUntil": "2026-02-12T16:00:00"
}
```

---

## 📝 Gestion des Templates (MessageTemplateController)

### 1. Récupérer Tous les Templates

**Endpoint:** `GET /admin/templates`

**Description:** Liste tous les templates de messages (SMS et Email).

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/templates \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
[
  {
    "id": "template-001",
    "templateType": "SMS_VERIFICATION",
    "subject": "Code de vérification",
    "content": "Votre code de vérification est: {code}. Valide pendant {minutes} minutes.",
    "variables": "code, minutes",
    "isActive": true,
    "createdAt": "2026-02-12T10:00:00"
  }
]
```

---

### 2. Créer un Template

**Endpoint:** `POST /admin/templates`

**Description:** Crée un nouveau template de message.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/templates \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "templateType": "SMS_CUSTOM",
    "subject": "Notification",
    "content": "Bonjour {firstName}, votre compte a été mis à jour.",
    "description": "Template personnalisé pour notifications",
    "variables": "firstName"
  }'
```

**Response 201:**
```json
{
  "id": "template-002",
  "templateType": "SMS_CUSTOM",
  "subject": "Notification",
  "content": "Bonjour {firstName}, votre compte a été mis à jour.",
  "isActive": true,
  "createdAt": "2026-02-12T11:00:00"
}
```

---

### 3. Mettre à Jour un Template

**Endpoint:** `PUT /admin/templates/{templateId}`

**Description:** Modifie un template existant.

```bash
curl -X PUT http://localhost:8081/api/v1/auth/admin/templates/template-001 \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Votre code de vérification est: {code}. Expire dans {minutes} minutes.",
    "isActive": true
  }'
```

**Response 200:**
```json
{
  "id": "template-001",
  "templateType": "SMS_VERIFICATION",
  "content": "Votre code de vérification est: {code}. Expire dans {minutes} minutes.",
  "isActive": true,
  "updatedAt": "2026-02-12T11:30:00"
}
```

---

### 4. Supprimer un Template

**Endpoint:** `DELETE /admin/templates/{templateId}`

**Description:** Supprime un template (désactivation logique).

```bash
curl -X DELETE http://localhost:8081/api/v1/auth/admin/templates/template-002 \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "message": "Template supprimé avec succès",
  "templateId": "template-002"
}
```

---

## 📊 Audit et Logs (AuditController)

### 1. Logs d'Authentification Récents

**Endpoint:** `GET /admin/audit/logs`

**Description:** Récupère les logs d'authentification récents.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/audit/logs \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
[
  {
    "id": "log-001",
    "userId": "user-123",
    "phoneNumber": "+22300000000",
    "eventType": "LOGIN",
    "status": "SUCCESS",
    "ipAddress": "192.168.1.100",
    "userAgent": "Mozilla/5.0...",
    "location": "Bamako, Mali",
    "timestamp": "2026-02-12T10:30:00"
  }
]
```

---

### 2. Logs par Utilisateur

**Endpoint:** `GET /admin/audit/logs/user/{userId}`

**Description:** Récupère tous les logs pour un utilisateur spécifique.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/audit/logs/user/user-123 \
  -H "Authorization: Bearer {admin_token}"
```

---

### 3. Logs par Téléphone

**Endpoint:** `POST /admin/audit/logs/search`

> Le numéro passe par le **corps** et non par l'URL : une URL est écrite en
> clair dans les journaux d'accès du Gateway, des proxys et de la
> supervision, qui sont hors de portée de l'effacement RGPD.

**Description:** Récupère les logs par numéro de téléphone.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/audit/logs/search \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+22300000000"}' \
  -H "Authorization: Bearer {admin_token}"
```

---

### 4. Tentatives Échouées Récentes

**Endpoint:** `GET /admin/audit/failed-attempts`

**Description:** Liste les tentatives de connexion échouées récentes.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/audit/failed-attempts \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "totalFailedAttempts": 15,
  "last24Hours": 8,
  "topFailedPhones": [
    {
      "phoneNumber": "+22300000000",
      "attempts": 5,
      "lastAttempt": "2026-02-12T10:30:00"
    }
  ]
}
```

---

### 5. Statistiques de Sécurité

**Endpoint:** `GET /admin/audit/security-stats`

**Description:** Obtient des statistiques globales de sécurité.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/audit/security-stats \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "totalLogins": 1250,
  "successfulLogins": 1100,
  "failedLogins": 150,
  "successRate": 88.0,
  "lockedAccounts": 5,
  "activeUsers": 450,
  "period": "last_30_days"
}
```

---

### 6. Nettoyer les Anciens Logs

**Endpoint:** `DELETE /admin/audit/logs/cleanup`

**Description:** Supprime les logs plus anciens que X jours.

```bash
curl -X DELETE "http://localhost:8081/api/v1/auth/admin/audit/logs/cleanup?days=90" \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "message": "Logs supprimés avec succès",
  "deletedCount": 3500,
  "olderThanDays": 90
}
```

---

## 📋 Gestion des Logs (AuthenticationLogController)

### 1. Tous les Logs

**Endpoint:** `GET /admin/logs`

**Description:** Récupère tous les logs d'authentification.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/logs \
  -H "Authorization: Bearer {admin_token}"
```

---

### 2. Logs par User ID

**Endpoint:** `GET /admin/logs/user/{userId}`

**Description:** Récupère les logs pour un utilisateur spécifique.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/logs/user/user-123 \
  -H "Authorization: Bearer {admin_token}"
```

---

### 3. Logs par Téléphone

**Endpoint:** `POST /admin/logs/search/by-phone`

> Numéro dans le **corps** : voir la note ci-dessus.

**Description:** Récupère les logs par numéro de téléphone.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/logs/search/by-phone \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+22300000000"}' \
  -H "Authorization: Bearer {admin_token}"
```

---

### 4. Logs par Type d'Événement et Statut

**Endpoint:** `GET /admin/logs/event/{eventType}/status/{status}`

**Description:** Filtre les logs par type d'événement et statut.

**Types d'événements:** `LOGIN`, `LOGOUT`, `REGISTRATION`, `PASSWORD_RESET`, `OTP_REQUEST`  
**Statuts:** `SUCCESS`, `FAILURE`

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/logs/event/LOGIN/status/FAILURE \
  -H "Authorization: Bearer {admin_token}"
```

---

### 5. Nettoyer les Logs Anciens

**Endpoint:** `DELETE /admin/logs/cleanup`

**Description:** Supprime les logs plus anciens que X jours (défaut: 90).

```bash
curl -X DELETE "http://localhost:8081/api/v1/auth/admin/logs/cleanup?daysToKeep=90" \
  -H "Authorization: Bearer {admin_token}"
```

---

## 🔒 Gestion des Rôles (UserRoleManagementController)

### 1. Assigner un Rôle

**Endpoint:** `POST /admin/roles/assign`

**Description:** Assigne un rôle à un utilisateur.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/roles/assign \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "roleName": "ROLE_MODERATOR",
    "assignedBy": "admin-001"
  }'
```

**Response 200:**
```json
{
  "message": "Rôle assigné avec succès",
  "userId": "user-123",
  "roleName": "ROLE_MODERATOR"
}
```

---

### 2. Révoquer un Rôle

**Endpoint:** `POST /admin/roles/revoke`

**Description:** Révoque un rôle d'un utilisateur.

```bash
curl -X POST http://localhost:8081/api/v1/auth/admin/roles/revoke \
  -H "Authorization: Bearer {admin_token}" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "roleName": "ROLE_MODERATOR",
    "revokedBy": "admin-001"
  }'
```

---

### 3. Obtenir les Rôles d'un Utilisateur

**Endpoint:** `GET /admin/roles/user/{userId}`

**Description:** Liste tous les rôles d'un utilisateur.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/roles/user/user-123 \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
{
  "userId": "user-123",
  "roles": [
    {
      "roleName": "ROLE_USER",
      "assignedAt": "2026-02-01T10:00:00",
      "assignedBy": "system"
    },
    {
      "roleName": "ROLE_MODERATOR",
      "assignedAt": "2026-02-12T11:00:00",
      "assignedBy": "admin-001"
    }
  ]
}
```

---

### 4. Lister Tous les Rôles Disponibles

**Endpoint:** `GET /admin/roles/available`

**Description:** Récupère la liste de tous les rôles disponibles dans le système.

```bash
curl -X GET http://localhost:8081/api/v1/auth/admin/roles/available \
  -H "Authorization: Bearer {admin_token}"
```

**Response 200:**
```json
[
  {
    "roleName": "ROLE_USER",
    "description": "Utilisateur standard"
  },
  {
    "roleName": "ROLE_ADMIN",
    "description": "Administrateur système"
  },
  {
    "roleName": "ROLE_MODERATOR",
    "description": "Modérateur de contenu"
  }
]
```

---

## 🔧 Endpoints Internes (InternalAuthController)

> **⚠️ Ces endpoints sont réservés à la communication inter-services**

### 1. Récupérer un Utilisateur par ID

**Endpoint:** `GET /internal/users/{userId}`

**Description:** Récupère les données d'un utilisateur (utilisé par user-service).

```bash
curl -X GET http://localhost:8082/auth/internal/users/user-123 \
  -H "X-Internal-Service: user-service"
```

---

### 2. Supprimer un Utilisateur

**Endpoint:** `DELETE /internal/users/{userId}`

**Description:** Supprime un utilisateur de auth-service (suppression en cascade).

```bash
curl -X DELETE http://localhost:8082/auth/internal/users/user-123 \
  -H "X-Internal-Service: user-service"
```

---

## 📝 Notes Importantes

1. **Authentification Admin :** Tous les endpoints admin nécessitent un token JWT avec le rôle `ROLE_ADMIN`.

2. **Rate Limiting :** Les endpoints admin ont des limites de taux plus élevées mais restent protégés.

3. **Audit Trail :** Toutes les actions admin sont loggées dans `authentication_logs` avec le type d'événement approprié.

4. **Sécurité :** Les endpoints internes ne doivent être accessibles que depuis le réseau interne (pas via le Gateway public).

5. **Pagination :** Certains endpoints supportent la pagination via les paramètres `page` et `size` (non documenté ici pour simplicité).
