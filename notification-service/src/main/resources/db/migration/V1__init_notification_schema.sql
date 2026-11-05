-- ==========================================================
-- notification-service : schéma initial PostgreSQL
-- ==========================================================
-- Deux tables, et la séparation est le point de conception :
--
--   * notification      : CE QUI A ÉTÉ DEMANDÉ. Une ligne par demande,
--                         quels que soient les canaux. C'est aussi la boîte
--                         de réception applicative (canal IN_APP).
--   * remise_par_canal  : CE QUI EST ARRIVÉ, une ligne par canal essayé.
--
-- Pourquoi ne pas tout mettre dans une table : une demande peut viser quatre
-- canaux dont deux aboutissent. Un seul statut par notification obligerait à
-- choisir lequel des quatre il décrit — et la question « ce code de
-- vérification est-il parti par SMS ? » deviendrait sans réponse.
--
-- C'est le même raisonnement que le registre de preuve d'effacement RGPD :
-- affirmer avoir notifié sans pouvoir dire par quel canal, c'est ne rien
-- pouvoir démontrer.
-- ==========================================================

CREATE TABLE IF NOT EXISTS notification (
    id                 UUID         PRIMARY KEY,

    -- Le `sub` du porteur. VARCHAR et non UUID : le contrat partagé
    -- (DemandeDeNotification.destinataireId) est une chaîne, parce qu'un sujet
    -- de jeton n'est pas garanti UUID par la norme. Contraindre la colonne
    -- ferait échouer l'insertion là où le code accepte la valeur.
    destinataire_id    VARCHAR(100) NOT NULL,

    -- Le gabarit et ses variables, et non le texte rendu.
    --
    -- Conserver le texte figerait la langue et la mise en page du jour de
    -- l'envoi, et surtout dupliquerait des données personnelles : les
    -- variables peuvent contenir un nom de projet, un montant. Les garder
    -- séparées permet à l'effacement RGPD de vider `variables` sans perdre la
    -- trace qu'une notification a eu lieu.
    modele             VARCHAR(100) NOT NULL,
    variables          JSONB        NOT NULL DEFAULT '{}'::jsonb,

    urgence            VARCHAR(20)  NOT NULL,
    service_emetteur   VARCHAR(60)  NOT NULL,
    demande_id         UUID         NOT NULL,

    -- Boîte de réception : NULL tant que non lue.
    --
    -- Un horodatage et non un booléen `lue` : « quand » répond aussi à
    -- « est-ce lu », l'inverse est faux. Et un booléen aurait demandé une
    -- seconde colonne le jour où la date devient utile.
    lue_le             TIMESTAMP,

    creee_le           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Une même demande ne doit produire qu'une ligne.
    --
    -- Kafka garantit « au moins une fois » : un même message peut être
    -- consommé deux fois après un redémarrage. Sans cette contrainte,
    -- l'utilisateur verrait la notification en double dans sa boîte. C'est le
    -- verrou d'idempotence du consommateur.
    CONSTRAINT uk_notification_demande UNIQUE (demande_id)
);

-- La requête de la boîte de réception : les notifications d'une personne, les
-- plus récentes d'abord. L'index porte les deux colonnes dans cet ordre
-- précis — filtrer puis trier — sinon PostgreSQL trie après coup.
CREATE INDEX IF NOT EXISTS idx_notification_destinataire
    ON notification (destinataire_id, creee_le DESC);

-- Le décompte des non lues, affiché sur chaque écran : un index partiel, qui
-- ne contient que les lignes concernées. Sur une table où la plupart des
-- notifications finissent lues, il reste petit là où un index complet
-- grossirait indéfiniment.
CREATE INDEX IF NOT EXISTS idx_notification_non_lues
    ON notification (destinataire_id) WHERE lue_le IS NULL;

-- ==========================================================

CREATE TABLE IF NOT EXISTS remise_par_canal (
    id                 UUID         PRIMARY KEY,
    notification_id    UUID         NOT NULL REFERENCES notification (id) ON DELETE CASCADE,

    canal              VARCHAR(20)  NOT NULL,

    -- REMIS, REFUSE, ECHOUE, SANS_DESTINATAIRE.
    --
    -- Les quatre, et non un booléen : REFUSE ne se répare pas en réessayant,
    -- ECHOUE peut-être, et SANS_DESTINATAIRE — WEBSOCKET sans session ouverte
    -- — est le fonctionnement normal. Les confondre produirait soit des
    -- réessais infinis, soit des alertes sur du trafic sain.
    etat               VARCHAR(20)  NOT NULL,

    -- Le motif d'un échec, SANS donnée personnelle : ni adresse, ni numéro, ni
    -- jeton. Cette colonne est lue par l'exploitant et peut partir dans les
    -- journaux.
    motif              TEXT,

    tentatives         SMALLINT     NOT NULL DEFAULT 1,
    essayee_le         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Un seul enregistrement par couple (notification, canal) : les tentatives
    -- s'incrémentent sur la ligne existante au lieu d'en créer une nouvelle.
    -- Sinon un canal en panne qui réessaie toutes les minutes ferait grossir
    -- la table sans borne.
    CONSTRAINT uk_remise_notification_canal UNIQUE (notification_id, canal)
);

CREATE INDEX IF NOT EXISTS idx_remise_notification
    ON remise_par_canal (notification_id);

-- Les remises à reprendre : état ECHOUE seulement.
--
-- Index partiel, pour la même raison que celui des non lues : la reprise ne
-- s'intéresse qu'aux pannes, et elles sont rares. REFUSE en est exclu
-- volontairement — le réessayer ne changerait rien.
CREATE INDEX IF NOT EXISTS idx_remise_a_reprendre
    ON remise_par_canal (essayee_le) WHERE etat = 'ECHOUE';
