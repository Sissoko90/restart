#!/bin/bash

# ==========================================
# TESTS DE SÉCURITÉ ADMIN ET MAINTENANCE
# ==========================================
# Ce script contient des requêtes curl pour tester tous les scénarios de sécurité
# pour l'admin et la maintenance (blocages, tentatives, etc.)

# Configuration
ADMIN_SECRET_KEY="your_admin_secret_key_from_env"
MAINTENANCE_SECRET_KEY="your_maintenance_secret_key_from_env"
INTERFACE_URL="http://localhost:8088"
ADMIN_EMAIL="makenzyks6@gmail.com"
ADMIN_PHONE="+221774567890"
ADMIN_PASSWORD="AdminSecure123!"
RECOVERY_SECRET="RecoverySecret123"

# ==========================================
# SCÉNARIOS DE LOGIN ADMIN
# ==========================================

echo "=== SCÉNARIO 1: Login admin avec mot de passe correct ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "'$ADMIN_PASSWORD'",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 2: Login admin avec mot de passe incorrect (1ère tentative - alerte email) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 3: Login admin avec mot de passe incorrect (2ème tentative) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 4: Login admin avec mot de passe incorrect (3ème tentative) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 5: Login admin avec mot de passe incorrect (4ème tentative - blocage 3min) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 6: Login admin avec mot de passe incorrect (5ème tentative) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 7: Login admin avec mot de passe incorrect (6ème tentative) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 8: Login admin avec mot de passe incorrect (7ème tentative - blocage 10min) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 9: Login admin avec mot de passe incorrect (8ème tentative - blocage définitif) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

# ==========================================
# SCÉNARIOS DE CONFIRMATION ADMIN
# ==========================================

echo -e "\n=== SCÉNARIO 10: Confirmer que c'est l'admin (après tentative incorrecte) ==="
# Remplacer TOKEN par le token reçu dans l'email de confirmation
curl -X POST "$INTERFACE_URL/admin/confirm-admin" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "TOKEN_FROM_EMAIL",
    "confirm": true
  }' | jq .

echo -e "\n=== SCÉNARIO 11: Bloquer l'IP (ce n'est pas l'admin) ==="
# Remplacer TOKEN par le token reçu dans l'email de confirmation
curl -X POST "$INTERFACE_URL/admin/confirm-admin" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "TOKEN_FROM_EMAIL",
    "confirm": false
  }' | jq .

# ==========================================
# SCÉNARIOS DE MAINTENANCE
# ==========================================

echo -e "\n=== SCÉNARIO 12: Obtenir un token JWT de maintenance ==="
curl -X POST "$INTERFACE_URL/maintenance/auth" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -H "X-Maintenance-Identifier: $MAINTENANCE_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "'$ADMIN_PASSWORD'",
    "totpCode": "123456"
  }' | jq .

# Remplacer MAINTENANCE_TOKEN par le token reçu
MAINTENANCE_TOKEN="YOUR_MAINTENANCE_TOKEN"

echo -e "\n=== SCÉNARIO 13: Accès maintenance sans token JWT (doit échouer) ==="
curl -X GET "$INTERFACE_URL/maintenance/security-audit" \
  -H "Content-Type: application/json" | jq .

echo -e "\n=== SCÉNARIO 14: Accès maintenance avec token JWT valide ==="
curl -X GET "$INTERFACE_URL/maintenance/security-audit" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" | jq .

echo -e "\n=== SCÉNARIO 15: Accès maintenance avec token JWT invalide (doit échouer) ==="
curl -X GET "$INTERFACE_URL/maintenance/security-audit" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer invalid_token" | jq .

echo -e "\n=== SCÉNARIO 16: Changer les identifiants admin (avec recovery secret) ==="
curl -X POST "$INTERFACE_URL/maintenance/change-credentials" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" \
  -d '{
    "recoverySecret": "'$RECOVERY_SECRET'",
    "email": "'$ADMIN_EMAIL'",
    "phoneNumber": "'$ADMIN_PHONE'",
    "newPassword": "NewAdminPassword123!"
  }' | jq .

echo -e "\n=== SCÉNARIO 17: Changer les identifiants admin avec recovery secret incorrect (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/change-credentials" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" \
  -d '{
    "recoverySecret": "WrongRecoverySecret",
    "email": "'$ADMIN_EMAIL'",
    "phoneNumber": "'$ADMIN_PHONE'",
    "newPassword": "NewAdminPassword123!"
  }' | jq .

echo -e "\n=== SCÉNARIO 18: Obtenir la configuration TOTP admin ==="
curl -X GET "$INTERFACE_URL/maintenance/totp-config" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" | jq .

echo -e "\n=== SCÉNARIO 19: Régénérer le TOTP admin ==="
curl -X POST "$INTERFACE_URL/maintenance/regenerate-totp" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" \
  -d '{
    "password": "'$ADMIN_PASSWORD'"
  }' | jq .

