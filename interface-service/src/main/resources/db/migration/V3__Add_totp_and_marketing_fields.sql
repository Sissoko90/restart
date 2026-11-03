-- =====================================================
-- Migration V2 : Ajout des champs TOTP et Marketing
-- Interface-service : Support TOTP admin + RGPD
-- =====================================================

-- Ajouter les champs manquants à la table users
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS refresh_token VARCHAR(500),
ADD COLUMN IF NOT EXISTS refresh_token_expires_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS marketing_consent BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(255),
ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN DEFAULT FALSE;

-- Index pour les performances TOTP
CREATE INDEX IF NOT EXISTS idx_users_totp_secret ON users(totp_secret) WHERE totp_secret IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_users_refresh_token ON users(refresh_token) WHERE refresh_token IS NOT NULL;

-- Commentaires pour les nouveaux champs
COMMENT ON COLUMN users.refresh_token IS 'Token de rafraîchissement JWT';
COMMENT ON COLUMN users.refresh_token_expires_at IS 'Expiration du refresh token';
COMMENT ON COLUMN users.marketing_consent IS 'Consentement RGPD pour communications marketing';
COMMENT ON COLUMN users.totp_secret IS 'Secret TOTP pour authentification 2FA (Base32)';
COMMENT ON COLUMN users.totp_enabled IS 'TOTP activé pour cet utilisateur';
