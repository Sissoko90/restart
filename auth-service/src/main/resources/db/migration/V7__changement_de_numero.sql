-- ============================================================================
-- Changement de numéro de téléphone : le numéro en attente
-- ============================================================================
-- Remplacer son numéro demande deux étapes, et un état entre les deux : le
-- nouveau numéro, pas encore prouvé.
--
-- ----------------------------------------------------------------------------
-- Pourquoi deux etapes, et non une
-- ----------------------------------------------------------------------------
-- Ecrire directement le nouveau numero sur presentation du mot de passe
-- accepterait n'importe quelle saisie : une faute de frappe enfermerait la
-- personne hors de la recuperation par SMS, sans aucun moyen de revenir — le
-- numero servant justement a recuperer le compte.
--
-- Le code part donc au NOUVEAU numero, et lui seul prouve qu'on le detient.
-- Tant qu'il n'est pas confirme, le compte garde son ancien numero : c'est ce
-- qui rend l'operation annulable par l'inaction.
--
-- ----------------------------------------------------------------------------
-- Pourquoi cet etat EXPIRE
-- ----------------------------------------------------------------------------
-- Une demande abandonnee laisserait sinon un numero en attente indefiniment,
-- qu'un code intercepte bien plus tard suffirait a activer. L'echeance borne la
-- fenetre, et elle est alignee sur celle du code lui-meme : au-dela, le code ne
-- vaut plus rien et l'attente non plus.
--
-- La demande n'est PAS supprimee a l'expiration : la colonne reste, et c'est la
-- lecture qui refuse. Un nettoyage periodique serait une piece de plus a
-- surveiller pour une correction qu'un simple predicat apporte.
--
-- ----------------------------------------------------------------------------
-- Pourquoi l'indicatif est conserve a part
-- ----------------------------------------------------------------------------
-- `country_code` est derive du numero par `PhoneNumbers.prefixeIndicatif`, qui
-- coupe un nombre fixe de caracteres — defaut connu et documente de ce depot.
-- Le client envoie donc l'indicatif explicitement, et il est garde avec le
-- numero en attente pour etre pose tel quel a la confirmation. Le deduire une
-- seconde fois reproduirait le defaut sur les comptes qui changent de numero.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS numero_en_attente          VARCHAR(20);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS indicatif_en_attente       VARCHAR(5);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS numero_en_attente_jusqu_a  TIMESTAMP;

-- Index partiel sur les seules demandes en cours.
--
-- Il sert la verification d'unicite : un numero en attente sur un compte ne
-- doit pas pouvoir etre confirme sur un autre. Sans le predicat, l'index
-- porterait une ligne par compte de la base pour une colonne presque toujours
-- nulle.
CREATE INDEX IF NOT EXISTS idx_users_numero_en_attente
    ON users (numero_en_attente)
    WHERE numero_en_attente IS NOT NULL;

COMMENT ON COLUMN users.numero_en_attente IS
    'Nouveau numero, pas encore prouve. Le compte garde l ancien jusqu a confirmation.';
COMMENT ON COLUMN users.numero_en_attente_jusqu_a IS
    'Echeance de la demande. Une attente sans fin se ferait activer par un code intercepte plus tard.';
