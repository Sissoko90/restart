-- ==========================================================
-- Bases de données de la plateforme
-- ==========================================================
-- Exécuté automatiquement au PREMIER démarrage du conteneur
-- PostgreSQL (docker-entrypoint-initdb.d). Si le volume existe
-- déjà, ce script n'est PAS rejoué : supprimer le volume pour
-- repartir de zéro.
--
-- Les schémas eux-mêmes sont créés par Flyway au démarrage de
-- chaque service ; ici on ne crée que les bases.
-- ==========================================================

CREATE DATABASE auth_service_db;
CREATE DATABASE interface_db;
CREATE DATABASE user_service_db;
CREATE DATABASE setting_service_db;

-- Base de la politique de sécurité.
--
-- SEPAREE de setting_service_db, et pas par habitude : les deux services ont
-- des droits d'accès et des cycles de vie distincts, et c'est la raison pour
-- laquelle la migration qui a déplacé la politique hors de setting-service ne
-- pouvait pas copier les surcharges existantes — aucune requête SQL ne traverse
-- deux bases.
CREATE DATABASE security_service_db;
