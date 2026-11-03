-- =====================================================
-- Script de création de la table projects
-- Interface-service : Gestion des projets de base uniquement
-- =====================================================

-- Table des projets (informations de base uniquement)
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(50) NOT NULL,
    description VARCHAR(500),
    user_id UUID NOT NULL,
    
    -- Audit fields (BaseEntity)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version BIGINT DEFAULT 0,
    is_active BOOLEAN DEFAULT TRUE,
    
    -- Contraintes
    CONSTRAINT fk_projects_user_id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_projects_name_user UNIQUE (name, user_id, is_active)
);

-- Index pour les performances
CREATE INDEX idx_projects_user_id ON projects(user_id) WHERE is_active = TRUE;
CREATE INDEX idx_projects_name ON projects(name) WHERE is_active = TRUE;
CREATE INDEX idx_projects_created_at ON projects(created_at);
CREATE INDEX idx_projects_user_created ON projects(user_id, created_at DESC) WHERE is_active = TRUE;

-- Trigger pour updated_at automatique
CREATE TRIGGER update_projects_updated_at 
    BEFORE UPDATE ON projects 
    FOR EACH ROW 
    EXECUTE FUNCTION update_updated_at_column();

-- Commentaires
COMMENT ON TABLE projects IS 'Table des projets de base - Interface-service (configurations gérées par setting-service)';
COMMENT ON COLUMN projects.id IS 'Identifiant unique UUID du projet';
COMMENT ON COLUMN projects.name IS 'Nom du projet (3-50 caractères)';
COMMENT ON COLUMN projects.description IS 'Description du projet (max 500 caractères)';
COMMENT ON COLUMN projects.user_id IS 'Référence vers l''utilisateur propriétaire';
COMMENT ON COLUMN projects.version IS 'Version pour optimistic locking';
COMMENT ON COLUMN projects.is_active IS 'Projet actif (soft delete)';

-- Contrainte de validation pour le nom du projet
ALTER TABLE projects ADD CONSTRAINT chk_projects_name_length 
    CHECK (LENGTH(TRIM(name)) >= 3 AND LENGTH(TRIM(name)) <= 50);

-- Contrainte de validation pour la description
ALTER TABLE projects ADD CONSTRAINT chk_projects_description_length 
    CHECK (description IS NULL OR LENGTH(description) <= 500);
