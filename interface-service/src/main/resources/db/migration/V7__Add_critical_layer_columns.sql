-- ==========================================
-- Migration V6 : Ajout des colonnes pour la couche critique de sécurité admin
-- ==========================================
-- Cette migration ajoute les champs nécessaires pour suivre les tentatives après confirmation
-- et gérer l'envoi d'alertes critiques (dernière couche de défense)

-- Ajouter les colonnes pour les tentatives après confirmation
ALTER TABLE users
ADD COLUMN IF NOT EXISTS post_confirmation_attempts INTEGER DEFAULT 0;

ALTER TABLE users
ADD COLUMN IF NOT EXISTS critical_alert_sent BOOLEAN DEFAULT FALSE;

ALTER TABLE users
ADD COLUMN IF NOT EXISTS critical_maintenance_token VARCHAR(255);

ALTER TABLE users
ADD COLUMN IF NOT EXISTS critical_maintenance_expires_at TIMESTAMP;

-- Ajouter un index sur le token de maintenance pour les recherches rapides
CREATE INDEX IF NOT EXISTS idx_users_critical_maintenance_token
ON users(critical_maintenance_token)
WHERE critical_maintenance_token IS NOT NULL;

-- Ajouter un index sur l'IP de confirmation admin pour les recherches rapides
CREATE INDEX IF NOT EXISTS idx_users_admin_confirmation_ip
ON users(admin_confirmation_ip)
WHERE admin_confirmation_ip IS NOT NULL;
