package com.abdatytch.setting_service.enums;

/**
 * Regroupement fonctionnel d'un paramètre système.
 *
 * <p>Correspond aux blocs de {@code config-repo/dev/setting-service-dev.yml} :
 * {@code settings.system}, {@code settings.security}, {@code settings.notifications}.
 */
public enum SettingCategory {

    /** Comportement général de la plateforme (maintenance, inscriptions...). */
    SYSTEM,

    // SECURITY a été RETIRÉE : la politique de sécurité est portée par
    // security-service, typée, bornée et publiée dans Redis. La garder ici
    // aurait laissé deux magasins pour une même décision — le défaut qui a fait
    // qu'un verrouillage administrateur ne verrouillait personne dans ce dépôt.
    //
    // La valeur n'est pas conservée en obsolète : une catégorie sans aucune clé
    // laisserait croire qu'il existe des paramètres de sécurité ici, et une
    // interface les afficherait comme une section vide.

    /** Canaux de notification activés. */
    NOTIFICATIONS
}
