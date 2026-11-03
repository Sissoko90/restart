-- ============================================================================
-- Retrait de la politique de securite : elle appartient a security-service
-- ============================================================================
-- Les bases sont SEPAREES par service — setting_service_db d'un cote,
-- security_service_db de l'autre. Cette migration ne peut donc pas copier les
-- valeurs : elle les ARCHIVE sur place, et l'operateur les reporte.
--
-- ----------------------------------------------------------------------------
-- Pourquoi archiver au lieu de supprimer
-- ----------------------------------------------------------------------------
-- Une cle absente du catalogue fait lever requireDefinition : la ligne devient
-- illisible et non supprimable PAR L'API, tout en restant en base. Elle
-- disparait simplement de la liste, sans trace.
--
-- Supprimer sans archiver perdrait une decision d'administrateur — un seuil de
-- verrouillage porte a 10, une verification d'adresse rendue obligatoire. La
-- table d'archive garde la valeur, son auteur et sa date, de sorte que le
-- report dans security-service soit verifiable.
--
-- C'est « effacer n'est pas supprimer la ligne », applique ici.
--
-- ----------------------------------------------------------------------------
-- Ce qui est deplace, et vers quelle cle
-- ----------------------------------------------------------------------------
-- Meme cle :
--   security.max-login-attempts
--   security.lockout-duration-minutes
--   security.password-expiry-days
--   security.force-password-change
--
-- Cle renommee, parce que ces parametres decidaient de la securite sous
-- l'etiquette « system » :
--   system.email-verification-required -> security.email-confirme-obligatoire
--   system.phone-verification-required -> security.telephone-confirme-obligatoire
--   system.password-complexity-enabled -> security.complexite-mot-de-passe
--   system.two-factor-auth-enabled     -> security.second-facteur-obligatoire-utilisateur
--                                         ET security.second-facteur-obligatoire-admin
--   system.session-timeout-minutes     -> security.duree-session-utilisateur-minutes
--                                         ET security.duree-session-admin-minutes
--
-- Les deux derniers sont DEDOUBLES : un parametre unique forçait a choisir
-- entre gener les clients et exposer l'administration. Le report demande donc
-- une decision, et non une copie — d'ou la colonne « cible » ci-dessous, qui
-- peut en nommer deux.
--
-- system.maintenance-mode n'a pas de cible : il est remplace par les fenetres
-- de maintenance, qui portent un message, une duree et un service. Ce booleen
-- etait de toute façon ecrit, journalise en WARN, et lu par PERSONNE.
-- ============================================================================

CREATE TABLE IF NOT EXISTS system_setting_migre_vers_security (
    setting_key   VARCHAR(120) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    -- Cible dans security-service, ou NULL quand le parametre est remplace par
    -- un mecanisme different plutot que par une cle.
    cible         VARCHAR(250),
    updated_by    VARCHAR(100),
    updated_at    TIMESTAMPTZ,
    archive_le    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE system_setting_migre_vers_security IS
    'Surcharges de politique de securite archivees avant retrait. A reporter dans security-service, puis cette table peut etre videe.';

-- Archive : seules les lignes REELLEMENT surchargees existent dans
-- system_setting, donc cette requete ne copie que des decisions prises.
INSERT INTO system_setting_migre_vers_security
    (setting_key, setting_value, cible, updated_by, updated_at)
SELECT
    s.setting_key,
    s.setting_value,
    CASE s.setting_key
        WHEN 'security.max-login-attempts'        THEN 'security.max-login-attempts'
        WHEN 'security.lockout-duration-minutes'  THEN 'security.lockout-duration-minutes'
        WHEN 'security.password-expiry-days'      THEN 'security.password-expiry-days'
        WHEN 'security.force-password-change'     THEN 'security.force-password-change'
        WHEN 'system.email-verification-required' THEN 'security.email-confirme-obligatoire'
        WHEN 'system.phone-verification-required' THEN 'security.telephone-confirme-obligatoire'
        WHEN 'system.password-complexity-enabled' THEN 'security.complexite-mot-de-passe'
        WHEN 'system.two-factor-auth-enabled'
            THEN 'security.second-facteur-obligatoire-utilisateur + security.second-facteur-obligatoire-admin'
        WHEN 'system.session-timeout-minutes'
            THEN 'security.duree-session-utilisateur-minutes + security.duree-session-admin-minutes'
        -- Remplace par les fenetres de maintenance, pas par une cle.
        WHEN 'system.maintenance-mode'            THEN NULL
    END,
    s.updated_by,
    s.updated_at
FROM system_setting s
WHERE s.setting_key IN (
    'security.max-login-attempts',
    'security.lockout-duration-minutes',
    'security.password-expiry-days',
    'security.force-password-change',
    'system.email-verification-required',
    'system.phone-verification-required',
    'system.password-complexity-enabled',
    'system.two-factor-auth-enabled',
    'system.session-timeout-minutes',
    'system.maintenance-mode'
)
-- La migration doit pouvoir etre rejouee sans ecraser une archive deja faite.
ON CONFLICT (setting_key) DO NOTHING;

-- Retrait : la ligne n'est plus atteignable par l'API, et la laisser ferait
-- croire a une surcharge active lors d'une lecture directe en base.
DELETE FROM system_setting
WHERE setting_key IN (
    'security.max-login-attempts',
    'security.lockout-duration-minutes',
    'security.password-expiry-days',
    'security.force-password-change',
    'system.email-verification-required',
    'system.phone-verification-required',
    'system.password-complexity-enabled',
    'system.two-factor-auth-enabled',
    'system.session-timeout-minutes',
    'system.maintenance-mode'
);
