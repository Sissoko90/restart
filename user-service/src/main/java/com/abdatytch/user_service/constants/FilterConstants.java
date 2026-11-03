package com.abdatytch.user_service.constants;

/**

 * Constantes pour les filtres
 */
public final class FilterConstants {

    private FilterConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Header Names
    public static final String HEADER_SECRET_KEY = "X-Secret-Key";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_CONTENT_DISPOSITION = "Content-Disposition";

    // Token Prefix
    public static final String BEARER_PREFIX = "Bearer ";
    public static final int BEARER_PREFIX_LENGTH = 7;

    // Paths
    public static final String PATH_ACTUATOR = "actuator";
    public static final String PATH_ERROR = "/error";

    // Log Messages - INFO level
    public static final String LOG_INFO_PUBLIC_KEY_LOADED = " Clé publique RSA chargée avec succès pour la vérification des JWT";

    // Log Messages - DEBUG level
    public static final String LOG_DEBUG_REQUEST_AUTHORIZED = " Requête autorisée avec clé valide - URI: {}";
    public static final String LOG_DEBUG_ROLES_EXTRACTED = "Roles extracted from JWT: {}";
    public static final String LOG_DEBUG_USER_HAS_ADMIN = "User has ADMIN role: {}";

    // Log Messages - WARN level
    public static final String LOG_WARN_ACCESS_DENIED = " Accès refusé - Clé secrète invalide ou absente - URI: {}";
    public static final String LOG_WARN_NO_TOKEN = "No Authorization token found in request";

    // Log Messages - ERROR level
    public static final String LOG_ERROR_JWT_EXPIRED = "JWT token expired: {}";
    public static final String LOG_ERROR_INVALID_SIGNATURE = "Invalid JWT signature: {}";
    public static final String LOG_ERROR_EXTRACTING_ROLE = "Error extracting role from JWT: {} - {}";
    public static final String LOG_ERROR_EXTRACTING_USER_ID = "Error extracting userId from JWT: {} - {}";

    // Error Response Keys
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_STATUS = "status";
    public static final String KEY_ERROR = "error";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_PATH = "path";
    public static final String KEY_REASON = "reason";
    public static final String KEY_DETAILS = "details";

    // Error Response Values
    public static final String ERROR_FORBIDDEN = "Forbidden";
    public static final int STATUS_FORBIDDEN = 403;
    public static final String MSG_ACCESS_DENIED = " Accès refusé. Clé secrète requise.";
    public static final String MSG_INVALID_OR_MISSING_KEY = "Header X-Secret-Key invalide ou absent";
    public static final String MSG_SECRET_KEY_REQUIRED = "Contacter le developpeur pour obtenir plus d'informations.";

    // Encoding
    public static final String ENCODING_UTF8 = "UTF-8";

    // Exception Messages
    public static final String EX_TOKEN_MISSING = "Token d'authentification manquant. Veuillez fournir un token JWT valide.";
    public static final String EX_TOKEN_EXPIRED = "Token JWT expiré. Veuillez vous reconnecter.";
    public static final String EX_INVALID_SIGNATURE = "Signature du token JWT invalide. Token non autorisé.";
    public static final String EX_INVALID_TOKEN = "Token JWT invalide: %s";

    // Public Key Processing
    public static final String PUBLIC_KEY_BEGIN = "-----BEGIN PUBLIC KEY-----";
    public static final String PUBLIC_KEY_END = "-----END PUBLIC KEY-----";
    public static final String REGEX_WHITESPACE = "\\s";
    public static final String ALGORITHM_RSA = "RSA";

    // JWT Claims
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_USER_ID = "userId";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    // Comments
    public static final String COMMENT_ARCHITECTURE_CLIENT = "Client  Gateway (avec JWT)";
    public static final String COMMENT_ARCHITECTURE_GATEWAY = "Gateway  Service (avec JWT + X-Secret-Key)";
    public static final String COMMENT_ARCHITECTURE_NO_KEY = "Si X-Secret-Key absent  403 Forbidden";
    public static final String COMMENT_JWT_VALIDATED = "Note: Le JWT a déjà été validé par le Gateway";
}
