-- ============================================================================
-- Les noms laisses derriere par le renommage de V8
-- ============================================================================
-- V8 a renomme `refresh_tokens.token` en `empreinte`, et renomme l'index
-- `idx_refresh_token` en consequence. Son propre commentaire disait pourquoi :
-- « laisser un index sous son ancien nom rendrait illisible le plan d'une
-- requete de rafraichissement ».
--
-- Elle a pourtant manque les deux objets que PostgreSQL ne renomme PAS avec la
-- colonne, et qui portaient eux aussi le mot « token » :
--
--   * refresh_tokens_token_key      -- UNIQUE (empreinte)
--   * refresh_tokens_token_not_null -- NOT NULL empreinte
--
-- Ces noms sont engendres a partir du nom de colonne au moment du CREATE
-- TABLE ; une fois poses, ils ne suivent plus rien. `RENAME COLUMN` ne touche
-- que la colonne.
--
-- Mesure faite sur une COPIE de auth_service_db, V4 a V9 rejouees dessus le
-- 2026-11-10 : le plan d'une recherche par empreinte annonce
-- « Index Scan using refresh_tokens_token_key ». C'est exactement
-- l'illisibilite que V8 voulait eviter — et sur l'index qui sert reellement la
-- requete, pas sur celui qu'elle avait renomme.
--
-- ----------------------------------------------------------------------------
-- Et un index EN DOUBLON, present depuis V1
-- ----------------------------------------------------------------------------
-- V1 declarait « token VARCHAR(500) NOT NULL UNIQUE » — ce qui cree deja un
-- index unique — PUIS « CREATE INDEX idx_refresh_token ON refresh_tokens
-- (token) ». Deux arbres B sur la meme colonne unique, depuis l'origine.
--
-- Le second n'a jamais pu servir : pour une sonde d'egalite sur une colonne
-- unique, le planificateur prend l'index unique. Verifie avec
-- `enable_seqscan = off` sur la copie, la recherche de `findByEmpreinte` passe
-- par refresh_tokens_empreinte_key.
--
-- Il n'etait pas gratuit pour autant : chaque ouverture de session et chaque
-- revocation maintenaient un arbre de plus, pour aucune lecture. C'est l'index
-- que V8 a renomme — d'ou sa disparition ici, et non un second renommage.
--
-- ----------------------------------------------------------------------------
-- Pourquoi une migration de plus, et non une correction de V8
-- ----------------------------------------------------------------------------
-- V8 est deja appliquee quelque part des qu'une base a tourne. Flyway retient
-- une somme de controle par migration et REFUSE de demarrer si le fichier a
-- change : « Migration checksum mismatch ». Un script applique ne se corrige
-- pas, il se complete.
--
-- ----------------------------------------------------------------------------
-- Pourquoi chaque instruction est conditionnelle
-- ----------------------------------------------------------------------------
-- Les noms engendres dependent de la version de PostgreSQL — les contraintes
-- NOT NULL n'apparaissent dans `pg_constraint` que sur les versions recentes,
-- mesure ici sur 18.6 — et une base creee ailleurs, ou deja corrigee a la
-- main, n'a pas forcement ces noms.
--
-- Un ALTER sec echouerait, Flyway marquerait la version en echec, et bloquerait
-- TOUTES les suivantes. Pour un probleme de lisibilite, ce serait un prix
-- absurde.
-- ============================================================================

-- L'index en doublon. `IF EXISTS` : il n'existe que sur une base passee par V8.
DROP INDEX IF EXISTS idx_refresh_empreinte;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'refresh_tokens'::regclass
           AND conname = 'refresh_tokens_token_key'
    ) THEN
        ALTER TABLE refresh_tokens
            RENAME CONSTRAINT refresh_tokens_token_key
                           TO refresh_tokens_empreinte_key;
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'refresh_tokens'::regclass
           AND conname = 'refresh_tokens_token_not_null'
    ) THEN
        ALTER TABLE refresh_tokens
            RENAME CONSTRAINT refresh_tokens_token_not_null
                           TO refresh_tokens_empreinte_not_null;
    END IF;
END
$$;

-- Le commentaire est lui aussi conditionnel, et pour la meme raison : sur une
-- base ou la contrainte porte un troisieme nom, un COMMENT sec echouerait et
-- bloquerait toutes les migrations suivantes.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'refresh_tokens'::regclass
           AND conname = 'refresh_tokens_empreinte_key'
    ) THEN
        COMMENT ON CONSTRAINT refresh_tokens_empreinte_key ON refresh_tokens IS
            'Unicite de l''empreinte. C''est cet index qui sert findByEmpreinte : deux lignes de meme empreinte signifieraient qu''un meme secret ouvre deux sessions.';
    END IF;
END
$$;
