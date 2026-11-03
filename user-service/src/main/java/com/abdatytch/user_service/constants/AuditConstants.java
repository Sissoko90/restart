package com.abdatytch.user_service.constants;

/**

 * Constantes pour le système d'audit
 */
public final class AuditConstants {

    private AuditConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Entity Types
    public static final String ENTITY_TYPE_USER = "USER";
    public static final String ENTITY_TYPE_PROFILE = "PROFILE";
    public static final String ENTITY_TYPE_ROLE = "ROLE";
    public static final String ENTITY_TYPE_STATUS = "STATUS";

    // Status
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /**
     * Auteur inconnu, quand la décision n'a pas de contexte de sécurité.
     *
     * <p>Une valeur explicite, et non l'identifiant du compte visé : l'ancien
     * repli posait {@code userId} dans le champ {@code adminId}, et le journal
     * attribuait donc la fermeture du compte à ce compte lui-même. Un journal
     * d'audit qui désigne la victime comme auteur est pire que muet.
     */
    public static final String ADMIN_INCONNU = "INCONNU";

    // Action Details
    public static final String ACTION_PERMANENT_DELETION = "Fermeture de compte : revocation locale et propagation a auth-service";
    public static final String ACTION_USER_CREATED = "User created by admin";
    public static final String ACTION_USER_UPDATED = "User profile updated by admin";
    public static final String ACTION_USER_BLOCKED = "User blocked by admin";
    public static final String ACTION_USER_UNBLOCKED = "User unblocked by admin";
    public static final String ACTION_ROLE_CHANGED = "User role changed by admin";
    public static final String ACTION_STATUS_CHANGED = "User status changed by admin";

    // Log Messages
    public static final String LOG_AUDIT_ACTION = " Logging audit action: {} by admin {} on user {}";
    public static final String LOG_AUDIT_FAILED = " Logging failed audit action: {} by admin {} on user {} - Error: {}";
    public static final String LOG_FETCHING_USER_LOGS = "Récupération des logs d'audit pour l'utilisateur: {}";
    public static final String LOG_FETCHING_ADMIN_LOGS = "Récupération des logs d'audit pour l'admin: {}";
    public static final String LOG_FETCHING_ALL_LOGS = "Récupération de tous les logs d'audit";
    public static final String LOG_FETCHING_BY_ACTION = "Récupération des logs d'audit pour le type d'action: {}";
    public static final String LOG_CLEANUP_LOGS = "Nettoyage des logs d'audit de plus de {} jours";

    // Cleanup
    public static final int DEFAULT_LOGS_RETENTION_DAYS = 90;
}
