-- ==========================================================
-- auth-service : schéma initial PostgreSQL
-- ==========================================================
-- Reprend à l'identique le schéma qu'Hibernate générait sous MySQL
-- (`ddl-auto: update`), en explicite et versionné.
--
-- Deux changements de modélisation liés au passage à R2DBC, qui ne
-- gère ni les relations ni le chargement paresseux :
--   * refresh_tokens.user_id et oauth_accounts.user_id sont désormais
--     de simples colonnes (l'@ManyToOne JPA vers User disparaît) ;
--   * les jointures éventuelles sont explicites côté requête.
-- ==========================================================

-- ----------------------------------------------------------
-- Utilisateurs
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                     VARCHAR(36)  PRIMARY KEY,
    phone_number           VARCHAR(20)  UNIQUE,
    country_code           VARCHAR(5),
    email                  VARCHAR(100) UNIQUE,
    first_name             VARCHAR(50),
    last_name              VARCHAR(50),
    password_hash          VARCHAR(255) NOT NULL,
    is_phone_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    is_locked              BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts  INTEGER      NOT NULL DEFAULT 0,
    locked_until           TIMESTAMP,
    last_login             TIMESTAMP,
    role                   VARCHAR(30)  NOT NULL DEFAULT 'USER',
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_users_phone ON users (phone_number);
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);

-- ----------------------------------------------------------
-- Rôles
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS roles (
    id           VARCHAR(36)  PRIMARY KEY,
    name         VARCHAR(30)  NOT NULL UNIQUE,
    description  VARCHAR(255),
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP
);

-- ----------------------------------------------------------
-- Affectations de rôles (un utilisateur peut en cumuler plusieurs)
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_role_assignments (
    id            VARCHAR(36)  PRIMARY KEY,
    user_id       VARCHAR(36)  NOT NULL,
    email         VARCHAR(100) NOT NULL,
    phone_number  VARCHAR(20),
    role          VARCHAR(30)  NOT NULL,
    is_primary    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    assigned_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by   VARCHAR(36),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP,
    CONSTRAINT uk_email_role UNIQUE (email, role),
    CONSTRAINT uk_phone_role UNIQUE (phone_number, role)
);
CREATE INDEX IF NOT EXISTS idx_user_role_user ON user_role_assignments (user_id);
CREATE INDEX IF NOT EXISTS idx_user_role_email ON user_role_assignments (email);
CREATE INDEX IF NOT EXISTS idx_user_role_phone ON user_role_assignments (phone_number);
CREATE INDEX IF NOT EXISTS idx_user_role_role ON user_role_assignments (role);

-- ----------------------------------------------------------
-- Refresh tokens
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          VARCHAR(36)  PRIMARY KEY,
    user_id     VARCHAR(36)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token       VARCHAR(500) NOT NULL UNIQUE,
    is_revoked  BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMP    NOT NULL,
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    revoked_at  TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_refresh_user ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_token ON refresh_tokens (token);
CREATE INDEX IF NOT EXISTS idx_refresh_expires ON refresh_tokens (expires_at);

