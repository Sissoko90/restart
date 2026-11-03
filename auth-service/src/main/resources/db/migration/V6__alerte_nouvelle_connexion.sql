-- ============================================================================
-- Alerte de nouvelle connexion : une préférence par compte
-- ============================================================================
-- Reçoit un courriel à chaque connexion aboutie.
--
-- ----------------------------------------------------------------------------
-- Pourquoi une préférence, alors que les autres alertes n'en sont pas
-- ----------------------------------------------------------------------------
-- Un changement de mot de passe ou d'adresse est RARE et toujours notable :
-- l'alerte part sans condition, parce que la rendre optionnelle la
-- desactiverait pour tous ceux qui n'ouvrent jamais leurs reglages — c'est-a-dire
-- la plupart des gens, et precisement ceux qu'un detournement de compte
-- surprendrait.
--
-- Une connexion est FREQUENTE et ordinaire. Un courriel par connexion atteint
-- un volume dont le resultat connu est que les gens cessent de les lire, puis
-- les desactivent. Une alerte qu'on ignore protege moins qu'une alerte qu'on a
-- choisie.
--
-- ----------------------------------------------------------------------------
-- Pourquoi le defaut est FAUX
-- ----------------------------------------------------------------------------
-- C'est le choix assume, et il a un cout : un compte qui n'ouvre jamais ses
-- reglages n'est pas prevenu de ses connexions. Ce qui le rend defendable est
-- que les deux alertes QUI COMPTENT — mot de passe et adresse modifies — sont
-- elles sans condition. Un detournement de compte passe forcement par l'une des
-- deux pour devenir durable : sans changer l'adresse ni le mot de passe,
-- l'attaquant perd l'acces des que la session expire.
--
-- Autrement dit, le defaut a faux reduit le bruit sans ouvrir de trou sur le
-- scenario qui compte.
--
-- ----------------------------------------------------------------------------
-- Pourquoi une colonne sur users, et non une cle dans security-service
-- ----------------------------------------------------------------------------
-- security-service porte la politique PARTAGEE : une quinzaine de valeurs
-- communes a toute la plateforme, publiees dans Redis. Ce drapeau est un etat
-- PAR COMPTE, lu sur le chemin de connexion. Le mettre la-bas supposerait une
-- entree Redis par compte — des millions de clefs pour un booleen — ou un appel
-- reseau par connexion.
--
-- Meme raison que pour les appareils de confiance et les codes de secours :
-- security-service decide des regles, auth-service detient l'etat des comptes.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS alerte_nouvelle_connexion BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.alerte_nouvelle_connexion IS
    'Prevenir par courriel a chaque connexion. Sur demande, car frequent ; les alertes de changement de mot de passe et d adresse sont elles sans condition.';
