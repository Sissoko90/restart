# 🧪 Tests cURL - Système de Gestion Dynamique des Documents

## Variables d'environnement
```bash
export GATEWAY_URL="http://localhost:8080"
export MEDIA_URL="http://localhost:8087"
export ADMIN_TOKEN="eyJhbGciOiJSUzI1NiJ9..." # Token JWT admin
export USER_TOKEN="eyJhbGciOiJSUzI1NiJ9..."   # Token JWT utilisateur
```

---

## 📋 1. ADMIN - Gestion des Types de Documents

### 1.1 Créer un type de document - Pièce d'identité
```bash
curl -X POST "${MEDIA_URL}/admin/document-types" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "NATIONAL_ID",
    "displayName": "Pièce d'\''identité nationale",
    "description": "Carte nationale d'\''identité ou passeport",
    "category": "IDENTITY",
    "requiresNumber": true,
    "requiresExpirationDate": true,
    "requiresCategory": false,
    "defaultValidityYears": 5,
    "acceptedMimeTypes": ["image/jpeg", "image/png", "application/pdf"],
    "maxFileSizeMB": 10
  }'
```

### 1.2 Créer un type de document - Permis de conduire
```bash
curl -X POST "${MEDIA_URL}/admin/document-types" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "DRIVER_LICENSE",
    "displayName": "Permis de conduire",
    "description": "Permis de conduire valide",
    "category": "VEHICLE",
    "requiresNumber": true,
    "requiresExpirationDate": true,
    "requiresCategory": true,
    "availableCategories": ["A", "B", "C", "D", "E", "F"],
    "categoriesWithoutExpiration": ["A", "B"],
    "defaultValidityYears": 5,
    "acceptedMimeTypes": ["image/jpeg", "image/png", "application/pdf"],
    "maxFileSizeMB": 10
  }'
```

### 1.3 Créer un type de document - Casier judiciaire
```bash
curl -X POST "${MEDIA_URL}/admin/document-types" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "CRIMINAL_RECORD",
    "displayName": "Casier judiciaire",
    "description": "Extrait de casier judiciaire",
    "category": "JUDICIAL",
    "requiresNumber": false,
    "requiresExpirationDate": false,
    "requiresCategory": false,
    "defaultValidityYears": 0,
    "acceptedMimeTypes": ["application/pdf"],
    "maxFileSizeMB": 5
  }'
```

### 1.4 Créer un type de document - RCCM
```bash
curl -X POST "${MEDIA_URL}/admin/document-types" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "RCCM",
    "displayName": "RCCM (Registre de Commerce)",
    "description": "Registre de Commerce et du Crédit Mobilier",
    "category": "BUSINESS",
    "requiresNumber": true,
    "requiresExpirationDate": false,
    "requiresCategory": false,
    "defaultValidityYears": 0,
    "acceptedMimeTypes": ["application/pdf", "image/jpeg", "image/png"],
    "maxFileSizeMB": 10
  }'
```

### 1.5 Lister tous les types de documents
```bash
curl -X GET "${MEDIA_URL}/admin/document-types" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 1.6 Lister uniquement les types actifs
```bash
curl -X GET "${MEDIA_URL}/admin/document-types?activeOnly=true" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 1.7 Lister par catégorie
```bash
curl -X GET "${MEDIA_URL}/admin/document-types?category=IDENTITY" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 1.8 Récupérer un type par code
```bash
curl -X GET "${MEDIA_URL}/admin/document-types/code/NATIONAL_ID" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 1.9 Modifier un type de document
```bash
curl -X PUT "${MEDIA_URL}/admin/document-types/{documentTypeId}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "code": "NATIONAL_ID",
    "displayName": "Pièce d'\''identité nationale (Modifié)",
    "description": "Carte nationale d'\''identité ou passeport valide",
    "category": "IDENTITY",
    "requiresNumber": true,
    "requiresExpirationDate": true,
    "requiresCategory": false,
    "defaultValidityYears": 10,
    "acceptedMimeTypes": ["image/jpeg", "image/png", "application/pdf"],
    "maxFileSizeMB": 15
  }'
```

### 1.10 Supprimer (désactiver) un type de document
```bash
curl -X DELETE "${MEDIA_URL}/admin/document-types/{documentTypeId}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

---

## 🔗 2. ADMIN - Affectation Documents ↔ Rôles

### 2.1 Assigner NATIONAL_ID au rôle DRIVER (obligatoire)
```bash
curl -X POST "${MEDIA_URL}/admin/document-types/NATIONAL_ID/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "DRIVER",
    "mandatory": true,
    "displayOrder": 1
  }'
