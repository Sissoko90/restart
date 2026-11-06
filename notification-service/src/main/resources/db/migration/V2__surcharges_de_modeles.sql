-- ==========================================================
-- notification-service : surcharges de modeles
-- ==========================================================
-- Les modeles restent dans modeles-notification.properties, sur le chemin de
-- classe. Cette table ne porte que les SURCHARGES, et ce choix est le coeur de
-- la conception.
--
-- POURQUOI LE FICHIER RESTE LE SOCLE
--
--   1. Une table vide se comporte EXACTEMENT comme aujourd'hui. Aucune
--      migration n'a a recopier correctement les quinze modeles existants, et
--      il n'existe aucun instant ou le service demarre avec une table a moitie
--      remplie et des notifications sans texte.
--
--   2. Les modeles sont sur le chemin des codes de verification. Les faire
--      dependre de la base rendrait une panne de base equivalente a « plus
--      aucun code ne part ». Le fichier est lu une fois, au demarrage, depuis
--      le chemin de classe : il ne peut pas tomber.
--
--   3. Le fichier est dans git, donc relu et versionne. La table est la voie
--      de correction a chaud — celle qu'on emprunte un dimanche pour corriger
--      une faute dans un texte, sans livraison.
--
-- POURQUOI UNE CLE PLATE ET NON (modele, canal, partie)
--
-- Le moteur resout deja `<modele>.<canal>.<partie>` puis `<modele>.<partie>`
-- dans un Properties, c'est-a-dire sur la cle complete. Stocker la cle
-- complete fait de cette table une simple couche posee sur la MEME resolution.
--
-- Trois colonnes auraient oblige a reecrire la chaine de repli une seconde
-- fois — une fois pour le fichier, une fois pour la base — et ce depot paie
-- deja en boucle le prix de deux mecanismes pour une seule regle.
-- ==========================================================

CREATE TABLE IF NOT EXISTS surcharge_de_modele (
    -- La cle COMPLETE, telle que le moteur l'interroge. Exemple :
    -- `securite.code-de-verification.sms.corps`.
    --
    -- Cle primaire naturelle : il n'y a rien a dire d'une surcharge en dehors
    -- de la cle qu'elle surcharge. Un identifiant technique en plus aurait
    -- autorise DEUX surcharges de la meme cle, et il aurait alors fallu
    -- decider laquelle gagne — exactement le defaut qu'une cle dupliquee
    -- produit dans un fichier .properties, en silence.
    cle          VARCHAR(200) PRIMARY KEY,

    -- Le texte. TEXT et non VARCHAR : un corps de courriel n'a pas de longueur
    -- raisonnable a deviner, et une borne trop basse se decouvre en production
    -- au moment ou l'on corrige un texte dans l'urgence.
    valeur       TEXT         NOT NULL,

    -- Qui a surcharge, et quand. Un texte envoye a des milliers de personnes
    -- modifiable sans trace serait une surface d'abus : il suffirait d'un jeton
    -- d'administration pour changer le contenu d'une alerte de securite.
    --
    -- Le `sub` du porteur, pas son adresse : une adresse ici serait une donnee
    -- personnelle dans une table que l'exploitant consulte librement.
    modifiee_par VARCHAR(100) NOT NULL,
    modifiee_le  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Aucun index supplementaire, et c'est delibere.
--
-- Cette table est lue ENTIEREMENT, par un seul SELECT sans clause WHERE, au
-- demarrage puis periodiquement. Il n'existe aucune requete par valeur ni par
-- auteur. Un index non interroge coute a chaque ecriture et ne sert jamais :
-- ce depot a deja trouve des alertes posees sur des metriques inexistantes,
-- c'est la meme erreur dans l'autre sens.
