-- ============================================================================
-- Appareils de confiance
-- ============================================================================
-- Un appareil de confiance dispense du second facteur. C'est donc un
-- JUSTIFICATIF de longue duree, et il est traite comme tel.
--
-- ----------------------------------------------------------------------------
-- Pourquoi un jeton, et non une empreinte d'appareil
-- ----------------------------------------------------------------------------
-- Trois façons d'identifier un appareil d'une connexion a l'autre :
--
--   1. l'User-Agent et l'adresse IP. L'adresse change a chaque deplacement, et
--      l'User-Agent n'est pas unique : des milliers de personnes partagent
--      « Chrome 131 sur Windows 10 ». Deux comptes sur le meme modele de
--      telephone auraient la meme empreinte ;
--   2. un identifiant fourni par le client. Il suffit alors de le deviner ou de
--      le copier pour se faire passer pour un appareil de confiance ;
--   3. un jeton aleatoire emis par le serveur au moment ou la personne accorde
--      sa confiance, que le client conserve et represente ensuite.
--
-- La troisieme est la seule qui prouve quelque chose : le jeton n'existe que
-- parce que quelqu'un a passe le second facteur une fois.
--
-- ----------------------------------------------------------------------------
-- Pourquoi le jeton est HACHE
-- ----------------------------------------------------------------------------
-- Il dispense du second facteur : qui le lit en base peut se connecter sans
-- code, pendant toute sa validite. Une lecture de la base — sauvegarde, copie
-- d'environnement, injection SQL — ne doit pas livrer des justificatifs
-- utilisables.
--
-- SHA-256 et non BCrypt, deliberement : le jeton fait 32 octets aleatoires,
-- donc il n'y a aucune attaque par dictionnaire a ralentir. BCrypt couterait
-- 241 ms par connexion — la mesure faite dans ce depot — pour aucune protection
-- supplementaire. C'est le meme raisonnement que pour un jeton de session.
--
-- NOTE : refresh_tokens, lui, stocke son jeton EN CLAIR. C'est une faiblesse
-- signalee et non corrigee ici, parce que la corriger invaliderait toutes les
-- sessions ouvertes. Cette table ne la reproduit pas.
-- ============================================================================

CREATE TABLE IF NOT EXISTS appareil_de_confiance (
    id              VARCHAR(36)  PRIMARY KEY,
    user_id         VARCHAR(36)  NOT NULL,

    -- SHA-256 du jeton, en hexadecimal : 64 caracteres.
    empreinte_jeton CHAR(64)     NOT NULL,

    -- Nom donne par la personne, ou deduit de l'User-Agent a defaut.
    -- Sans nom lisible, une liste d'appareils ne permet pas de reconnaitre
    -- celui qu'on veut revoquer : « appareil 3 » ne designe rien.
    nom             VARCHAR(120) NOT NULL,

    -- Metadonnees d'affichage, figees a l'instant de la confiance.
    -- Figees et non rafraichies : la question posee a la personne est
    -- « reconnaissez-vous CETTE confiance », donc les circonstances dans
    -- lesquelles elle a ete accordee. Les rafraichir effacerait l'indice qui
    -- permet de reperer une confiance accordee par quelqu'un d'autre.
    navigateur      VARCHAR(80),
    systeme         VARCHAR(80),
    type_appareil   VARCHAR(40),
    ip_accordee     VARCHAR(64),
    pays_accorde    CHAR(2),

    accorde_le      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Derniere utilisation, pour que la personne puisse distinguer un appareil
    -- qu'elle utilise d'un appareil oublie.
    utilise_le      TIMESTAMPTZ,

    -- Echeance OBLIGATOIRE. Une confiance perpetuelle transforme un vol
    -- d'appareil en acces definitif sans second facteur, et personne ne pense a
    -- faire le menage dans une liste qui n'expire pas.
    expire_le       TIMESTAMPTZ  NOT NULL,

    -- Revocation enregistree, ligne conservee : savoir qu'un appareil a eu la
    -- confiance et l'a perdue, quand et par qui, est ce qu'un journal d'audit
    -- doit montrer. « Effacer n'est pas supprimer la ligne ».
    revoque_le      TIMESTAMPTZ,
    revoque_par     VARCHAR(40),

    -- Horodatages d'audit du socle commun.
    --
    -- OBLIGATOIRES, et pas par convention : l'entite herite de BaseEntity, et
    -- Spring Data R2DBC inclut les proprietes heritees dans l'INSERT. Sans ces
    -- deux colonnes, toute ecriture echoue sur « column created_at does not
    -- exist » — et le profil de test desactive R2DBC, donc rien ne le signale
    -- avant le premier demarrage contre une vraie base.
    --
    -- Distincts de accorde_le, qui date la CONFIANCE : une revocation met a
    -- jour updated_at sans que la confiance ait ete re-accordee.
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP,

    CONSTRAINT fk_appareil_utilisateur
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- L'empreinte est unique : deux lignes de meme empreinte signifieraient qu'un
-- meme jeton ouvre deux appareils, et la recherche en rendrait une au hasard.
CREATE UNIQUE INDEX IF NOT EXISTS idx_appareil_empreinte
    ON appareil_de_confiance (empreinte_jeton);

-- Index partiel sur les seuls appareils encore de confiance : c'est la requete
-- du chemin de connexion, et elle n'a aucune raison de parcourir l'historique
-- des revocations.
CREATE INDEX IF NOT EXISTS idx_appareil_actifs
    ON appareil_de_confiance (user_id) WHERE revoque_le IS NULL;

COMMENT ON TABLE appareil_de_confiance IS
    'Appareils dispenses du second facteur. Le jeton est hache : qui lit la base ne peut pas s en servir.';
COMMENT ON COLUMN appareil_de_confiance.expire_le IS
    'Obligatoire. Une confiance perpetuelle transforme un vol d appareil en acces definitif sans second facteur.';
