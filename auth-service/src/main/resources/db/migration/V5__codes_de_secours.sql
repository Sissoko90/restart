-- ============================================================================
-- Codes de secours du second facteur
-- ============================================================================
-- Ils remplacent un code TOTP quand l'appareil qui le produit est perdu, cassé
-- ou remplacé.
--
-- ----------------------------------------------------------------------------
-- Pourquoi ils sont devenus necessaires
-- ----------------------------------------------------------------------------
-- Le second facteur etait reserve aux comptes d'administration, et sa seule
-- sortie etait `retirer()` par un SUPER_ADMIN. Tenable pour une poignee
-- d'administrateurs qu'on connait.
--
-- Depuis que l'enrolement est accessible en libre-service, un client peut
-- l'activer — et perdre son telephone. La sortie par SUPER_ADMIN devient alors
-- intenable : elle demande une intervention humaine par client bloque, et
-- surtout elle oblige le support a juger de l'identite de quelqu'un qui ne peut
-- plus prouver qui il est. C'est exactement le chemin que prennent les attaques
-- par ingenierie sociale.
--
-- Les codes de secours deplacent cette preuve AVANT l'incident : ils sont remis
-- au moment de l'enrolement, quand la personne est encore en mesure de prouver
-- son identite.
--
-- ----------------------------------------------------------------------------
-- Pourquoi une ligne par code, et non un tableau
-- ----------------------------------------------------------------------------
-- Chaque code est a USAGE UNIQUE. Une ligne par code permet de marquer
-- precisement celui qui a ete consomme, quand, et depuis quelle adresse — donc
-- de montrer a la personne lesquels de ses codes ont servi.
--
-- Un tableau dans une colonne obligerait a reecrire l'ensemble a chaque usage,
-- avec la course que cela suppose : deux connexions simultanees consommant deux
-- codes differents, et l'une des deux ecritures perdue.
--
-- ----------------------------------------------------------------------------
-- Pourquoi les codes sont HACHES, et avec BCrypt cette fois
-- ----------------------------------------------------------------------------
-- Un code de secours remplace le second facteur : qui lit la base pourrait
-- sinon franchir ce controle.
--
-- BCrypt et non SHA-256, contrairement au jeton d'appareil de confiance, et la
-- difference est deliberee : un code de secours est COURT et lisible par un
-- humain — il doit pouvoir etre recopie depuis un papier. Son espace est donc
-- minuscule devant 32 octets aleatoires, et un SHA-256 se casse par force brute
-- en quelques secondes sur du materiel courant.
--
-- Le cout de BCrypt — les 241 ms mesures dans ce depot — est acceptable ici :
-- un code de secours sert quelques fois dans la vie d'un compte, pas a chaque
-- connexion.
-- ============================================================================

CREATE TABLE IF NOT EXISTS code_de_secours (
    id            VARCHAR(36)  PRIMARY KEY,
    user_id       VARCHAR(36)  NOT NULL,

    -- Empreinte BCrypt : 60 caracteres. Jamais le code.
    empreinte     VARCHAR(72)  NOT NULL,

    -- Quatre premiers caracteres du code, en clair.
    --
    -- Ce n'est PAS une fuite : quatre caracteres sur dix laissent un espace
    -- encore trop grand pour etre devine, et BCrypt protege le reste. En
    -- echange, la personne peut cocher sur son papier les codes deja utilises
    -- — sans cet indice, une liste de « code consomme le 3 mars » ne lui
    -- apprend rien d'exploitable.
    debut         VARCHAR(8)   NOT NULL,

    -- Horodatages d'audit du socle commun.
    --
    -- OBLIGATOIRES, et pas par convention : l'entite herite de BaseEntity, et
    -- Spring Data R2DBC inclut les proprietes heritees dans l'INSERT. Sans ces
    -- deux colonnes, toute ecriture echoue sur « column created_at does not
    -- exist » — et le profil de test desactive R2DBC, donc rien ne le signale
    -- avant le premier demarrage contre une vraie base.
    --
    -- created_at date la REMISE du code : aucune colonne « cree_le » separee,
    -- qui n'en serait qu'un doublon divergeant au premier oubli.
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP,

    -- Consommation : la ligne n'est pas supprimee.
    --
    -- Montrer a la personne QUELS codes ont servi, quand et depuis ou, est ce
    -- qui lui permet de reperer un usage qu'elle ne reconnait pas. Une
    -- suppression effacerait precisement cet indice.
    consomme_le   TIMESTAMPTZ,
    consomme_ip   VARCHAR(64),

    -- Invalidation par regeneration : un lot remplace l'autre.
    --
    -- Distinct de la consommation : un code invalide n'a jamais servi, il a ete
    -- remplace. Confondre les deux ferait croire a la personne qu'un code a ete
    -- utilise alors qu'elle en a simplement demande de nouveaux.
    invalide_le   TIMESTAMPTZ,

    CONSTRAINT fk_code_secours_utilisateur
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Index partiel sur les seuls codes encore utilisables : c'est la requete du
-- chemin de connexion. Sans le predicat, elle parcourrait tous les lots
-- regeneres depuis la creation du compte.
CREATE INDEX IF NOT EXISTS idx_code_secours_utilisables
    ON code_de_secours (user_id)
    WHERE consomme_le IS NULL AND invalide_le IS NULL;

COMMENT ON TABLE code_de_secours IS
    'Codes a usage unique remplaçant le second facteur. Haches en BCrypt : un code court se casse en SHA-256.';
COMMENT ON COLUMN code_de_secours.debut IS
    'Quatre premiers caracteres, en clair, pour que la personne reconnaisse ses codes sur son papier.';
COMMENT ON COLUMN code_de_secours.invalide_le IS
    'Remplace par regeneration — distinct de consomme_le, qui signifie « a servi ».';
