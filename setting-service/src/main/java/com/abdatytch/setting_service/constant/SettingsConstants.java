package com.abdatytch.setting_service.constant;

/**

 * Constantes du service de paramètres.
 */
public final class SettingsConstants {

    private SettingsConstants() {
        // Classe de constantes
    }

    // ==========================================================
    // ROUTES
    // ==========================================================
    public static final String BASE_PATH = "/settings";
    public static final String ADMIN_BASE_PATH = "/admin/settings";

    // ==========================================================
    // CODES D'ERREUR
    // ==========================================================
    public static final String ERROR_SETTING_NOT_FOUND = "SETTING_NOT_FOUND";
    public static final String ERROR_INVALID_VALUE = "INVALID_SETTING_VALUE";
    public static final String ERROR_READ_ONLY = "SETTING_READ_ONLY";

    // ==========================================================
    // MESSAGES
    // ==========================================================
    public static final String MSG_SETTING_NOT_FOUND = "Paramètre inconnu : %s";
    public static final String MSG_INVALID_VALUE = "Valeur « %s » incompatible avec le type %s du paramètre %s";
    public static final String MSG_READ_ONLY = "Le paramètre %s est en lecture seule";

    // ==========================================================
    // LOGS
    // ==========================================================
    public static final String LOG_SYSTEM_UPDATED = " Paramètre système {} modifié : {} -> {} (par {})";
    public static final String LOG_SYSTEM_RESET = " Paramètre système {} réinitialisé à sa valeur par défaut ({})";
    public static final String LOG_USER_UPDATED = " Paramètre {} de l'utilisateur {} modifié : {}";
    public static final String LOG_USER_DELETED = " Paramètre {} de l'utilisateur {} supprimé";
    // LOG_MAINTENANCE_ON / LOG_MAINTENANCE_OFF ont été retirés avec le
    // paramètre system.maintenance-mode. Ils annonçaient en WARN une coupure
    // qui n'avait aucun effet : personne ne lisait ce booléen. La coupure réelle
    // est journalisée par security-service et appliquée par le gateway.

    // ==========================================================
    // CLÉS SENSIBLES
    // ==========================================================
    /** Activer ce paramètre coupe l'accès à la plateforme : tracé explicitement. */
    // KEY_MAINTENANCE_MODE a été retirée : voir SettingsProperties.catalog().
}
