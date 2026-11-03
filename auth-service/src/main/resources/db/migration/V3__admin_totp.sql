-- ==========================================================
-- Second facteur pour les comptes d'administration
-- ==========================================================
-- Le compte SUPER_ADMIN est le plus privilegie de la plateforme :
-- maintenance, rotation des cles de signature, effacement
-- administratif. Le proteger par un mot de passe seul, sur un endpoint
-- public, serait disproportionne au pouvoir qu'il confere.
--
-- Le secret est genere par SuperAdminSeeder et remis une seule fois,
-- a l'enrolement. Voir docs/ROLES.md.
-- ==========================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret  VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Les comptes qui en disposent sont rares : un index partiel suffit.
CREATE INDEX IF NOT EXISTS idx_users_totp_enabled
    ON users (totp_enabled)
    WHERE totp_enabled = TRUE;
