package com.abdatytch.user_service.constants;

/**
 * Constantes pour la couche Web (Controllers)
 */
public final class WebConstants {
    
    private WebConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // Log Messages - INFO level
    public static final String LOG_GET_CURRENT_USER = "GET /users/me - Fetching current user with authUserId: {}";
    public static final String LOG_GET_PROFILE_PICTURE = "GET /users/me/profile-picture - Getting profile picture for authUserId: {}";
    public static final String LOG_POST_PROFILE_PICTURE = "POST /users/me/profile-picture - Uploading profile picture for authUserId: {}";
    public static final String LOG_PUT_CURRENT_USER = "PUT /users/me - Updating current user with authUserId: {}";
    public static final String LOG_PROFILE_PICTURE_UPLOADED = "Profile picture uploaded successfully: {}";
    public static final String LOG_CURRENT_USER_UPDATED = "Current user updated successfully: {}";
    public static final String LOG_SEARCH_COMPLETED = "Search completed: {} results found";
    public static final String LOG_RETRIEVED_USERS = "Retrieved {} users";
    
    // Log Messages - DEBUG level
    // Log Messages - WARN level
    public static final String LOG_WARN_FILE_NOT_FOUND = "File not found or not readable: {}";
    
    // Log Messages - ERROR level
    public static final String LOG_ERROR_SERVING_FILE = "Error serving file: {}/{}";
    
    // Admin Controller Logs
    public static final String LOG_ADMIN_BLOCK_USER = "POST /admin/users/{}/block - Admin blocking user with reason: {}";
    public static final String LOG_ADMIN_SUSPEND_USER = "POST /admin/users/{}/suspend - Admin suspending user until: {}";
    public static final String LOG_ADMIN_BAN_USER = "POST /admin/users/{}/ban - Admin banning user with reason: {}";
    public static final String LOG_ADMIN_REVOKE_USER = "POST /admin/users/{}/revoke - Admin revoking user access";
    public static final String LOG_ADMIN_RESTORE_USER = "POST /admin/users/{}/restore - Admin restoring user";
    // Internal Controller Logs
    public static final String LOG_INTERNAL_CREATE_USER = "[INTERNAL] POST /internal/users - Creating user from service call for authUserId: {}";
    public static final String LOG_INTERNAL_USER_CREATED = "[INTERNAL] User created successfully with ID: {}";
    public static final String LOG_INTERNAL_GET_USER_BY_AUTH_ID = "[INTERNAL] GET /internal/users/auth/{} - Fetching user by authUserId";
    public static final String LOG_INTERNAL_USER_RETRIEVED = "[INTERNAL] User retrieved: {}";
    public static final String LOG_INTERNAL_UPDATE_LAST_LOGIN = "[INTERNAL] POST /internal/users/auth/{}/login - Updating last login";
    public static final String LOG_INTERNAL_LAST_LOGIN_UPDATED = "[INTERNAL] Last login updated for authUserId: {}";
    
    // Response Messages
    public static final String MSG_NO_PROFILE_PICTURE = "Aucune photo de profil définie";
    public static final String MSG_PROFILE_PICTURE_UPLOADED = "Photo de profil uploadée avec succès";
    
    // Log Messages
    public static final String LOG_UPLOAD_PROFILE_PICTURE = "Upload photo de profil pour utilisateur: {}";
    
    // Response Keys
    public static final String KEY_ERROR = "error";
    
    // Exception Messages
    // Response Keys
    public static final String KEY_PROFILE_PICTURE_URL = "profilePictureUrl";
    public static final String KEY_MESSAGE = "message";
    
    // Comments
    public static final String COMMENT_EMAIL_PHONE_REMOVED = "Note: /email/{email} et /phone/{phoneNumber} retirés";
    public static final String COMMENT_SEARCH_VIA_AUTH_SERVICE = "Pour rechercher par email/phone, utiliser auth-service";
    /** Autorité portée par un administrateur (le préfixe ROLE_ vient de l'émetteur). */
    public static final String COMMENT_ROLE_FILTER_REMOVED = "Note: /role/{role} retiré car role est dans auth-service";
    public static final String COMMENT_FILTER_VIA_AUTH_SERVICE = "Pour filtrer par role, utiliser auth-service";
}
