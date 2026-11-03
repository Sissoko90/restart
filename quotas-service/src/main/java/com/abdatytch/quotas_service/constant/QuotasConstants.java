package com.abdatytch.quotas_service.constant;

/**

 * Constantes du service de quotas.
 *
 * <p>Convention de la plateforme : aucune chaîne magique dans le code métier.
 */
public final class QuotasConstants {

    private QuotasConstants() {
        // Classe de constantes
    }

    // ==========================================================
    // ROUTES
    // ==========================================================
    public static final String BASE_PATH = "/quotas";
    public static final String ADMIN_BASE_PATH = "/admin/quotas";

    // ==========================================================
    // KAFKA
    // ==========================================================
    public static final String TOPIC_PROJECT_CREATED = "project.created";
    public static final String TOPIC_PROJECT_DELETED = "project.deleted";

    // ==========================================================
    // CODES D'ERREUR
    // ==========================================================
    public static final String ERROR_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String ERROR_QUOTA_NOT_FOUND = "QUOTA_NOT_FOUND";
    public static final String ERROR_INVALID_RESOURCE = "INVALID_RESOURCE";

    // ==========================================================
    // MESSAGES
    // ==========================================================
    public static final String MSG_QUOTA_EXCEEDED =
            "Quota dépassé pour la ressource %s : %d/%d utilisé(s), %d demandé(s)";
    public static final String MSG_QUOTA_NOT_FOUND =
            "Aucun quota enregistré pour l'utilisateur %s";
    public static final String MSG_AMOUNT_MUST_BE_POSITIVE =
            "La quantité demandée doit être strictement positive";

    // ==========================================================
    // LOGS
    // ==========================================================
    public static final String LOG_CONSUME =
            " Consommation {} pour user={} : +{} (total {}/{})";
    public static final String LOG_RELEASE =
            " Libération {} pour user={} : -{} (total {}/{})";
    public static final String LOG_QUOTA_EXCEEDED =
            " Quota {} dépassé pour user={} : {}/{} demandé {}";
    public static final String LOG_WARNING_THRESHOLD =
            " Seuil d'alerte atteint pour {} (user={}) : {}% du quota consommé";
    public static final String LOG_TIER_UPDATED =
            " Palier de l'utilisateur {} passé de {} à {}";
    public static final String LOG_EVENT_RECEIVED =
            " Événement {} reçu pour le projet {}";
}
