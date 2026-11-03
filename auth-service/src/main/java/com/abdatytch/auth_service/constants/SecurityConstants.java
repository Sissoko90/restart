package com.abdatytch.auth_service.constants;

/**
 * Constantes pour la couche Security
 */
public final class SecurityConstants {
    
    private SecurityConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // Headers
    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String HEADER_X_SECRET_KEY = "X-Secret-Key";
    
    // Token Prefixes
    public static final String BEARER_PREFIX = "Bearer ";
    public static final int BEARER_PREFIX_LENGTH = 7;
    
    // Roles
    public static final String ROLE_SERVICE = "ROLE_SERVICE";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";
    public static final String ROLE_USER = "ROLE_USER";
    
    // JWT Claims
    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_PHONE = "phone";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_TYPE = "type";
    public static final String CLAIM_SERVICE_NAME = "serviceName";
    
    // JWT Types
    public static final String JWT_TYPE_SERVICE = "SERVICE";
    public static final String JWT_TYPE_USER = "USER";
    
    // Service Names
    public static final String SERVICE_USER_SERVICE = "user-service";
    public static final String SERVICE_AUTH_SERVICE = "auth-service";
    
    // Log Messages - INFO level
    public static final String LOG_INFO_JWT_FILTER_PROCESSING = "Processing JWT authentication for: {}";
    public static final String LOG_INFO_JWT_VALID = "JWT valide pour l'utilisateur: {}";
    public static final String LOG_INFO_SERVICE_TOKEN_VALID = "Service token valide pour: {}";
    
    // Log Messages - DEBUG level
    public static final String LOG_DEBUG_JWT_EXTRACTION = "Extraction du JWT depuis le header Authorization";
    public static final String LOG_DEBUG_JWT_VALIDATION = "Validation du JWT";
    public static final String LOG_DEBUG_NO_JWT = "Aucun JWT trouvé dans la requête";
    
    // Log Messages - WARN level
    public static final String LOG_WARN_JWT_INVALID = "JWT invalide: {}";
    public static final String LOG_WARN_JWT_EXPIRED = "JWT expiré: {}";
    public static final String LOG_WARN_NO_AUTH_HEADER = "Aucun header Authorization trouvé";
    
    // Log Messages - ERROR level
    public static final String LOG_ERROR_JWT_PROCESSING = "Erreur lors du traitement du JWT: {}";
    public static final String LOG_ERROR_AUTH_FAILED = "Échec de l'authentification: {}";
    
    // Exception Messages
    public static final String EX_JWT_MISSING = "JWT manquant";
    public static final String EX_JWT_INVALID = "JWT invalide";
    public static final String EX_JWT_EXPIRED = "JWT expiré";
    public static final String EX_JWT_SIGNATURE_INVALID = "Signature JWT invalide";
    public static final String EX_UNAUTHORIZED = "Non autorisé";
    
    // Paths
    public static final String PATH_ACTUATOR = "actuator";
    public static final String PATH_ERROR = "/error";
    public static final String PATH_AUTH = "/auth/**";
    public static final String PATH_INTERNAL = "/internal/**";
    public static final String PATH_ADMIN = "/admin/**";
    
    // Comments
    public static final String COMMENT_JWT_VALIDATED = "Note: Le JWT a déjà été validé par le filtre";
    public static final String COMMENT_SERVICE_TOKEN = "Token de service pour communication inter-services";
}