```

### 2.2 Assigner DRIVER_LICENSE au rôle DRIVER (obligatoire)
```bash
curl -X POST "${MEDIA_URL}/admin/document-types/DRIVER_LICENSE/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "DRIVER",
    "mandatory": true,
    "displayOrder": 2
  }'
```

### 2.3 Assigner CRIMINAL_RECORD au rôle DRIVER (optionnel)
```bash
curl -X POST "${MEDIA_URL}/admin/document-types/CRIMINAL_RECORD/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "DRIVER",
    "mandatory": false,
    "displayOrder": 3
  }'
```

### 2.4 Assigner NATIONAL_ID au rôle DELIVERY (obligatoire)
```bash
curl -X POST "${MEDIA_URL}/admin/document-types/NATIONAL_ID/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "DELIVERY",
    "mandatory": true,
    "displayOrder": 1
  }'
```

### 2.5 Assigner DRIVER_LICENSE au rôle DELIVERY (optionnel)
```bash
curl -X POST "${MEDIA_URL}/admin/document-types/DRIVER_LICENSE/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "DELIVERY",
    "mandatory": false,
    "displayOrder": 2
  }'
```

### 2.6 Assigner RCCM au rôle PHARMACY (optionnel)
```bash
curl -X POST "${MEDIA_URL}/admin/document-types/RCCM/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "PHARMACY",
    "mandatory": false,
    "displayOrder": 1
  }'
```

### 2.7 Modifier une affectation (passer de obligatoire à optionnel)
```bash
curl -X PUT "${MEDIA_URL}/admin/document-types/DRIVER_LICENSE/roles/DELIVERY" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "userRole": "DELIVERY",
    "mandatory": true,
    "displayOrder": 2
  }'
```

### 2.8 Retirer un document d'un rôle
```bash
curl -X DELETE "${MEDIA_URL}/admin/document-types/CRIMINAL_RECORD/roles/DRIVER" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 2.9 Lister tous les documents d'un rôle
```bash
curl -X GET "${MEDIA_URL}/admin/document-types/roles/DRIVER" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 2.10 Lister uniquement les documents obligatoires d'un rôle
```bash
curl -X GET "${MEDIA_URL}/admin/document-types/roles/DRIVER?mandatoryOnly=true" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 2.11 Lister uniquement les documents optionnels d'un rôle
```bash
curl -X GET "${MEDIA_URL}/admin/document-types/roles/DRIVER?optionalOnly=true" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 2.12 Lister tous les rôles assignés à un document
```bash
curl -X GET "${MEDIA_URL}/admin/document-types/NATIONAL_ID/roles" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

---

## 👤 3. UTILISATEUR - Consultation des Exigences

### 3.1 Voir tous les documents requis pour mon rôle
```bash
curl -X GET "${GATEWAY_URL}/media/requirements" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

### 3.2 Voir tous les documents requis pour un rôle spécifique
```bash
curl -X GET "${GATEWAY_URL}/media/requirements?userRole=DRIVER" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

### 3.3 Voir uniquement les documents manquants
```bash
curl -X GET "${GATEWAY_URL}/media/user/status" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

### 3.4 Voir les documents manquants pour un rôle spécifique
```bash
curl -X GET "${GATEWAY_URL}/media/user/status?userRole=DELIVERY" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

---

## 📤 4. UTILISATEUR - Upload de Documents

### 4.1 Upload Pièce d'identité (avec numéro et expiration)
```bash
curl -X POST "${GATEWAY_URL}/media/upload" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -F "file=@/path/to/national_id.jpg" \
  -F "documentTypeCode=NATIONAL_ID" \
  -F "documentNumber=CD123456789" \
  -F "expiryDate=2029-12-31" \
  -F "description=Carte d'identité nationale"
```

### 4.2 Upload Permis de conduire catégorie A (sans expiration)
```bash
curl -X POST "${GATEWAY_URL}/media/upload" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -F "file=@/path/to/driver_license.pdf" \
  -F "documentTypeCode=DRIVER_LICENSE" \
  -F "documentNumber=DL987654321" \
  -F "documentCategory=A" \
  -F "description=Permis moto catégorie A"
```

