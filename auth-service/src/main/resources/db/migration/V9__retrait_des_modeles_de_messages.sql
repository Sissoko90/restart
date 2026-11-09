-- ==========================================================
-- auth-service : retrait du moteur de modeles
-- ==========================================================
-- La table message_templates et son moteur quittent ce module. Les textes
-- vivent desormais dans notification-service :
--
--   * modeles-notification.properties, versionne avec le code ;
--   * table surcharge_de_modele, pour les corrections a chaud ;
--   * API PUT /api/v1/admin/modeles, qui remplace l'ancien
--     MessageTemplateController.
--
-- POURQUOI CE RETRAIT. Il y avait DEUX moteurs de modeles sur la plateforme,
-- donc deux exemplaires des memes messages. « Votre code de verification
-- est… » existait ici et la-bas, et les deux textes DIFFERAIENT deja : celui de
-- notification-service dit « Ne le communiquez a personne », celui d'ici ne le
-- disait pas. Deux detenteurs d'un meme texte divergent des la premiere
-- correction, et personne ne lit les deux cote a cote.
--
-- Le nommage de l'enum TemplateType melangeait de plus le CANAL et l'OBJET :
-- SMS_VERIFICATION et EMAIL_VERIFICATION designaient le MEME message sur deux
-- canaux, donc deux textes a tenir d'accord. Avec le couple (modele, canal) de
-- notification-service, l'objet est nomme une fois et le canal n'est qu'une
-- variante : les cinq types se ramenent a trois modeles.
--
-- RENOMMEE, PAS SUPPRIMEE
--
-- Les cinq textes amorces par DataSeeder sont deja dans le fichier de
-- notification-service, donc rien d'essentiel n'est en jeu. Mais l'ancien
-- controleur permettait de MODIFIER ces textes a chaud : un exploitant a
-- peut-etre corrige une formulation qui n'existe nulle part ailleurs.
--
-- Un DROP la perdrait sans recours. Un renommage la garde lisible, coute une
-- seule instruction, et laisse la suppression definitive a une decision prise
-- en connaissance de ce qu'elle contient. Meme choix que
-- V3__retrait_interrupteurs_notifications.sql de setting-service.
-- ==========================================================

-- Les index d'abord : ils suivraient la table au renommage, mais sous leur
-- ancien nom — ce qui ferait echouer la creation d'un index homonyme ailleurs.
-- Les retirer est sans risque : aucune requete ne lit plus cette table, son
-- entite, son depot et son service ayant ete supprimes.
DROP INDEX IF EXISTS idx_template_type;
DROP INDEX IF EXISTS idx_template_active;

-- Le renommage est conditionnel. Sans ce garde, la migration echouerait sur
-- une base ou la table n'existe pas — et Flyway marquerait alors la version en
-- echec, bloquant toutes les suivantes.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_name = 'message_templates'
    ) THEN
        ALTER TABLE message_templates RENAME TO message_templates_archive;

        COMMENT ON TABLE message_templates_archive IS
            'Archive du moteur de modeles retire de auth-service. Les textes '
            'vivent desormais dans notification-service '
            '(modeles-notification.properties et surcharge_de_modele). '
            'Conservee le temps de verifier qu''aucune formulation corrigee a '
            'chaud n''y est restee seule ; supprimable ensuite.';
    END IF;
END
$$;
