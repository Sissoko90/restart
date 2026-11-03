-- Migration pour ajouter la colonne role à la table users
-- Version: V3
-- Description: Ajouter la colonne role avec DEVELOPER comme valeur par défaut

-- Ajouter la colonne role avec une valeur par défaut
ALTER TABLE users ADD COLUMN role VARCHAR(50) DEFAULT 'DEVELOPER' NOT NULL;

-- Mettre à jour l'utilisateur admin existant pour avoir le rôle ADMIN
-- (si il existe déjà avec l'email admin@abdaty.com ou makenzyks6@gmail.com)
UPDATE users 
SET role = 'ADMIN' 
WHERE email IN ('admin@abdaty.com', 'makenzyks6@gmail.com') 
   OR phone_number = '+22397758697';

-- Ajouter un index sur la colonne role pour les performances
CREATE INDEX idx_users_role ON users(role);

-- Ajouter un commentaire sur la colonne
COMMENT ON COLUMN users.role IS 'Rôle de l''utilisateur: ADMIN, DEVELOPER, USER, etc.';
