-- Ajouter les colonnes de confirmation admin pour la sécurité
ALTER TABLE users
ADD COLUMN admin_confirmation_pending BOOLEAN DEFAULT FALSE,
ADD COLUMN admin_confirmation_token VARCHAR(255),
ADD COLUMN admin_confirmation_expires_at TIMESTAMP,
ADD COLUMN admin_confirmation_ip VARCHAR(45);