-- ----------------------------------------------------------
-- Codes de vérification (OTP)
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS verification_codes (
    id            VARCHAR(36)  PRIMARY KEY,
    phone_number  VARCHAR(20)  NOT NULL,
    email         VARCHAR(100),
    code          VARCHAR(6)   NOT NULL,
    first_name    VARCHAR(50),
    last_name     VARCHAR(50),
    country_code  VARCHAR(5),
    type          VARCHAR(30)  NOT NULL,
    is_used       BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts      INTEGER      NOT NULL DEFAULT 0,
    expires_at    TIMESTAMP    NOT NULL,
    used_at       TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_verification_phone ON verification_codes (phone_number);
CREATE INDEX IF NOT EXISTS idx_verification_code ON verification_codes (code);
CREATE INDEX IF NOT EXISTS idx_verification_expires ON verification_codes (expires_at);

-- ----------------------------------------------------------
-- Comptes OAuth2 liés
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS oauth_accounts (
    id                VARCHAR(36)   PRIMARY KEY,
    user_id           VARCHAR(36)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider          VARCHAR(20)   NOT NULL,
    provider_user_id  VARCHAR(255)  NOT NULL,
    email             VARCHAR(100),
    name              VARCHAR(100),
    picture_url       VARCHAR(2000),
    access_token      VARCHAR(1000),
    refresh_token     VARCHAR(1000),
    token_expires_at  BIGINT,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP,
    CONSTRAINT uk_provider_id UNIQUE (provider, provider_user_id)
);
CREATE INDEX IF NOT EXISTS idx_oauth_user ON oauth_accounts (user_id);
CREATE INDEX IF NOT EXISTS idx_oauth_provider ON oauth_accounts (provider);

-- ----------------------------------------------------------
-- Credentials biométriques (WebAuthn/FIDO2 : clés publiques uniquement)
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS biometric_credentials (
    id                  VARCHAR(36)  PRIMARY KEY,
    user_id             VARCHAR(36)  NOT NULL,
    biometric_type      VARCHAR(20)  NOT NULL,
    credential_id       VARCHAR(255) NOT NULL UNIQUE,
    public_key          TEXT         NOT NULL,
    device_id           VARCHAR(255),
    device_name         VARCHAR(100),
    device_os           VARCHAR(50),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    counter             BIGINT       NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMP,
    registered_at       TIMESTAMP    NOT NULL,
    aaguid              VARCHAR(36),
    attestation_format  VARCHAR(50),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_biometric_user ON biometric_credentials (user_id);
CREATE INDEX IF NOT EXISTS idx_biometric_credential ON biometric_credentials (credential_id);
CREATE INDEX IF NOT EXISTS idx_biometric_device ON biometric_credentials (device_id);

-- ----------------------------------------------------------
-- Journal d'authentification
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS authentication_logs (
    id               VARCHAR(36)  PRIMARY KEY,
    user_id          VARCHAR(36),
    phone_number     VARCHAR(20),
    event_type       VARCHAR(30)  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    ip_address       VARCHAR(45),
    user_agent       TEXT,
    device_info      TEXT,
    browser_family   VARCHAR(50),
    browser_version  VARCHAR(20),
    os_family        VARCHAR(50),
    os_version       VARCHAR(20),
    device_family    VARCHAR(50),
    device_type      VARCHAR(20),
    location         VARCHAR(255),
    country          VARCHAR(100),
    country_code     VARCHAR(2),
    region           VARCHAR(100),
    city             VARCHAR(100),
    latitude         DOUBLE PRECISION,
    longitude        DOUBLE PRECISION,
    timezone         VARCHAR(50),
    isp              VARCHAR(100),
    organization     VARCHAR(100),
    asn              VARCHAR(20),
    error_message    TEXT,
    timestamp        TIMESTAMP    NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_auth_log_user ON authentication_logs (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_log_phone ON authentication_logs (phone_number);
CREATE INDEX IF NOT EXISTS idx_auth_log_timestamp ON authentication_logs (timestamp);
CREATE INDEX IF NOT EXISTS idx_auth_log_event ON authentication_logs (event_type);
CREATE INDEX IF NOT EXISTS idx_auth_log_country ON authentication_logs (country_code);
CREATE INDEX IF NOT EXISTS idx_auth_log_city ON authentication_logs (city);

-- ----------------------------------------------------------
-- Modèles de messages (email / SMS)
-- ----------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_templates (
    id             VARCHAR(36)  PRIMARY KEY,
    template_type  VARCHAR(50)  NOT NULL UNIQUE,
    subject        VARCHAR(200),
    content        TEXT         NOT NULL,
    description    VARCHAR(500),
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    variables      VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_template_type ON message_templates (template_type);
CREATE INDEX IF NOT EXISTS idx_template_active ON message_templates (is_active);
