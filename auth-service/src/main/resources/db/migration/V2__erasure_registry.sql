-- ==========================================================
-- Registre des demandes d'effacement (RGPD art. 17 et 5.2)
-- ==========================================================
-- L'article 5.2 impose de pouvoir DÉMONTRER la conformité : avoir
-- effacé ne suffit pas, il faut pouvoir l'établir. Ce registre trace
-- chaque demande et l'accusé de chaque service.
--
-- Il ne contient AUCUNE donnée personnelle : seulement l'identifiant
-- technique du porteur, qui devient un jeton opaque une fois les
-- champs identifiants écrasés dans les tables métier. Conserver ici
-- le nom ou l'adresse de la personne annulerait l'effacement qu'on
-- prétend prouver.
-- ==========================================================

CREATE TABLE IF NOT EXISTS erasure_requests (
    id             VARCHAR(36)  PRIMARY KEY,
    subject_id     VARCHAR(36)  NOT NULL,
    reason         VARCHAR(40)  NOT NULL,
    requested_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    requested_by   VARCHAR(36),
    completed_at   TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP
);

-- Une personne peut redemander l'effacement ; on garde l'historique.
CREATE INDEX IF NOT EXISTS idx_erasure_requests_subject
    ON erasure_requests (subject_id);

-- Les demandes non terminées sont celles à reprendre.
CREATE INDEX IF NOT EXISTS idx_erasure_requests_pending
    ON erasure_requests (completed_at)
    WHERE completed_at IS NULL;

-- ----------------------------------------------------------
-- Accusés par service
-- ----------------------------------------------------------
-- Une ligne par service ayant traité la demande. Un service qui ne
-- détient rien accuse tout de même avec records_erased = 0 : c'est ce
-- qui distingue « rien à effacer » de « n'a jamais répondu ».

CREATE TABLE IF NOT EXISTS erasure_acknowledgements (
    id             VARCHAR(36)  PRIMARY KEY,
    request_id     VARCHAR(36)  NOT NULL,
    service_name   VARCHAR(60)  NOT NULL,
    completed_at   TIMESTAMP    NOT NULL,
    records_erased INTEGER      NOT NULL DEFAULT 0,
    details        VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP,

    CONSTRAINT fk_erasure_ack_request
        FOREIGN KEY (request_id) REFERENCES erasure_requests (id) ON DELETE CASCADE,

    -- Un événement peut être livré deux fois : la contrainte rend le
    -- traitement idempotent au lieu d'accumuler des doublons.
    CONSTRAINT uk_erasure_ack_request_service UNIQUE (request_id, service_name)
);

CREATE INDEX IF NOT EXISTS idx_erasure_ack_request
    ON erasure_acknowledgements (request_id);
