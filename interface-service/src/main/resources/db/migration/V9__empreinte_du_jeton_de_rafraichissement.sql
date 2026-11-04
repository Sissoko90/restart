-- ==========================================================
-- Le jeton de rafraichissement n'est plus stocke en clair
-- ==========================================================
--
-- DEUX MAGASINS POUR UN MEME ETAT, et celui qui servait etait le dangereux.
--
-- V1 avait cree `refresh_token_hash` : le bon choix, une empreinte. V3 a
-- ajoute `refresh_token VARCHAR(500)` a cote, AVEC UN INDEX DESSUS, et le code
-- a suivi la nouvelle colonne. `refresh_token_hash` n'etait plus que remis a
-- null par l'effacement RGPD -- une colonne correcte, presente, et inutilisee.
--
-- C'est le meme defaut que auth-service a ferme par sa migration V8. La raison
-- y est ecrite et vaut ici : un jeton de rafraichissement stocke en clair est
-- directement utilisable par quiconque lit la base -- une sauvegarde, un
-- vidage, un acces en lecture. Une empreinte SHA-256 ne l'est pas.
--
-- AUCUNE DONNEE N'EST PERDUE. La colonne n'etait JAMAIS ecrite : son seul
-- redacteur est `updateRefreshToken`, appele depuis le seul flux de
-- rafraichissement, lequel echouait avant d'y parvenir -- la connexion ne
-- persistait pas le jeton qu'elle emettait, donc la recherche ne trouvait
-- jamais rien. La colonne est vide dans toutes les lignes.
--
-- Pas de bcrypt ni d'Argon2 pour cette empreinte, et c'est delibere : il n'y a
-- aucun dictionnaire a attaquer -- le jeton est engendre aleatoirement, pas
-- choisi par un humain. Et une empreinte salee ne pourrait pas etre retrouvee
-- par index, donc la recherche deviendrait un balayage de table. Voir le
-- javadoc d'EmpreinteDeSecret dans platform-commons.
-- ==========================================================

-- L'index d'abord : il porte la valeur en clair, et le laisser ferait echouer
-- la suppression de la colonne sur certaines versions.
DROP INDEX IF EXISTS idx_users_refresh_token;

ALTER TABLE users DROP COLUMN IF EXISTS refresh_token;

-- 64 caracteres hexadecimaux pour une empreinte SHA-256. VARCHAR(255) suffit
-- largement, et la colonne existe deja depuis V1 : on ne la recree pas.
COMMENT ON COLUMN users.refresh_token_hash IS
    'Empreinte SHA-256 du jeton de rafraichissement, 64 caracteres hexadecimaux. Jamais la valeur presentee par le client.';

-- La recherche se fait desormais sur l'empreinte : sans index, chaque
-- rafraichissement deviendrait un balayage de la table des comptes.
CREATE INDEX IF NOT EXISTS idx_users_refresh_token_hash
    ON users (refresh_token_hash)
    WHERE refresh_token_hash IS NOT NULL;
