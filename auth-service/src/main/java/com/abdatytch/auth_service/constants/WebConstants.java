package com.abdatytch.auth_service.constants;

/**
 * Constantes pour la couche Web (Controllers)
 */
public final class WebConstants {
    
    private WebConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }
    
    // Log Messages - INFO level - AuthController
    public static final String LOG_INFO_LOGIN_REQUEST = "POST /auth/login - Tentative de connexion";
    public static final String LOG_INFO_LOGOUT_REQUEST = "POST /auth/logout - Déconnexion";
    public static final String LOG_INFO_REFRESH_TOKEN_REQUEST = "POST /auth/refresh - Rafraîchissement du token";
    public static final String LOG_INFO_FORGOT_PASSWORD_REQUEST = "POST /auth/forgot-password - Demande de réinitialisation";
    public static final String LOG_INFO_RESET_PASSWORD_REQUEST = "POST /auth/reset-password - Réinitialisation du mot de passe";
    public static final String LOG_INFO_UPDATE_PROFILE_REQUEST = "PUT /auth/profile - Mise à jour du profil";
    public static final String LOG_INFO_PHONE_AUTH_REQUEST = "POST /auth/phone/auth-request - Demande d'authentification par téléphone";
    public static final String LOG_INFO_PHONE_AUTH_VERIFY = "POST /auth/phone/auth-verify - Vérification d'authentification par téléphone";
    public static final String LOG_INFO_PHONE_REGISTER_REQUEST = "POST /auth/phone/register-request - Demande d'inscription par téléphone";
    public static final String LOG_INFO_PHONE_REGISTER_VERIFY = "POST /auth/phone/register-verify - Vérification d'inscription par téléphone";
    public static final String LOG_INFO_EMAIL_AUTH_REQUEST = "POST /auth/email/auth-request - Demande d'authentification par email";
    public static final String LOG_INFO_EMAIL_AUTH_VERIFY = "POST /auth/email/auth-verify - Vérification d'authentification par email";
    public static final String LOG_INFO_EMAIL_REGISTER = "POST /auth/email/register - Inscription par email";
    public static final String LOG_INFO_EMAIL_REGISTER_VERIFY = "POST /auth/email/register-verify - Vérification d'inscription par email";
    public static final String LOG_INFO_RESEND_CODE = "POST /auth/resend-code - Renvoi du code de vérification";
    public static final String LOG_INFO_OAUTH2_CALLBACK = "POST /auth/oauth2/callback - Callback OAuth2 - Provider: {}";
    public static final String LOG_INFO_OAUTH2_PROFILE_COMPLETION = "POST /auth/oauth2/complete-profile - Complétion du profil OAuth2";
    public static final String LOG_INFO_GET_PROFILE = "GET /auth/profile - Récupération du profil utilisateur";
    
    // Log Messages - INFO level - InternalAuthController
    /** Ni le secret recu ni le secret attendu ne sont journalises. */
    public static final String LOG_WARN_INTERNAL_SECRET_REFUSE =
            "Appel interne refusé : X-Internal-Secret absent ou invalide";

    public static final String LOG_INFO_INTERNAL_GET_USER = "[INTERNAL] GET /internal/auth/users/{} - Récupération utilisateur";
    public static final String LOG_INFO_INTERNAL_USER_RETRIEVED = "[INTERNAL] Utilisateur récupéré: {}";
    public static final String LOG_INFO_INTERNAL_DELETE_USER = "[INTERNAL] DELETE /internal/auth/users/{} - Suppression utilisateur";
    public static final String LOG_INFO_EFFACEMENT_ENREGISTRE =
            "Effacement de {} enregistre au registre sous {}";
    public static final String EX_EFFACEMENT_NON_ENREGISTRE =
            "Effacement non enregistre au registre pour ";
    public static final String LOG_INFO_INTERNAL_USER_DELETED = "[INTERNAL] Utilisateur supprimé: {}";
    
    // Log Messages - INFO level - MessageTemplateController
    public static final String LOG_INFO_GET_ALL_TEMPLATES = "GET /admin/templates - Récupération de tous les templates";
    public static final String LOG_INFO_GET_TEMPLATE = "GET /admin/templates/{} - Récupération du template";
    public static final String LOG_INFO_CREATE_TEMPLATE = "POST /admin/templates - Création d'un template";
    public static final String LOG_INFO_UPDATE_TEMPLATE = "PUT /admin/templates/{} - Mise à jour du template";
    
    // Log Messages - INFO level - AuthenticationLogController
    public static final String LOG_INFO_GET_ALL_LOGS = "GET /admin/logs - Récupération de tous les logs";
    public static final String LOG_INFO_GET_USER_LOGS = "GET /admin/logs/user/{} - Récupération des logs utilisateur";
    public static final String LOG_INFO_GET_PHONE_LOGS = "GET /admin/logs/phone/{} - Récupération des logs par téléphone";
    public static final String LOG_INFO_GET_LOGS_BY_EVENT_STATUS = "GET /admin/logs/event/{}/status/{} - Récupération des logs par événement et statut";
    public static final String LOG_INFO_CLEANUP_OLD_LOGS = "POST /admin/logs/cleanup - Nettoyage des anciens logs";
    public static final String LOG_INFO_LOGS_CLEANED = "{} logs supprimés";
    
    // Log Messages - DEBUG level
    public static final String LOG_DEBUG_REQUEST_RECEIVED = "Requête reçue: {}";
    public static final String LOG_DEBUG_RESPONSE_SENT = "Réponse envoyée: {}";
    
    // Log Messages - WARN level
    public static final String LOG_WARN_INVALID_REQUEST = "Requête invalide: {}";
    
    // Log Messages - ERROR level
    public static final String LOG_ERROR_REQUEST_FAILED = "Erreur lors du traitement de la requête: {}";
    public static final String LOG_ERROR_OAUTH2_CODE_MISSING = "Code OAuth2 manquant dans la requête";
    
    // HTTP Headers
    public static final String HEADER_USER_AGENT = "User-Agent";
    public static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /**
     * Jeton de rafraichissement de l'appelant, pour qu'il reconnaisse sa propre
     * session.
     *
     * <h2>Pourquoi un en-tete et non un parametre de requete</h2>
     *
     * C'est un justificatif : qui l'obtient renouvelle l'acces au compte. Dans
     * une chaine de requete, il se retrouve dans l'historique du navigateur,
     * dans l'en-tete {@code Referer} et dans les journaux d'acces de tout ce qui
     * est sur le trajet. En en-tete, il reste a sa place — et c'est le meme
     * canal que celui qui porte deja {@code Authorization}.
     *
     * <h2>Pourquoi il est optionnel</h2>
     *
     * Sans lui, la liste des sessions est rendue sans marquer celle de
     * l'appelant. C'est degrade mais pas faux. L'exiger ferait echouer une
     * simple consultation pour un confort d'affichage.
     */
    public static final String HEADER_REFRESH_TOKEN = "X-Refresh-Token";
    
    // OAuth2 Providers
    public static final String OAUTH2_PROVIDER_GOOGLE = "google";
    public static final String OAUTH2_PROVIDER_FACEBOOK = "facebook";
    public static final String OAUTH2_PROVIDER_APPLE = "apple";
    
    // Exception Messages
    public static final String EX_OAUTH2_CODE_MISSING = "Code OAuth2 manquant";
    public static final String EX_UNKNOWN_PROVIDER = "Unknown provider: ";
    
    // Default Values
    public static final String DEFAULT_OAUTH2_STATE = "";
    public static final String COMMA_SEPARATOR = ",";
    public static final int FIRST_ELEMENT_INDEX = 0;
    
    // Response Messages
    public static final String MSG_LOGIN_SUCCESS = "Connexion réussie";
    public static final String MSG_LOGOUT_SUCCESS = "Déconnexion réussie";
    public static final String MSG_REFRESH_TOKEN_SUCCESS = "Token rafraîchi avec succès";
    public static final String MSG_FORGOT_PASSWORD_SUCCESS = "Code de réinitialisation envoyé";
    public static final String MSG_RESET_PASSWORD_SUCCESS = "Mot de passe réinitialisé avec succès";
    public static final String MSG_UPDATE_PROFILE_SUCCESS = "Profil mis à jour avec succès";
    public static final String MSG_CODE_SENT = "Code de vérification envoyé";
    public static final String MSG_VERIFICATION_SUCCESS = "Vérification réussie";
    public static final String MSG_REGISTRATION_SUCCESS = "Inscription réussie";
    public static final String MSG_CODE_RESENT = "Code renvoyé avec succès";
    public static final String MSG_OAUTH2_SUCCESS = "Authentification OAuth2 réussie";
    public static final String MSG_OAUTH2_PROFILE_COMPLETED = "Profil complété avec succès";
    public static final String MSG_TEMPLATE_CREATED = "Template créé avec succès";
    public static final String MSG_TEMPLATE_UPDATED = "Template mis à jour avec succès";
    public static final String MSG_USER_DELETED = "Utilisateur supprimé avec succès";
    
    // Comments
    public static final String COMMENT_INTERNAL_ENDPOINT = "Endpoint interne pour communication inter-services";
    public static final String COMMENT_ADMIN_ENDPOINT = "Endpoint admin protégé";
}
