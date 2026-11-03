-- Ajout de la colonne recovery_secret_hash pour permettre la récupération de l'accès admin
-- Ce champ stocke le hash du mot de passe initial du système qui sert de recovery secret
-- Si un attaquant change le mot de passe admin, l'admin légitime peut toujours
-- récupérer l'accès en utilisant ce recovery secret

ALTER TABLE users ADD COLUMN recovery_secret_hash VARCHAR(255);
