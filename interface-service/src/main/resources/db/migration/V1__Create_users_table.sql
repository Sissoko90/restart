-- =====================================================
-- Script de création de la table users
-- Interface-service : Gestion des utilisateurs développeurs
-- =====================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Table des utilisateurs développeurs
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    
    -- Vérifications
    email_verified BOOLEAN DEFAULT FALSE,
    phone_number VARCHAR(20),
    phone_verified BOOLEAN DEFAULT FALSE,
    
    -- Sécurité et authentification
    last_login_at TIMESTAMP,
    failed_login_attempts INTEGER DEFAULT 0,
    account_locked_until TIMESTAMP,
    
    -- Tokens
    password_reset_token VARCHAR(255),
    password_reset_expires_at TIMESTAMP,
    email_verification_token VARCHAR(255),
    refresh_token_hash VARCHAR(255),
    
    -- Audit fields (BaseEntity)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE
);

-- Index pour les performances
CREATE INDEX idx_users_email ON users(email) WHERE is_active = TRUE;
CREATE INDEX idx_users_username ON users(username) WHERE is_active = TRUE;
CREATE INDEX idx_users_email_verification_token ON users(email_verification_token) WHERE email_verification_token IS NOT NULL;
CREATE INDEX idx_users_password_reset_token ON users(password_reset_token) WHERE password_reset_token IS NOT NULL;
CREATE INDEX idx_users_created_at ON users(created_at);

-- Trigger pour updated_at automatique
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    NEW.version = OLD.version + 1;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at 
    BEFORE UPDATE ON users 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- Commentaires
COMMENT ON TABLE users IS 'Table des utilisateurs développeurs - Interface-service';
COMMENT ON COLUMN users.id IS 'Identifiant unique UUID';
COMMENT ON COLUMN users.username IS 'Nom d''utilisateur unique';
COMMENT ON COLUMN users.email IS 'Adresse email unique';
COMMENT ON COLUMN users.password_hash IS 'Hash du mot de passe (bcrypt)';
COMMENT ON COLUMN users.email_verified IS 'Email vérifié par token';
COMMENT ON COLUMN users.phone_verified IS 'Téléphone vérifié par OTP';
COMMENT ON COLUMN users.failed_login_attempts IS 'Nombre de tentatives de connexion échouées';
COMMENT ON COLUMN users.account_locked_until IS 'Compte verrouillé jusqu''à cette date';
COMMENT ON COLUMN users.version IS 'Version pour optimistic locking';
COMMENT ON COLUMN users.is_active IS 'Utilisateur actif (soft delete)';
