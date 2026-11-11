-- ============================================================================
-- Schema initial de market-service
-- ============================================================================
-- La place de marche : des boutiques professionnelles, un catalogue, une
-- moderation, et des avis.
--
-- ----------------------------------------------------------------------------
-- Les identifiants sont produits par la BASE
-- ----------------------------------------------------------------------------
-- `gen_random_uuid()` sur chaque clef primaire : les entites laissent leur `id`
-- nul, donc Spring Data R2DBC sait qu'une ligne est neuve et emet un INSERT.
-- La regle a tenir est de ne JAMAIS appeler `setId()` avant une ecriture.
--
-- C'est le choix de user-service, et l'inverse de celui d'auth-service, ou
-- l'application assigne les identifiants et a besoin de `Persistable` pour
-- lever l'ambiguite — sans quoi un identifiant deja pose fait emettre un
-- UPDATE pour une ligne jamais inseree, et le `save()` est sans effet.
--
-- `gen_random_uuid()` vient de pgcrypto, integre au coeur de PostgreSQL depuis
-- la version 13 : aucune extension a creer.
--
-- ----------------------------------------------------------------------------
-- TOUTES les tables portent created_at ET updated_at
-- ----------------------------------------------------------------------------
-- Y compris celles dont les lignes ne sont jamais modifiees en place —
-- produit_photo, produit_caracteristique, boutique_document sont remplacees en
-- bloc, pas mises a jour.
--
-- La raison n'est pas l'audit : c'est que toutes les entites heritent de
-- `BaseEntity`, qui declare les deux colonnes. R2DBC mappe la classe entiere,
-- donc une table a qui il en manque une fait echouer le SELECT avec « bad SQL
-- grammar » — pas a l'ecriture, mais a la LECTURE, et seulement sur le chemin
-- qui lit cette table. Mesure du 2026-11-12 : la creation d'un produit rendait
-- 500 parce que l'enrichissement relisait produit_photo.
--
-- Deux conventions pour un meme socle d'entites couteraient plus cher que huit
-- octets par ligne.
--
-- ----------------------------------------------------------------------------
-- AUCUNE clef etrangere vers un autre service
-- ----------------------------------------------------------------------------
-- `auth_user_id` designe un compte d'auth-service, `document_id` un document de
-- media-service. Ce sont des references LOGIQUES : une contrainte referentielle
-- entre deux bases n'existe pas, et pretendre le contraire ferait croire a une
-- integrite que personne ne tient.
--
-- Consequence assumee : un compte efface laisse des lignes ici. C'est
-- l'ecouteur d'effacement RGPD qui s'en charge, pas la base.
--
-- ----------------------------------------------------------------------------
-- AUCUN BINAIRE DANS CE SERVICE
-- ----------------------------------------------------------------------------
-- Les photos de produit et les scans de RCCM n'ont pas de colonne `bytea` ici.
-- media-service detient les fichiers, leur chiffrement, leur analyse antivirus
-- et leur duree de conservation ; ce service ne garde que l'identifiant qu'il
-- rend. Un second magasin de fichiers, c'est un second endroit ou un document
-- d'identite peut fuir.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- categorie : l'arbre du catalogue
-- ----------------------------------------------------------------------------
-- `parent_id` pointe la meme table. Une categorie racine a `parent_id` nul.
--
-- ON DELETE RESTRICT, et non CASCADE : supprimer « Electronique » ne doit pas
-- emporter en silence ses douze sous-categories et les produits qui s'y
-- rattachent. Le refus oblige a regarder.
CREATE TABLE categorie (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES categorie (id) ON DELETE RESTRICT,
    nom         VARCHAR(120) NOT NULL,
    -- Le slug sert aux URL publiques. Unique sur TOUT l'arbre et non par
    -- niveau : une URL ne porte pas le chemin de ses parents, donc deux slugs
    -- identiques a deux endroits rendraient l'adresse ambigue.
    slug        VARCHAR(140) NOT NULL UNIQUE,
    position    INTEGER      NOT NULL DEFAULT 0,
    actif       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_categorie_parent ON categorie (parent_id);


-- ----------------------------------------------------------------------------
-- boutique : le compte professionnel
-- ----------------------------------------------------------------------------
-- ETRE PRO, C'EST AVOIR UNE BOUTIQUE APPROUVEE — PAS PORTER UN ROLE.
--
-- Le role `MERCHANT` existe dans auth-service, et il aurait pu servir. Il ne
-- sait pourtant pas exprimer ce qui decide ici : approuvee quand, par qui, sur
-- quel RCCM, et retirable sans toucher au jeton de la personne. Ce service
-- tranche donc lui-meme, et n'ecrit jamais dans auth-service.
--
-- `pays` est RECOPIE depuis user-service a la creation. Il decide de la
-- visibilite des produits, donc il ne vient jamais du formulaire : un
-- particulier pourrait sinon elargir sa portee en se declarant d'ailleurs.
CREATE TABLE boutique (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Le proprietaire, par son identifiant d'authentification. UNIQUE : une
    -- personne ne tient qu'une boutique. Lever cette regle demanderait de
    -- revoir la resolution de portee, qui suppose une boutique au plus.
    auth_user_id  VARCHAR(64)  NOT NULL UNIQUE,
    nom           VARCHAR(160) NOT NULL,
    description   TEXT,
    -- Registre du commerce et numero d'identification fiscale. Non uniques :
    -- la contrainte d'unicite appartient a l'organisme qui les delivre, et
    -- l'imposer ici refuserait une saisie legitime sur une faute de frappe
    -- d'autrui. L'unicite reelle est verifiee par la MODERATION.
    rccm          VARCHAR(64)  NOT NULL,
    nif           VARCHAR(64)  NOT NULL,
    pays          VARCHAR(2)   NOT NULL,
    ville         VARCHAR(120),
    adresse       TEXT,
    telephone     VARCHAR(32),
    -- Identifiants de documents chez media-service. Jamais des URL : une URL
    -- codee en dur ici survivrait a un changement d'hebergement et pointerait
    -- dans le vide.
    logo_document_id       VARCHAR(64),
    couverture_document_id VARCHAR(64),
    statut        VARCHAR(24)  NOT NULL DEFAULT 'EN_ATTENTE',
    -- Rempli au refus, et RENDU au proprietaire : un refus dont on ignore le
    -- motif se represente a l'identique.
    motif_decision TEXT,
    decide_par    VARCHAR(64),
    decide_le     TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT boutique_statut_connu
        CHECK (statut IN ('EN_ATTENTE', 'APPROUVEE', 'REJETEE', 'SUSPENDUE'))
);

-- La file de moderation : les boutiques en attente, les plus anciennes
-- d'abord. Index partiel — il ne porte que les lignes qui interessent la file,
-- et reste donc petit meme avec un million de boutiques approuvees.
CREATE INDEX idx_boutique_a_moderer ON boutique (created_at)
    WHERE statut = 'EN_ATTENTE';

CREATE INDEX idx_boutique_pays ON boutique (pays) WHERE statut = 'APPROUVEE';


-- ----------------------------------------------------------------------------
-- boutique_document : les pieces justificatives
-- ----------------------------------------------------------------------------
-- Une table plutot que deux colonnes `rccm_document_id` / `nif_document_id` :
-- une piece peut tenir en plusieurs pages, un pays peut en exiger une
-- troisieme, et une piece refusee doit pouvoir etre remplacee sans perdre
-- l'historique de ce qui a ete soumis.
CREATE TABLE boutique_document (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    boutique_id UUID         NOT NULL REFERENCES boutique (id) ON DELETE CASCADE,
    type        VARCHAR(32)  NOT NULL,
    -- L'identifiant rendu par media-service. UNIQUE : rattacher deux fois le
    -- meme document a la meme boutique est une erreur de saisie, pas une
    -- intention.
    document_id VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT boutique_document_type_connu
        CHECK (type IN ('RCCM', 'NIF', 'PIECE_IDENTITE', 'AUTRE')),
    CONSTRAINT boutique_document_unique UNIQUE (boutique_id, document_id)
);

CREATE INDEX idx_boutique_document_boutique ON boutique_document (boutique_id);


-- ----------------------------------------------------------------------------
-- produit
-- ----------------------------------------------------------------------------
-- LES QUATRE COLONNES QUI DECIDENT DE LA VISIBILITE
--
--   statut_moderation  un produit non approuve n'est vu que de son vendeur
--   actif              le vendeur peut le retirer sans le supprimer
--   visibilite         PUBLIQUE, ou reservee aux comptes professionnels
--   pays               un particulier ne voit que le sien
--
-- `pays` est DENORMALISE depuis la boutique, et ce n'est pas une optimisation
-- prematuree : c'est la colonne la plus filtrante du catalogue, et la joindre
-- a chaque requete de liste ferait passer l'index de couverture a la trappe.
-- Le prix est qu'une boutique qui change de pays doit propager — c'est une
-- operation de moderation, pas une mise a jour courante.
CREATE TABLE produit (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    boutique_id   UUID         NOT NULL REFERENCES boutique (id) ON DELETE CASCADE,
    categorie_id  UUID         REFERENCES categorie (id) ON DELETE RESTRICT,
    nom           VARCHAR(200) NOT NULL,
    description   TEXT,
    -- Prix de reference, en plus petite unite monetaire (centimes, francs CFA
    -- entiers). Un BIGINT et non un NUMERIC flottant : l'arithmetique
    -- decimale approchee sur de l'argent finit toujours par couter un centime
    -- a quelqu'un, et personne ne sait lequel.
    prix          BIGINT       NOT NULL,
    devise        VARCHAR(3)   NOT NULL DEFAULT 'XOF',
    pays          VARCHAR(2)   NOT NULL,
    visibilite    VARCHAR(24)  NOT NULL DEFAULT 'PUBLIQUE',
    statut_moderation VARCHAR(24) NOT NULL DEFAULT 'EN_ATTENTE',
    motif_decision TEXT,
    decide_par    VARCHAR(64),
    decide_le     TIMESTAMP,
    actif         BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Agregats d'avis, tenus a jour a l'ecriture d'un avis.
    --
    -- Les recalculer a la lecture ferait une agregation par produit affiche,
    -- soit le defaut N+1 sur l'ecran le plus consulte du service. Le prix est
    -- qu'ils peuvent deriver ; une tache planifiee les reconcilie.
    note_moyenne  NUMERIC(3,2) NOT NULL DEFAULT 0,
    nombre_avis   INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT produit_prix_positif CHECK (prix >= 0),
    CONSTRAINT produit_note_bornee CHECK (note_moyenne >= 0 AND note_moyenne <= 5),
    CONSTRAINT produit_visibilite_connue
        CHECK (visibilite IN ('PUBLIQUE', 'RESERVEE_AUX_PRO')),
    CONSTRAINT produit_statut_connu
        CHECK (statut_moderation IN ('EN_ATTENTE', 'APPROUVE', 'REJETE'))
);

-- L'index du CATALOGUE, dans l'ordre ou la requete filtre.
--
-- Partiel sur ce qui est visible : un produit en attente ou retire ne figure
-- dans aucune liste publique, donc il n'a pas a peser sur cet index.
CREATE INDEX idx_produit_catalogue
    ON produit (pays, visibilite, categorie_id, created_at DESC)
    WHERE statut_moderation = 'APPROUVE' AND actif = TRUE;

-- La vitrine d'une boutique : tous ses produits, quel que soit leur etat.
CREATE INDEX idx_produit_boutique ON produit (boutique_id, created_at DESC);

-- La file de moderation, meme raisonnement que pour les boutiques.
CREATE INDEX idx_produit_a_moderer ON produit (created_at)
    WHERE statut_moderation = 'EN_ATTENTE';


-- ----------------------------------------------------------------------------
-- produit_photo : des references, pas des images
-- ----------------------------------------------------------------------------
CREATE TABLE produit_photo (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    produit_id  UUID         NOT NULL REFERENCES produit (id) ON DELETE CASCADE,
    document_id VARCHAR(64)  NOT NULL,
    position    INTEGER      NOT NULL DEFAULT 0,
    principale  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT produit_photo_unique UNIQUE (produit_id, document_id)
);

-- UNE SEULE photo principale par produit.
--
-- Un index unique PARTIEL, et c'est la seule forme qui tienne : une contrainte
-- UNIQUE (produit_id, principale) interdirait deux photos NON principales, ce
-- qui est exactement le cas courant.
CREATE UNIQUE INDEX idx_produit_photo_principale
    ON produit_photo (produit_id) WHERE principale = TRUE;

CREATE INDEX idx_produit_photo_produit ON produit_photo (produit_id, position);


-- ----------------------------------------------------------------------------
-- produit_variante : couleur, taille, et leur stock
-- ----------------------------------------------------------------------------
-- Une variante porte SON prix et SON stock. Un supplement par rapport au
-- produit paraissait plus simple, et ne l'est pas : une taille XXL a souvent un
-- prix propre, et un supplement negatif se lit mal.
CREATE TABLE produit_variante (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    produit_id  UUID         NOT NULL REFERENCES produit (id) ON DELETE CASCADE,
    -- Reference interne du vendeur. Unique DANS la boutique via le produit,
    -- jamais globalement : deux boutiques ont le droit d'employer « T-SHIRT-M ».
    sku         VARCHAR(64),
    couleur     VARCHAR(60),
    taille      VARCHAR(40),
    prix        BIGINT       NOT NULL,
    stock       INTEGER      NOT NULL DEFAULT 0,
    actif       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT produit_variante_prix_positif CHECK (prix >= 0),
    CONSTRAINT produit_variante_stock_positif CHECK (stock >= 0),
    -- Deux variantes d'un meme produit ne peuvent pas avoir le meme couple
    -- couleur/taille : ce serait deux lignes pour un seul article reel, donc
    -- deux stocks a tenir d'accord.
    CONSTRAINT produit_variante_unique UNIQUE (produit_id, couleur, taille)
);

CREATE INDEX idx_produit_variante_produit ON produit_variante (produit_id);


-- ----------------------------------------------------------------------------
-- produit_caracteristique : les attributs libres
-- ----------------------------------------------------------------------------
-- Une table plutot qu'un JSONB : ces valeurs sont destinees a etre FILTREES
-- (« marque = X », « matiere = coton »), et un JSONB rend ce filtrage possible
-- mais illisible, et son index difficile a prevoir.
CREATE TABLE produit_caracteristique (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    produit_id  UUID         NOT NULL REFERENCES produit (id) ON DELETE CASCADE,
    cle         VARCHAR(80)  NOT NULL,
    valeur      VARCHAR(400) NOT NULL,
    position    INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT produit_caracteristique_unique UNIQUE (produit_id, cle)
);

CREATE INDEX idx_produit_caracteristique_produit ON produit_caracteristique (produit_id);


-- ----------------------------------------------------------------------------
-- avis : note et commentaire
-- ----------------------------------------------------------------------------
-- UN SEUL AVIS PAR PERSONNE ET PAR PRODUIT. Sans cette contrainte, la note
-- moyenne se manipule en publiant cent fois.
--
-- Les avis passent eux aussi par la moderation : un commentaire est du texte
-- libre publie sous le nom d'une boutique qui ne l'a pas ecrit.
CREATE TABLE avis (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    produit_id    UUID         NOT NULL REFERENCES produit (id) ON DELETE CASCADE,
    auth_user_id  VARCHAR(64)  NOT NULL,
    note          SMALLINT     NOT NULL,
    commentaire   TEXT,
    statut_moderation VARCHAR(24) NOT NULL DEFAULT 'EN_ATTENTE',
    motif_decision TEXT,
    decide_par    VARCHAR(64),
    decide_le     TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT avis_note_bornee CHECK (note >= 1 AND note <= 5),
    CONSTRAINT avis_statut_connu
        CHECK (statut_moderation IN ('EN_ATTENTE', 'APPROUVE', 'REJETE')),
    CONSTRAINT avis_unique_par_personne UNIQUE (produit_id, auth_user_id)
);

CREATE INDEX idx_avis_produit ON avis (produit_id, created_at DESC)
    WHERE statut_moderation = 'APPROUVE';

CREATE INDEX idx_avis_a_moderer ON avis (created_at)
    WHERE statut_moderation = 'EN_ATTENTE';
