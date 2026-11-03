-- ==========================================================
-- Les jetons de rafraichissement cessent d'etre stockes en clair
--
-- Ce qui etait en place
-- ---------------------
-- refresh_tokens.token portait la valeur EXACTE presentee par le client :
-- RefreshToken.builder().token(UUID.randomUUID().toString()), ecrite telle
-- quelle. Un jeton de rafraichissement suffit a obtenir un jeton d'acces neuf,
-- indefiniment, sans mot de passe et sans second facteur. Cette colonne etait
-- donc une liste de sessions pretes a l'emploi : une sauvegarde egaree, un
-- export, une lecture imprevue, et chaque ligne est utilisable.
--
-- Le service masquait pourtant deja ces jetons dans les JOURNAUX
-- (SecretMasking.masquer). La precaution la plus visible etait prise ; la plus
-- importante ne l'etait pas.
--
-- Pourquoi les lignes existantes sont REVOQUEES et non converties
-- --------------------------------------------------------------
-- Convertir serait possible — encode(digest(token,'sha256'),'hex') avec
-- pgcrypto — et ce serait le mauvais choix. Ces secrets ont ete conserves en
-- clair : on ne sait pas qui a pu les lire. Les reporter sous forme d'empreinte
-- preserverait precisement les sessions dont la confidentialite n'est pas
-- etablie.
--
-- La revocation est explicite plutot que subie : sans elle, le changement de
-- colonne rendrait ces lignes introuvables et les sessions cesseraient de
-- fonctionner sans trace. Ici, revoked_at porte la date et l'audit reste
-- lisible. Cote client, le rafraichissement rend TOKEN_INVALID et l'utilisateur
-- se reconnecte.
--
-- Effet au deploiement : toutes les sessions en cours doivent se
-- reauthentifier. C'est le cout, assume, d'une correction de stockage.
-- ==========================================================

-- La colonne est renommee et non doublee : deux colonnes dont une seule porte
-- la verite est precisement le defaut que ce depot traque ailleurs. Le nom dit
-- desormais ce qu'elle contient — une empreinte, pas un jeton.
ALTER TABLE refresh_tokens RENAME COLUMN token TO empreinte;

-- Revocation AVANT le changement de type : les valeurs en clair sont des UUID
-- de 36 caracteres et tiennent dans VARCHAR(64), mais l'ordre rend l'intention
-- lisible — on ferme les sessions, puis on ajuste la colonne.
UPDATE refresh_tokens
   SET is_revoked = TRUE,
       revoked_at = CURRENT_TIMESTAMP
 WHERE is_revoked = FALSE;

-- 64 caracteres exactement : SHA-256 en hexadecimal minuscule. VARCHAR(500)
-- laissait croire qu'une valeur longue et arbitraire pouvait y entrer.
ALTER TABLE refresh_tokens ALTER COLUMN empreinte TYPE VARCHAR(64);

-- L'index suit le nom de la colonne. Le laisser sous son ancien nom rendrait
-- illisible le plan d'une requete de rafraichissement.
ALTER INDEX IF EXISTS idx_refresh_token RENAME TO idx_refresh_empreinte;

COMMENT ON COLUMN refresh_tokens.empreinte IS
    'SHA-256 hexadecimal du secret porteur. Le secret lui-meme n''est JAMAIS stocke : il est remis une seule fois a l''appelant, a la creation. Voir EmpreinteDeSecret dans platform-commons.';
