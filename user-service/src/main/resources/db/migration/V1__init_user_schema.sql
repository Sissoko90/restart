-- ============================================================================
-- Schéma initial de user-service
-- ============================================================================
-- Ce fichier n'existait pas, et c'est le défaut le plus grave de ce service :
-- quatre entités, quatre dépôts, vingt-sept endpoints, et AUCUNE table.
--
-- Ni Flyway, ni script d'initialisation : `R2dbcConfig` déclarait bien un
-- `ConnectionFactoryInitializer`, mais son populateur était commenté. Un bean
-- qui existait pour ne rien faire.
--
-- Rien ne le signalait, parce qu'aucun test ne touchait la base — et qu'aucun
-- test ne chargeait même le contexte Spring.
--
-- ----------------------------------------------------------------------------
-- Les identifiants sont produits par la BASE
-- ----------------------------------------------------------------------------
-- `gen_random_uuid()` sur chaque clef primaire, et c'est un choix structurant :
-- les entités laissent leur `id` nul, donc Spring Data R2DBC sait qu'une ligne
-- est neuve et émet un INSERT.
--
-- C'est l'inverse d'auth-service, dont l'application assigne les identifiants
-- et qui a besoin de `Persistable` pour lever l'ambiguïté : là-bas, un
-- identifiant déjà posé faisait émettre un UPDATE pour une ligne jamais
-- insérée, et le `save()` était silencieusement sans effet.
--
-- La règle à tenir ici est donc : ne jamais appeler `setId()` avant une
-- écriture. `IdentifiantLaisseALaBaseTest` la surveille.
--
-- `gen_random_uuid()` est fourni par pgcrypto, intégré au cœur de PostgreSQL
-- depuis la version 13 : aucune extension à créer.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- users : le profil applicatif, PAS l'identité
-- ----------------------------------------------------------------------------
-- L'identité — adresse, numéro, nom, mot de passe, rôle — vit dans
-- auth-service. Cette table ne porte que ce qui lui est propre : l'état
-- administratif du compte et les données de profil.
--
-- `auth_user_id` est le lien vers auth-service. Il n'y a PAS de clef étrangère :
-- les deux services ont des bases séparées, et une contrainte ne traverse pas
-- deux bases. La cohérence passe par les événements Kafka.
CREATE TABLE IF NOT EXISTS users (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Identifiant du compte dans auth-service. UNIQUE : deux lignes pour un
    -- même compte feraient rendre l'une ou l'autre au hasard par
    -- findByAuthUserId.
    auth_user_id        VARCHAR(36)  NOT NULL UNIQUE,

    date_of_birth       DATE,
    profile_picture_url VARCHAR(512),

    -- État administratif. Les cinq valeurs de UserStatus.
    --
    -- Contrainte de domaine explicite : sans elle, une valeur inconnue écrite
    -- par erreur ferait échouer la LECTURE de la ligne, et le compte
    -- deviendrait illisible plutôt que mal étiqueté.
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',

    -- Motif de la décision administrative, et son horodatage.
    --
    -- Conservés après un retour à ACTIVE ? Non : `restore` les efface, parce
    -- que c'est l'état COURANT du compte. L'historique des décisions est dans
    -- audit_logs, qui ne s'efface pas.
    blocked_reason      VARCHAR(500),
    blocked_at          TIMESTAMP,

    -- Échéance d'une suspension. C'est ce qui distingue une suspension d'un
    -- blocage : elle porte une fin.
    suspended_until     TIMESTAMP,

    -- Effacement logique. « Effacer n'est pas supprimer la ligne » : un compte
    -- révoqué garde sa ligne, pour que l'audit sache qu'il a existé.
    deleted_at          TIMESTAMP,

    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,

    CONSTRAINT ck_users_status CHECK (
        status IN ('ACTIVE', 'BLOCKED', 'SUSPENDED', 'BANNED', 'DELETED'))
);

-- Index sur le lien vers auth-service : c'est la requête du chemin interne,
-- appelée à chaque création de compte et à chaque connexion.
CREATE INDEX IF NOT EXISTS idx_users_auth_user_id ON users (auth_user_id);

-- Index partiel sur les comptes vivants : les listes d'administration et les
-- recherches ne parcourent pas l'historique des révocations.
CREATE INDEX IF NOT EXISTS idx_users_actifs
    ON users (status) WHERE deleted_at IS NULL;

