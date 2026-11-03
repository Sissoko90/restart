package com.abdatytch.user_service.constants;

/**

 * Constantes pour le Rate Limiting
 * Centralise tous les textes et configurations liés à la limitation de débit
 */
public final class RateLimitConstants {

    private RateLimitConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // ==========================================
    // LIMITES DE DÉBIT
    // ==========================================

    // Endpoints publics (actuator, health)
    public static final int PUBLIC_LIMIT_PER_PERIOD = 100;
    public static final int PUBLIC_PERIOD_MINUTES = 1;
    public static final int PUBLIC_TIMEOUT_MS = 100;

    // Endpoints utilisateur authentifiés
    public static final int USER_LIMIT_PER_PERIOD = 500;
    public static final int USER_PERIOD_MINUTES = 1;
    public static final int USER_TIMEOUT_MS = 100;

    // Endpoints admin
    public static final int ADMIN_LIMIT_PER_PERIOD = 200;
    public static final int ADMIN_PERIOD_MINUTES = 1;
    public static final int ADMIN_TIMEOUT_MS = 100;

    // Upload de fichiers
    public static final int FILE_UPLOAD_LIMIT_PER_PERIOD = 10;
    public static final int FILE_UPLOAD_PERIOD_HOURS = 1;
    public static final int FILE_UPLOAD_TIMEOUT_MS = 500;

    // ==========================================
    // NOMS DES RATE LIMITERS
    // ==========================================

    public static final String LIMITER_PUBLIC_ENDPOINTS = "publicEndpoints";
    public static final String LIMITER_USER_ENDPOINTS = "userEndpoints";
    public static final String LIMITER_ADMIN_ENDPOINTS = "adminEndpoints";
    public static final String LIMITER_FILE_UPLOAD = "fileUpload";

    // ==========================================
    // PATHS
    // ==========================================

    public static final String PATH_ACTUATOR = "/actuator/";
    public static final String PATH_ADMIN = "/api/v1/admin/";
    public static final String PATH_USERS = "/api/v1/users/";
    public static final String PATH_PICTURE = "/picture";
    public static final String PATH_UPLOAD = "/upload";

    // ==========================================
    // HEADERS
    // ==========================================

    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String HEADER_X_REAL_IP = "X-Real-IP";
    public static final String HEADER_RETRY_AFTER = "X-RateLimit-Retry-After-Seconds";

    // ==========================================
    // MESSAGES DE LOG
    // ==========================================

    // INFO
    public static final String LOG_INFO_PUBLIC_CONFIGURED = " Rate Limiter configuré: %d req/%dmin pour endpoints publics";
    public static final String LOG_INFO_USER_CONFIGURED = " Rate Limiter configuré: %d req/%dmin pour endpoints utilisateur";
    public static final String LOG_INFO_ADMIN_CONFIGURED = " Rate Limiter configuré: %d req/%dmin pour endpoints admin";
    public static final String LOG_INFO_FILE_CONFIGURED = " Rate Limiter configuré: %d uploads/%dheure pour fichiers";

    // WARN
    public static final String LOG_WARN_LIMIT_EXCEEDED = " Rate limit exceeded for IP: {} on path: {}";
    public static final String LOG_WARN_LIMIT_EXCEEDED_EXCEPTION = " Rate limit exceeded (exception) for IP: {} on path: {}";

    // ==========================================
    // VALEURS PAR DÉFAUT
    // ==========================================

    public static final String DEFAULT_CLIENT_IP = "unknown";
    public static final String DEFAULT_RETRY_AFTER_SECONDS = "60";
    public static final String SEPARATOR_COMMA = ",";
    public static final int FIRST_IP_INDEX = 0;
}
