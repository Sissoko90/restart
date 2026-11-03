-- ============================================================================
-- security-service : politique de securite, regles d'acces, maintenance
-- ============================================================================
-- Ce schema reprend trois choses qui existaient ailleurs, et les reunit sous un
-- detenteur unique :
--
--   1. SettingCategory.SECURITY de setting-service -> surcharge_parametre
--   2. la liste noire d'IP de IpBlacklistWebFilter -> regle_ip
--   3. la liste de pays de GeoAdminController      -> pays_bloque
--
-- Les deux dernieres ne vivaient que dans Redis, sans motif ni auteur ni
-- historique. Redis reste l'applicateur ; la base devient la source.
--
-- Le mode maintenance, lui, n'existait nulle part en pratique :
-- setting-service ecrivait system.maintenance-mode et rien ne le lisait.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Surcharges de la politique
-- ----------------------------------------------------------------------------
-- Seules les valeurs qui DIFFERENT du defaut sont ici. Les defauts vivent dans
-- l'enumeration ParametreDeSecurite : remettre a zero consiste a supprimer la
-- ligne, pas a y reecrire le defaut — sinon un changement de defaut dans le
-- code ne s'appliquerait jamais aux installations deja remises a zero.
CREATE TABLE IF NOT EXISTS surcharge_parametre (
    cle         VARCHAR(120) PRIMARY KEY,
    valeur      VARCHAR(500) NOT NULL,
    modifie_par VARCHAR(100) NOT NULL,
    modifie_le  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE surcharge_parametre IS
    'Politique de securite : uniquement les valeurs qui differrent du defaut du catalogue.';
COMMENT ON COLUMN surcharge_parametre.modifie_par IS
    'Qui a change ce parametre. Sans cette colonne, une revue de securite constate un etat sans pouvoir le discuter.';

-- ----------------------------------------------------------------------------
-- Regles d'IP
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS regle_ip (
    id           VARCHAR(36)  PRIMARY KEY,
    adresse      VARCHAR(64)  NOT NULL,
    motif        VARCHAR(40)  NOT NULL,
    commentaire  VARCHAR(500),
    -- NULL = permanent. N'est accepte, par le service, que pour un motif qui
    -- n'est pas une detection automatique : un faux positif permanent enferme
    -- une adresse pour toujours, et la victime ne peut plus se connecter pour
    -- le signaler.
    expire_le    TIMESTAMPTZ,
    pose_par     VARCHAR(100) NOT NULL,
    cree_le      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- La levee est enregistree, la ligne n'est pas supprimee : « effacer n'est
    -- pas supprimer la ligne » vaut aussi pour une decision de blocage.
    levee_le     TIMESTAMPTZ,
    levee_par    VARCHAR(100)
);

-- Index partiel sur les seules regles non levees : c'est la requete du
-- rechargement du cache, et elle n'a aucune raison de parcourir l'historique.
CREATE INDEX IF NOT EXISTS idx_regle_ip_actives
    ON regle_ip (adresse) WHERE levee_le IS NULL;

CREATE INDEX IF NOT EXISTS idx_regle_ip_expiration
    ON regle_ip (expire_le) WHERE levee_le IS NULL AND expire_le IS NOT NULL;

COMMENT ON TABLE regle_ip IS
    'Adresses bloquees, avec motif, auteur et echeance. Alimente le cache lu par IpBlacklistWebFilter.';

-- ----------------------------------------------------------------------------
-- Pays bloques
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pays_bloque (
    -- ISO 3166-1 alpha-2, normalise en majuscules par le service. Sans
    -- normalisation, « ml » et « ML » creent deux lignes dont une seule est lue.
    code_iso   CHAR(2)      PRIMARY KEY,
    motif      VARCHAR(500) NOT NULL,
    bloque_par VARCHAR(100) NOT NULL,
    cree_le    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    levee_le   TIMESTAMPTZ,
    levee_par  VARCHAR(100)
);

COMMENT ON TABLE pays_bloque IS
    'Pays refuses. La surface d administration vient de GeoAdminController, qui vivait dans le gateway.';

-- ----------------------------------------------------------------------------
-- Fenetres de maintenance
-- ----------------------------------------------------------------------------
-- Plusieurs fenetres coexistent : une globale et une par service. La plus
-- restrictive s'applique, de sorte qu'activer une maintenance ciblee ne leve
-- pas par accident une maintenance globale.
CREATE TABLE IF NOT EXISTS fenetre_maintenance (
    id          VARCHAR(36)  PRIMARY KEY,
    -- '*' designe la plateforme entiere. Une valeur reservee plutot qu'un NULL :
    -- « service = NULL » dans une requete est une source classique de ligne
    -- jamais trouvee.
    service     VARCHAR(60)  NOT NULL,
    -- Obligatoire : une maintenance sans message rend un refus que personne ne
    -- sait interpreter, et le client appelle le support pour le dire.
    message     VARCHAR(500) NOT NULL,
    debute_le   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finit_le    TIMESTAMPTZ,
    activee_par VARCHAR(100) NOT NULL,
    cree_le     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    levee_le    TIMESTAMPTZ,
    levee_par   VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_actives
    ON fenetre_maintenance (service) WHERE levee_le IS NULL;

COMMENT ON TABLE fenetre_maintenance IS
    'Coupures annoncees, globales ou par service. Remplace system.maintenance-mode, qui etait ecrit et jamais lu.';

-- ----------------------------------------------------------------------------
-- Graine : le pays que le gateway ensemençait lui-meme
-- ----------------------------------------------------------------------------
-- GeoLocationService, dans le gateway, appelait initializeDefaultBlockedCountries()
-- au demarrage et ajoutait « KP » si l'ensemble Redis etait vide. Deux ecrivains
-- pour un meme ensemble : l'ensemencement l'aurait fait reapparaitre a chaque
-- redemarrage pendant que la publication, qui REMPLACE l'ensemble depuis cette
-- table, l'aurait fait disparaitre. Un pays refuse par intermittence, sans
-- qu'aucune erreur ne le signale.
--
-- La graine devient donc une ligne : visible, auditable, et retirable par
-- l'API — ce que l'ensemencement en dur ne permettait pas.
--
-- ON CONFLICT DO NOTHING : la migration doit pouvoir etre rejouee sur une base
-- ou la decision a deja ete prise, voire levee. La reecrire ecraserait un choix
-- d'administrateur.
INSERT INTO pays_bloque (code_iso, motif, bloque_par, cree_le)
VALUES ('KP', 'Graine historique, reprise de l ensemencement du gateway', 'migration', NOW())
ON CONFLICT (code_iso) DO NOTHING;
