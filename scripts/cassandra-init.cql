-- ==========================================================
-- Schéma Cassandra de quotas-service
-- ==========================================================
-- Le keyspace ne peut pas être créé par Spring Boot : il doit exister
-- avant le premier démarrage du service.
--
--   docker exec -i cassandra cqlsh < scripts/cassandra-init.cql
--
-- Les TABLES, elles, sont créées automatiquement par Spring Data
-- (spring.cassandra.schema-action: create-if-not-exists).
-- Elles sont reproduites ici à titre de documentation du modèle.
-- ==========================================================

CREATE KEYSPACE IF NOT EXISTS quotas_keyspace
WITH replication = {
  'class': 'SimpleStrategy',
  'replication_factor': 1   -- dev uniquement : passer en NetworkTopologyStrategy en production
};

USE quotas_keyspace;

-- Palier commercial de l'utilisateur.
-- Les limites chiffrées ne sont PAS stockées ici : elles vivent dans
-- config-repo/dev/quotas-service-dev.yml (quotas.free|premium|enterprise).
CREATE TABLE IF NOT EXISTS user_quota (
    user_id     text PRIMARY KEY,
    tier        text,
    createdat   timestamp,
    updatedat   timestamp
);

-- Consommation courante, une ligne par utilisateur.
-- api_calls_count est relatif à api_calls_window_start (fenêtre d'une heure) :
-- dès que l'heure change, le compteur repart de zéro côté applicatif.
CREATE TABLE IF NOT EXISTS user_consumption (
    user_id                 text PRIMARY KEY,
    projects_count          bigint,
    collaborators_count     bigint,
    storage_mb_used         bigint,
    api_calls_count         bigint,
    api_calls_window_start  timestamp,
    updatedat               timestamp
);
