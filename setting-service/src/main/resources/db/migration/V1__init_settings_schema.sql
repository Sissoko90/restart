-- ==========================================================
-- setting-service : schéma initial PostgreSQL
-- ==========================================================
-- Deux niveaux de paramètres :
--   * system_setting : surcharges des paramètres de plateforme.
--     La table ne contient QUE les valeurs modifiées ; les valeurs par
--     défaut vivent dans config-repo/dev/setting-service-dev.yml.
--     Réinitialiser un paramètre = supprimer sa ligne.
--   * user_setting : préférences propres à chaque utilisateur, clés libres.
-- ==========================================================

CREATE TABLE IF NOT EXISTS system_setting (
    setting_key    VARCHAR(100) PRIMARY KEY,
    setting_value  TEXT         NOT NULL,
    category       VARCHAR(30)  NOT NULL,
    updated_by     VARCHAR(100),
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_system_setting_category ON system_setting (category);

CREATE TABLE IF NOT EXISTS user_setting (
    id             VARCHAR(36)  PRIMARY KEY,
    user_id        VARCHAR(36)  NOT NULL,
    setting_key    VARCHAR(100) NOT NULL,
    setting_value  TEXT         NOT NULL,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Une seule valeur par couple (utilisateur, clé)
    CONSTRAINT uk_user_setting UNIQUE (user_id, setting_key)
);
CREATE INDEX IF NOT EXISTS idx_user_setting_user ON user_setting (user_id);
