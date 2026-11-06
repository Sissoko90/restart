-- ============================================================================
-- Retrait des interrupteurs de notification : ils appartiennent au service qui
-- les applique
-- ============================================================================
-- Trois cles systeme quittent ce catalogue :
--
--   notifications.email-enabled
--   notifications.sms-enabled
--   notifications.push-enabled
--
-- ----------------------------------------------------------------------------
-- Pourquoi : elles etaient lues par PERSONNE
-- ----------------------------------------------------------------------------
-- Releve fait sur tout le depot : ces trois cles sont ecrites dans le catalogue
-- par SettingsProperties, exposees par l'API d'administration, modifiables par
-- un administrateur — et aucune ligne de code ne les consulte.
--
-- Un administrateur qui desactivait les SMS depuis l'interface ne changeait donc
-- RIEN : les SMS continuaient de partir, et de se payer. Un interrupteur qui ne
-- coupe rien est pire qu'un interrupteur absent, parce qu'on croit l'avoir
-- actionne.
--
-- ----------------------------------------------------------------------------
-- Ou vit l'interrupteur maintenant
-- ----------------------------------------------------------------------------
-- La ou il est APPLIQUE, c'est-a-dire dans notification-service et sa
-- configuration :
--
--   notifications.email-enabled -> notification.email.from renseigne ou non
--                                  (CanalCourriel refuse sans expediteur)
--   notifications.sms-enabled   -> sms.enabled, dans application-dev.yml
--   notifications.push-enabled  -> notification.push.enabled
--
-- Et ces valeurs sont desormais modifiables A CHAUD : le config-repo est
-- rechargeable par POST /actuator/refresh, et les beans concernes portent
-- @RefreshScope. Couper un canal pendant un incident reste donc une operation
-- de deux gestes, sans redemarrage — et cette fois elle coupe reellement.
--
-- ----------------------------------------------------------------------------
-- Ce qui RESTE dans ce service : le consentement par personne
-- ----------------------------------------------------------------------------
-- Les cles `notifications.canal.<canal>` du magasin PAR UTILISATEUR ne sont pas
-- concernees. Elles ne disent pas « la plateforme autorise ce canal » mais « ma
-- personne accepte ce canal », et ce sont deux questions differentes. Les
-- confondre ferait d'une preference individuelle un interrupteur general.
--
-- Leur convention de nommage vit dans ConsentementDeNotification, de
-- platform-commons, parce que trois endroits doivent s'y accorder : le service
-- qui lit, celui qui stocke, et l'interface qui ecrit.
--
-- ----------------------------------------------------------------------------
-- Pourquoi archiver au lieu de supprimer
-- ----------------------------------------------------------------------------
-- Meme raison que V2 : une cle absente du catalogue fait lever
-- requireDefinition, donc sa ligne devient illisible et non supprimable PAR
-- L'API tout en restant en base. Elle disparait de la liste sans laisser de
-- trace.
--
-- Ici l'enjeu est plus petit que pour la politique de securite — ces valeurs
-- n'avaient aucun effet — mais la DECISION de l'administrateur, elle, comptait :
-- il a voulu couper un canal. L'archive garde cette intention, sa date et son
-- auteur, pour qu'elle soit reportee dans le config-repo plutot que perdue.
-- ============================================================================

CREATE TABLE IF NOT EXISTS system_setting_notifications_archive (
    setting_key   VARCHAR(120) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    -- La propriete de configuration qui porte desormais cette decision.
    cible         VARCHAR(250),
    updated_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    archive_le    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE system_setting_notifications_archive IS
    'Interrupteurs de notification archives avant retrait. Ces cles n''avaient aucun effet : la valeur est conservee pour que l''intention de l''administrateur soit reportee dans le config-repo, puis cette table peut etre videe.';

-- Archive : seules les lignes REELLEMENT surchargees existent dans
-- system_setting, le catalogue fournissant les defauts sans ecrire en base.
--
-- ON CONFLICT DO NOTHING : la migration doit pouvoir etre rejouee sans ecraser
-- une archive deja faite, ni la date a laquelle elle l'a ete.
INSERT INTO system_setting_notifications_archive
    (setting_key, setting_value, cible, updated_by, updated_at)
SELECT s.setting_key,
       s.setting_value,
       CASE s.setting_key
           WHEN 'notifications.email-enabled'
               THEN 'notification-service : notification.email.from'
           WHEN 'notifications.sms-enabled'
               THEN 'application-dev.yml : sms.enabled'
           WHEN 'notifications.push-enabled'
               THEN 'notification-service : notification.push.enabled'
       END,
       s.updated_by,
       s.updated_at
FROM system_setting s
WHERE s.setting_key IN (
    'notifications.email-enabled',
    'notifications.sms-enabled',
    'notifications.push-enabled'
)
ON CONFLICT (setting_key) DO NOTHING;

DELETE FROM system_setting
WHERE setting_key IN (
    'notifications.email-enabled',
    'notifications.sms-enabled',
    'notifications.push-enabled'
);
