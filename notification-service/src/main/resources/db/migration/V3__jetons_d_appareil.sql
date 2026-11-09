-- ==========================================================
-- notification-service : jetons d'appareil (push FCM)
-- ==========================================================
-- Un push ne s'adresse pas a une personne : il s'adresse a un APPAREIL. Une
-- meme personne en a autant qu'elle possede de telephones, de tablettes et de
-- navigateurs, et chacun porte un jeton distinct, renouvele par le systeme.
--
-- C'est pourquoi cette table est locale a ce service et non lue chez
-- user-service : un jeton d'appareil n'est pas une coordonnee de profil, il
-- n'a aucun sens hors du canal push, et il change sans que la personne touche
-- a son compte.
--
-- CONSEQUENCE HEUREUSE : un push ne depend plus de la disponibilite de
-- user-service. Jusqu'ici le resolveur allait y chercher un NUMERO DE
-- TELEPHONE et le passait au canal push comme s'il s'agissait d'un jeton —
-- ce qui ne pouvait aboutir, et que le code admettait en commentaire.
-- ==========================================================

CREATE TABLE IF NOT EXISTS jeton_d_appareil (
    id              UUID         PRIMARY KEY,

    -- Le `sub` du porteur. VARCHAR et non UUID, pour la meme raison que dans
    -- la table notification : un sujet de jeton n'est pas garanti UUID par la
    -- norme, et contraindre la colonne ferait echouer l'insertion la ou le
    -- code accepte la valeur.
    destinataire_id VARCHAR(100) NOT NULL,

    -- Le jeton d'enregistrement FCM. TEXT et non VARCHAR borne : sa longueur
    -- n'est pas specifiee par Google et a deja change plusieurs fois. Une borne
    -- devinee se decouvre en production, au moment ou un appareil ne peut plus
    -- s'enregistrer.
    jeton           TEXT         NOT NULL,

    -- ANDROID, IOS, WEB. Sert au diagnostic et a la purge : un jeton Web
    -- n'expire pas comme un jeton mobile.
    plateforme      VARCHAR(20)  NOT NULL,

    enregistre_le   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Derniere fois que l'appareil s'est manifeste. Un client reenregistre son
    -- jeton a chaque demarrage : cette date distingue un appareil vivant d'un
    -- jeton laisse derriere par une application desinstallee.
    vu_le           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- UNICITE SUR LE JETON SEUL, et non sur (destinataire, jeton).
    --
    -- C'est une propriete de SECURITE, pas une commodite. Un jeton designe un
    -- APPAREIL : si une seconde personne se connecte sur le meme telephone,
    -- FCM lui attribue le meme jeton. Avec une unicite par couple, les deux
    -- lignes coexisteraient et l'appareil continuerait de recevoir les
    -- notifications du PREMIER proprietaire — y compris ses codes de
    -- verification et ses alertes de securite.
    --
    -- Avec cette contrainte, l'enregistrement DEPLACE le jeton vers son nouveau
    -- proprietaire au lieu d'en creer un second. Voir
    -- JetonDAppareilRepository.enregistrer.
    CONSTRAINT uk_jeton_d_appareil UNIQUE (jeton)
);

-- La requete du canal push : tous les jetons d'une personne.
CREATE INDEX IF NOT EXISTS idx_jeton_destinataire
    ON jeton_d_appareil (destinataire_id);

-- La purge des jetons dormants, par date.
--
-- Index separe de celui du destinataire : la purge balaie par ANCIENNETE, sans
-- connaitre de destinataire, et l'index ci-dessus ne lui servirait pas.
CREATE INDEX IF NOT EXISTS idx_jeton_vu_le
    ON jeton_d_appareil (vu_le);