echo -e "\n=== SCÉNARIO 20: Régénérer le TOTP admin avec mot de passe incorrect (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/regenerate-totp" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" \
  -d '{
    "password": "WrongPassword123!"
  }' | jq .

echo -e "\n=== SCÉNARIO 21: Audit de sécurité admin ==="
curl -X GET "$INTERFACE_URL/maintenance/security-audit" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" | jq .

echo -e "\n=== SCÉNARIO 22: Débloquer le compte admin ==="
curl -X POST "$INTERFACE_URL/maintenance/unlock-account" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $MAINTENANCE_TOKEN" | jq .

# ==========================================
# SCÉNARIOS DE BRUTE FORCE (POST-CONFIRMATION)
# ==========================================

echo -e "\n=== SCÉNARIO 23: Tentatives après confirmation admin (1ère tentative) ==="
# Simuler une tentative après confirmation de l'admin
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 24: Tentatives après confirmation admin (2ème tentative) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 25: Tentatives après confirmation admin (3ème tentative - email critique) ==="
curl -X POST "$INTERFACE_URL/admin/login" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

# ==========================================
# SCÉNARIOS DE SÉCURITÉ DES ENDPOINTS ADMIN
# ==========================================

echo -e "\n=== SCÉNARIO 26: Accès endpoint admin avec clé API valide ==="
curl -X POST "$INTERFACE_URL/admin/change-credentials" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "recoverySecret": "'$RECOVERY_SECRET'",
    "email": "'$ADMIN_EMAIL'",
    "phoneNumber": "'$ADMIN_PHONE'",
    "newPassword": "NewAdminPassword456!"
  }' | jq .

echo -e "\n=== SCÉNARIO 27: Accès endpoint admin sans clé API (doit échouer) ==="
curl -X POST "$INTERFACE_URL/admin/change-credentials" \
  -H "Content-Type: application/json" \
  -d '{
    "recoverySecret": "'$RECOVERY_SECRET'",
    "email": "'$ADMIN_EMAIL'",
    "phoneNumber": "'$ADMIN_PHONE'",
    "newPassword": "NewAdminPassword456!"
  }' | jq .

echo -e "\n=== SCÉNARIO 28: Accès endpoint admin avec clé API incorrecte (doit échouer) ==="
curl -X POST "$INTERFACE_URL/admin/change-credentials" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: wrong_secret_key" \
  -d '{
    "recoverySecret": "'$RECOVERY_SECRET'",
    "email": "'$ADMIN_EMAIL'",
    "phoneNumber": "'$ADMIN_PHONE'",
    "newPassword": "NewAdminPassword456!"
  }' | jq .

# ==========================================
# SCÉNARIOS DE MAINTENANCE AUTH
# ==========================================

echo -e "\n=== SCÉNARIO 29: Auth maintenance sans X-Admin-Secret (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/auth" \
  -H "Content-Type: application/json" \
  -H "X-Maintenance-Identifier: $MAINTENANCE_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "'$ADMIN_PASSWORD'",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 30: Auth maintenance sans X-Maintenance-Identifier (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/auth" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "'$ADMIN_PASSWORD'",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 31: Auth maintenance avec clé dure incorrecte (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/auth" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -H "X-Maintenance-Identifier: wrong_maintenance_key" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "'$ADMIN_PASSWORD'",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 32: Auth maintenance avec mot de passe incorrect (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/auth" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -H "X-Maintenance-Identifier: $MAINTENANCE_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "WrongPassword123!",
    "totpCode": "123456"
  }' | jq .

echo -e "\n=== SCÉNARIO 33: Auth maintenance avec TOTP incorrect (doit échouer) ==="
curl -X POST "$INTERFACE_URL/maintenance/auth" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Secret: $ADMIN_SECRET_KEY" \
  -H "X-Maintenance-Identifier: $MAINTENANCE_SECRET_KEY" \
  -d '{
    "phoneNumber": "'$ADMIN_PHONE'",
    "password": "'$ADMIN_PASSWORD'",
    "totpCode": "000000"
  }' | jq .

# ==========================================
# SCÉNARIOS DE RÉCUPÉRATION DE SECRET
# ==========================================

echo -e "\n=== SCÉNARIO 34: Test de régénération automatique du recovery secret (scheduled task) ==="
# Ce scénario nécessite d'attendre 24h ou de modifier la configuration du scheduler
echo "Vérifiez les logs pour confirmer que la tâche scheduled exécute la régénération"
echo "Vérifiez l'email admin pour le nouveau recovery secret chiffré"

# ==========================================
# FIN DES TESTS
# ==========================================

echo -e "\n=== FIN DES TESTS DE SÉCURITÉ ==="
echo "Vérifiez les logs de l'application pour les alertes de sécurité"
echo "Vérifiez les emails envoyés (admin et critique)"