### 4.3 Upload Permis de conduire catégorie B (sans expiration)
```bash
curl -X POST "${GATEWAY_URL}/media/upload" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -F "file=@/path/to/driver_license.pdf" \
  -F "documentTypeCode=DRIVER_LICENSE" \
  -F "documentNumber=DL987654321" \
  -F "documentCategory=B" \
  -F "description=Permis voiture catégorie B"
```

### 4.4 Upload Permis de conduire catégorie C (avec expiration)
```bash
curl -X POST "${GATEWAY_URL}/media/upload" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -F "file=@/path/to/driver_license.pdf" \
  -F "documentTypeCode=DRIVER_LICENSE" \
  -F "documentNumber=DL987654321" \
  -F "documentCategory=C" \
  -F "expiryDate=2028-06-30" \
  -F "description=Permis poids lourd catégorie C"
```

### 4.5 Upload Casier judiciaire (sans numéro ni expiration)
```bash
curl -X POST "${GATEWAY_URL}/media/upload" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -F "file=@/path/to/criminal_record.pdf" \
  -F "documentTypeCode=CRIMINAL_RECORD" \
  -F "description=Extrait de casier judiciaire"
```

### 4.6 Upload RCCM (avec numéro, sans expiration)
```bash
curl -X POST "${GATEWAY_URL}/media/upload" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -F "file=@/path/to/rccm.pdf" \
  -F "documentTypeCode=RCCM" \
  -F "documentNumber=RCCM/KIN/2024/12345" \
  -F "description=Registre de commerce"
```

---

## 📥 5. UTILISATEUR - Gestion des Documents

### 5.1 Lister mes documents
```bash
curl -X GET "${GATEWAY_URL}/media/user/documents" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

### 5.2 Voir les détails d'un document
```bash
curl -X GET "${GATEWAY_URL}/media/{documentId}" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

### 5.3 Télécharger un document
```bash
curl -X GET "${GATEWAY_URL}/media/download/{documentId}" \
  -H "Authorization: Bearer ${USER_TOKEN}" \
  -o downloaded_document.pdf
```

### 5.4 Supprimer un document
```bash
curl -X DELETE "${GATEWAY_URL}/media/{documentId}" \
  -H "Authorization: Bearer ${USER_TOKEN}"
```

---

## 👨‍💼 6. ADMIN - Gestion des Documents Utilisateurs

### 6.1 Voir tous les documents en attente de validation
```bash
curl -X GET "${MEDIA_URL}/admin/documents/pending" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 6.2 Voir tous les documents
```bash
curl -X GET "${MEDIA_URL}/admin/documents/all" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 6.3 Filtrer par statut
```bash
curl -X GET "${MEDIA_URL}/admin/documents/all?status=PENDING" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 6.4 Filtrer par utilisateur
```bash
curl -X GET "${MEDIA_URL}/admin/documents/all?userId={userId}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 6.5 Approuver un document
```bash
curl -X POST "${MEDIA_URL}/admin/documents/{documentId}/approve" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

### 6.6 Rejeter un document
```bash
curl -X POST "${MEDIA_URL}/admin/documents/{documentId}/reject" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "reason": "Photo floue, document illisible"
  }'
```

### 6.7 Voir les statistiques
```bash
curl -X GET "${MEDIA_URL}/admin/documents/stats" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}"
```

---

## 🧪 7. Scénarios de Test Complets

### Scénario 1 : Configuration complète pour DRIVER
```bash
# 1. Créer les types de documents
curl -X POST "${MEDIA_URL}/admin/document-types" -H "Authorization: Bearer ${ADMIN_TOKEN}" -H "Content-Type: application/json" -d '{"code":"NATIONAL_ID","displayName":"Pièce d'\''identité","category":"IDENTITY","requiresNumber":true,"requiresExpirationDate":true,"requiresCategory":false,"defaultValidityYears":5,"acceptedMimeTypes":["image/jpeg","image/png","application/pdf"],"maxFileSizeMB":10}'

curl -X POST "${MEDIA_URL}/admin/document-types" -H "Authorization: Bearer ${ADMIN_TOKEN}" -H "Content-Type: application/json" -d '{"code":"DRIVER_LICENSE","displayName":"Permis de conduire","category":"VEHICLE","requiresNumber":true,"requiresExpirationDate":true,"requiresCategory":true,"availableCategories":["A","B","C","D","E","F"],"categoriesWithoutExpiration":["A","B"],"defaultValidityYears":5,"acceptedMimeTypes":["image/jpeg","image/png","application/pdf"],"maxFileSizeMB":10}'

