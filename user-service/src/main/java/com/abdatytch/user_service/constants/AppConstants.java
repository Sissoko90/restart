package com.abdatytch.user_service.constants;

public final class AppConstants {
    
    private AppConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // Cache names
    public static final String CACHE_USERS = "users";
    
    // Cache TTL (in seconds)
    public static final long CACHE_TTL_USERS = 900; // 15 minutes
    
    // Messages d'erreur
    public static final String ERROR_USER_NOT_FOUND = "Utilisateur introuvable avec l'ID: %s";
    public static final String ERROR_USER_NOT_FOUND_AUTH_ID = "Utilisateur introuvable avec authUserId: %s";
    public static final String ERROR_USER_NOT_FOUND_EMAIL = "Utilisateur introuvable avec l'email: %s";
    public static final String ERROR_USER_NOT_FOUND_PHONE = "Utilisateur introuvable avec le téléphone: %s";
    public static final String ERROR_USER_ALREADY_EXISTS_AUTH_ID = "Utilisateur existe déjà avec authUserId: %s";
    public static final String ERROR_USER_ALREADY_EXISTS_EMAIL = "Utilisateur existe déjà avec l'email: %s";
    
    // Messages de succès
    public static final String SUCCESS_USER_CREATED = "Utilisateur créé avec succès avec l'ID: %s";
    public static final String SUCCESS_USER_UPDATED = "Utilisateur mis à jour avec succès: %s";
    public static final String SUCCESS_USER_DELETED = "Utilisateur supprimé (soft delete) avec succès: %s";
    public static final String SUCCESS_USER_BLOCKED = "Utilisateur bloqué avec succès: %s";
    public static final String SUCCESS_USER_UNBLOCKED = "Utilisateur débloqué avec succès: %s";
    public static final String SUCCESS_USER_SUSPENDED = "Utilisateur suspendu avec succès: %s";
    public static final String SUCCESS_USER_BANNED = "Utilisateur banni avec succès: %s";
    public static final String SUCCESS_USER_REVOKED = "Accès utilisateur révoqué avec succès: %s";
    
    // Messages de log - niveau INFO
    public static final String LOG_CREATING_USER = "Création utilisateur avec authUserId: {}";
    public static final String LOG_UPDATING_USER = "Mise à jour utilisateur avec ID: {}";
    public static final String LOG_DELETING_USER = "Suppression soft de l'utilisateur avec ID: {}";
    public static final String LOG_BLOCKING_USER = "Blocage utilisateur avec ID: {}";
    public static final String LOG_UNBLOCKING_USER = "Déblocage utilisateur avec ID: {}";
    public static final String LOG_SUSPENDING_USER = "Suspension utilisateur avec ID: {} jusqu'à {}";
    public static final String LOG_BANNING_USER = "Bannissement utilisateur avec ID: {}";
    public static final String LOG_REVOKING_USER = "Révocation accès utilisateur avec ID: {}";
    
    // Messages de log - niveau DEBUG
    public static final String LOG_DEBUG_FETCHING_USER_BY_ID = "Récupération utilisateur par ID: {}";
    public static final String LOG_DEBUG_USER_FOUND_BY_ID = "Utilisateur trouvé avec ID: {}";
    public static final String LOG_DEBUG_FETCHING_USER_BY_AUTH_ID = "Récupération utilisateur par authUserId: {}";
    public static final String LOG_DEBUG_USER_FOUND_BY_AUTH_ID = "Utilisateur trouvé avec authUserId: {}";
    public static final String LOG_DEBUG_UPDATE_REQUEST = "Requête de mise à jour pour utilisateur {}: {}";
    public static final String LOG_DEBUG_SOFT_DELETING_USER = "Suppression soft de l'utilisateur avec ID: {}";
    public static final String LOG_DEBUG_SEARCHING_USERS = "Recherche utilisateurs avec requête: {} et pageable: {}";
    public static final String LOG_DEBUG_FOUND_USERS = "Trouvé {} utilisateurs correspondant à la requête: {}";
    public static final String LOG_DEBUG_UPDATE_LAST_LOGIN = "updateLastLogin appelé pour authUserId: {} - lastLogin géré par auth-service";
    
    // Messages de log - niveau WARN
    public static final String LOG_WARN_USER_CREATION_FAILED = "Échec création utilisateur: authUserId existe déjà: {}";
    
    // Messages de log - niveau ERROR
    public static final String LOG_ERROR_USER_NOT_FOUND_BY_ID = "Utilisateur introuvable avec ID: {}";
    public static final String LOG_ERROR_USER_NOT_FOUND_BY_AUTH_ID = "Utilisateur introuvable avec authUserId: {}";
    public static final String LOG_ERROR_USER_NOT_FOUND_FOR_UPDATE = "Utilisateur introuvable pour mise à jour avec ID: {}";
    public static final String LOG_ERROR_USER_NOT_FOUND_FOR_DELETION = "Utilisateur introuvable pour suppression avec ID: {}";
    public static final String LOG_ERROR_CIRCUIT_BREAKER_FALLBACK_ID = "Circuit breaker fallback déclenché pour getUserById avec id: {}";
    public static final String LOG_ERROR_CIRCUIT_BREAKER_FALLBACK_AUTH_ID = "Circuit breaker fallback déclenché pour getUserByAuthUserId avec authUserId: {}";
    
    // Raisons d'actions admin
    public static final String REASON_REVOKED_BY_ADMIN = "Accès révoqué par l'administrateur";
    
    // Messages d'exception
    public static final String EX_USER_NOT_FOUND = "Utilisateur introuvable avec ID: %s";
    public static final String EX_USER_NOT_FOUND_AUTH_ID = "Utilisateur introuvable avec authUserId: %s";
    public static final String EX_USER_NOT_FOUND_EMAIL = "Utilisateur introuvable avec email: %s";
    public static final String EX_USER_NOT_FOUND_PHONE = "Utilisateur introuvable avec téléphone: %s";
    public static final String EX_USER_ALREADY_EXISTS = "Utilisateur existe déjà avec authUserId: %s";
    public static final String EX_USER_ALREADY_EXISTS_EMAIL = "Utilisateur existe déjà avec email: %s";
    public static final String EX_INVALID_TOKEN = "Token invalide ou expiré: %s";
    public static final String EX_TOKEN_VALIDATION_FAILED = "Échec validation token: %s";
    public static final String EX_UNAUTHORIZED_ACCESS = "Tentative d'accès non autorisé";
    public static final String EX_CIRCUIT_BREAKER_FALLBACK = "Service temporairement indisponible, utilisation du fallback";
    
    // Comments
    public static final String COMMENT_EMAIL_PHONE_IN_AUTH_SERVICE = "Note: email et phone sont vérifiés dans auth-service (source de vérité)";
    public static final String COMMENT_FIRSTNAME_LASTNAME_IN_AUTH_SERVICE = "Note: firstName et lastName sont dans auth-service (source de vérité)";
    public static final String COMMENT_PROFILE_PICTURE_MANAGED_BY_ENDPOINT = "profilePictureUrl géré par POST /users/me/profile-picture";
    public static final String COMMENT_LAST_LOGIN_IN_AUTH_SERVICE = "Note: lastLogin est géré dans auth-service (source de vérité)";
    
    // Resilience4j instance names
    public static final String RESILIENCE4J_INSTANCE_USER_SERVICE = "userService";
}