-- ----------------------------------------------------------------------------
-- user_profiles : les données déclaratives
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_profiles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- UNIQUE : un profil par compte. Sans cette contrainte, deux profils
    -- coexisteraient et la lecture en rendrait un au hasard — une modification
    -- paraîtrait perdue une fois sur deux.
    user_id     UUID         NOT NULL UNIQUE,

    bio         VARCHAR(2000),
    address     VARCHAR(500),
    city        VARCHAR(120),
    country     VARCHAR(120),
    postal_code VARCHAR(20),

    -- Préférences libres, en TEXTE JSON.
    --
    -- Pas une table clef/valeur : le champ est rendu tel quel dans
    -- UserDetailDTO et effacé d'un bloc par le RGPD. Une table imposerait une
    -- jointure et un dépôt de plus pour une donnée qui n'est jamais interrogée
    -- par son contenu.
    --
    -- Et pas JSONB, bien que la base validerait alors le document : lier une
    -- colonne JSONB demande le type `Json` du pilote PostgreSQL, déclaré en
    -- portée `runtime` pour que le code du service ne dépende pas d'un pilote
    -- précis. Jackson valide à l'écriture, ce qui couvre tout ce qui vient de
    -- l'application ; une modification manuelle en base est traitée par le
    -- repli de lecture de ConversionsJsonConfig.
    --
    -- Elles portent des DONNEES PERSONNELLES — le test d'effacement y place une
    -- adresse de contact — et sont donc vidées par ProfileErasureService.
    --
    -- La conversion Map <-> texte passe par ConversionsJsonConfig : R2DBC ne
    -- sait pas mapper une Map sans convertisseur, et l'écriture échouait donc
    -- avant même d'atteindre la base.
    preferences TEXT         NOT NULL DEFAULT '{}',

    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP,

    CONSTRAINT fk_user_profiles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_profiles_user_id ON user_profiles (user_id);

-- ----------------------------------------------------------------------------
-- user_roles : les rôles applicatifs du compte
-- ----------------------------------------------------------------------------
-- Le rôle d'AUTHENTIFICATION est dans auth-service et voyage dans le jeton.
-- Cette table porte les rôles applicatifs, dont un compte peut avoir plusieurs.
CREATE TABLE IF NOT EXISTS user_roles (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    role        VARCHAR(40)  NOT NULL,

    -- Rôle principal : celui qu'on affiche. Un seul par compte.
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Désactivation sans suppression : savoir qu'un rôle a été accordé puis
    -- retiré est ce qu'un journal d'audit doit montrer.
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,

    assigned_at TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP,

    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Un même rôle ne peut être accordé deux fois au même compte : deux lignes
-- identiques rendraient le retrait d'un rôle sans effet apparent, l'autre
-- restant active.
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_roles_unique
    ON user_roles (user_id, role);

-- Un seul rôle PRINCIPAL par compte, imposé par la base.
--
-- Un index unique partiel plutôt qu'une vérification applicative : deux
-- requêtes simultanées passeraient toutes deux une vérification en code, et le
-- compte finirait avec deux rôles principaux.
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_roles_un_seul_principal
    ON user_roles (user_id) WHERE is_primary;

-- ----------------------------------------------------------------------------
-- audit_logs : le journal des décisions d'administration
-- ----------------------------------------------------------------------------
-- Il ne s'efface pas, et c'est sa raison d'être. Un compte peut être révoqué,
-- ses données effacées, et le journal doit encore pouvoir attester de QUI a
-- décidé QUOI et QUAND.
--
-- Pas de clef étrangère vers users : la ligne du journal doit survivre à la
-- disparition de la ligne du compte. `ON DELETE CASCADE` ferait exactement
-- l'inverse de ce qu'on attend d'un journal.
CREATE TABLE IF NOT EXISTS audit_logs (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Compte visé. Pas de clef étrangère : voir ci-dessus.
    user_id        UUID,

    -- Auteur de l'action : son authUserId dans auth-service, et non un
    -- identifiant local — un administrateur n'a pas forcément de ligne ici.
    admin_id       VARCHAR(36),

    action_type    VARCHAR(60)   NOT NULL,
    entity_type    VARCHAR(40),
    entity_id      VARCHAR(64),
    action_details TEXT,

    -- Avant et après. C'est ce qui rend une décision contestable : sans la
    -- valeur précédente, le journal dit qu'on a changé quelque chose sans dire
    -- ce qu'on a changé.
    previous_value TEXT,
    new_value      TEXT,

    ip_address     VARCHAR(64),
    user_agent     VARCHAR(512),

    -- Horodatage de l'ACTION, distinct de created_at qui date l'écriture de la
    -- ligne. Les deux coïncident presque toujours, et « presque » est la raison
    -- de les séparer : une reprise de journal écrirait des lignes anciennes.
    timestamp      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    status         VARCHAR(20),
    error_message  TEXT,

    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP
);

-- Les deux requêtes du journal : « qu'a-t-on fait à ce compte » et « que s'est-il
-- passé sur cette période ».
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id ON audit_logs (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_timestamp ON audit_logs (timestamp DESC);

COMMENT ON TABLE users IS
    'Profil applicatif. L identite (adresse, numero, nom, mot de passe) vit dans auth-service.';
COMMENT ON COLUMN users.auth_user_id IS
    'Lien vers auth-service. Aucune clef etrangere : bases separees, coherence par evenements.';
COMMENT ON COLUMN user_profiles.preferences IS
    'Texte JSON. Porte des donnees personnelles, videes par ProfileErasureService.';
COMMENT ON TABLE audit_logs IS
    'Journal des decisions d administration. Ne s efface pas, et sans clef etrangere pour survivre au compte.';