# 2. Assigner au rôle DRIVER
curl -X POST "${MEDIA_URL}/admin/document-types/NATIONAL_ID/roles" -H "Authorization: Bearer ${ADMIN_TOKEN}" -H "Content-Type: application/json" -d '{"userRole":"DRIVER","mandatory":true,"displayOrder":1}'

curl -X POST "${MEDIA_URL}/admin/document-types/DRIVER_LICENSE/roles" -H "Authorization: Bearer ${ADMIN_TOKEN}" -H "Content-Type: application/json" -d '{"userRole":"DRIVER","mandatory":true,"displayOrder":2}'

# 3. Vérifier les exigences
curl -X GET "${GATEWAY_URL}/media/requirements?userRole=DRIVER" -H "Authorization: Bearer ${USER_TOKEN}"

# 4. Upload des documents
curl -X POST "${GATEWAY_URL}/media/upload" -H "Authorization: Bearer ${USER_TOKEN}" -F "file=@id.jpg" -F "documentTypeCode=NATIONAL_ID" -F "documentNumber=CD123456" -F "expiryDate=2029-12-31"

curl -X POST "${GATEWAY_URL}/media/upload" -H "Authorization: Bearer ${USER_TOKEN}" -F "file=@license.pdf" -F "documentTypeCode=DRIVER_LICENSE" -F "documentNumber=DL987654" -F "documentCategory=B"

# 5. Vérifier les documents manquants
curl -X GET "${GATEWAY_URL}/media/user/status" -H "Authorization: Bearer ${USER_TOKEN}"
```

### Scénario 2 : Test catégories permis avec/sans expiration
```bash
# Upload permis catégorie A (pas d'expiration requise)
curl -X POST "${GATEWAY_URL}/media/upload" -H "Authorization: Bearer ${USER_TOKEN}" -F "file=@license_a.pdf" -F "documentTypeCode=DRIVER_LICENSE" -F "documentNumber=DL111" -F "documentCategory=A"

# Upload permis catégorie C (expiration requise)
curl -X POST "${GATEWAY_URL}/media/upload" -H "Authorization: Bearer ${USER_TOKEN}" -F "file=@license_c.pdf" -F "documentTypeCode=DRIVER_LICENSE" -F "documentNumber=DL222" -F "documentCategory=C" -F "expiryDate=2028-12-31"
```

---

## 📊 8. Vérifications et Validations

### 8.1 Vérifier que le seeder a fonctionné
```bash
# Doit retourner tous les types de documents initialisés
curl -X GET "${MEDIA_URL}/admin/document-types" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq '.[] | {code, displayName, category}'
```

### 8.2 Vérifier les affectations par rôle
```bash
# DRIVER
curl -X GET "${MEDIA_URL}/admin/document-types/roles/DRIVER" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq

# DELIVERY
curl -X GET "${MEDIA_URL}/admin/document-types/roles/DELIVERY" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq

# PHARMACY
curl -X GET "${MEDIA_URL}/admin/document-types/roles/PHARMACY" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq
```

### 8.3 Vérifier la progression utilisateur
```bash
# Avant upload
curl -X GET "${GATEWAY_URL}/media/user/status" -H "Authorization: Bearer ${USER_TOKEN}" | jq '{totalRequired, completed, pending, completionPercentage}'

# Après upload
curl -X GET "${GATEWAY_URL}/media/user/status" -H "Authorization: Bearer ${USER_TOKEN}" | jq '{totalRequired, completed, pending, completionPercentage}'
```

---

## 🎯 Notes Importantes

1. **Tokens JWT** : Remplacez `${ADMIN_TOKEN}` et `${USER_TOKEN}` par de vrais tokens obtenus via `/auth/login`

2. **IDs dynamiques** : Remplacez `{documentId}`, `{documentTypeId}`, `{userId}` par les IDs réels retournés par les endpoints

3. **Fichiers** : Remplacez `/path/to/file.jpg` par le chemin réel vers vos fichiers de test

4. **Gateway vs Direct** : 
   - Utilisateurs → `${GATEWAY_URL}` (port 8080)
   - Admin → `${MEDIA_URL}` (port 8087) ou via gateway

5. **Format dates** : Utilisez le format ISO `YYYY-MM-DD` pour les dates d'expiration

6. **Catégories permis** :
   - A, B → Pas d'expiration requise
   - C, D, E, F → Expiration requise
