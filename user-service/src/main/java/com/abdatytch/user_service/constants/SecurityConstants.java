package com.abdatytch.user_service.constants;

/**

 * Constantes pour la couche Security
 */
public final class SecurityConstants {

    private SecurityConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // ------------------------------------------------------------------
    // Appel vers auth-service
    // ------------------------------------------------------------------

    /**
     * Identifiant du service dans Consul, et non un nom d'hôte.
     *
     * <p>C'est lui que l'annuaire résout vers une instance vivante. Écrire
     * l'hôte et le port à la place figerait une adresse que rien ne garantit :
     * une instance redémarrée ailleurs ne serait plus joignable.
     */
    public static final String SERVICE_AUTH = "auth-service";

    /**
     * Base de l'URI vers auth-service : l'identifiant de service, sans préfixe.
     *
     * <h3>Le défaut que cette ligne corrige</h3>
     *
     * Il valait {@code "/api/v1"}, et l'appel partait donc vers
     * {@code /api/v1/internal/auth/users/{id}}. Or {@code /api/v1} est le
     * préfixe <b>public</b>, celui que la passerelle retire avant de transmettre :
     * aucun service de cette plateforme n'a de {@code context-path}, et
     * auth-service sert {@code /internal/auth/users/{id}} à la racine.
     *
     * <p>Cet appel-ci ne passe pas par la passerelle : le nom d'hôte est
     * {@link #SERVICE_AUTH}, que l'équilibreur résout par Consul directement
     * vers l'instance. Il n'y avait donc personne pour retirer le préfixe, et
     * <b>chaque appel rendait 404</b>.
     *
     * <h3>Pourquoi cela ne se voyait pas</h3>
     *
     * {@code UserMapper} appelle auth-service pour composer chaque réponse
     * utilisateur, et son {@code onErrorResume} se rabattait sur une identité de
     * repli. Le repli posait {@code unknown@email.com} et {@code Unknown User} —
     * une adresse syntaxiquement valide et un nom lisible. Autrement dit : le
     * chemin dégradé était le chemin <b>normal</b>, et ses valeurs de
     * remplacement avaient l'apparence de vraies données.
     *
     * <p>Le nom, le courriel, le numéro et le rôle étaient donc faux dans
     * <b>toutes</b> les réponses de ce service depuis l'origine. Le repli rend
     * désormais des champs nuls, ce qui se voit.
     */
    public static final String BASE_URI_AUTH = "http://" + SERVICE_AUTH;

    // API Paths
    public static final String PATH_INTERNAL_AUTH_USERS = "/internal/auth/users/{authUserId}";

    // Headers
    public static final String HEADER_X_SECRET_KEY = "X-Secret-Key";

    /**
     * En-tête d'authentification de service à service, vers {@code /internal/**}.
     *
     * <h3>Pourquoi il remplace {@code X-Secret-Key} sur ces appels</h3>
     *
     * L'appel portait {@code X-Secret-Key: ${gateway.secret.key}} — la clé du
     * <b>Gateway</b>, et auth-service ne la vérifiait pas. Deux défauts
     * distincts :
     *
     * <ul>
     *   <li>la clé du Gateway authentifie le <b>Gateway</b> auprès des services,
     *       pas un service auprès d'un autre. L'emprunter brouille les deux
     *       rôles : un service compromis pourrait se faire passer pour le
     *       Gateway ;</li>
     *   <li>{@code X-Secret-Key} figure dans {@code GATEWAY_OWNED_HEADERS} : le
     *       Gateway le <b>purge</b> des requêtes entrantes. L'en-tête
     *       disparaîtrait donc le jour où cet appel passerait par lui.</li>
     * </ul>
     *
     * {@code X-Internal-Secret} est la convention déjà en place dans
     * interface-service, et elle n'est pas purgée.
     */
    public static final String HEADER_X_INTERNAL_SECRET = "X-Internal-Secret";

    // Token Prefixes
    public static final String BEARER_PREFIX = "Bearer ";
    public static final int BEARER_PREFIX_LENGTH = 7;

    // Roles
    public static final String ROLE_SERVICE = "ROLE_SERVICE";
    public static final String ROLE_SERVICE_SHORT = "SERVICE";

    // JWT Claims
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_SUBJECT = "subject";

    // JWKS Configuration - Clés publiques récupérées depuis interface-service
    // Note: user-service ne gère plus les clés RSA localement
    // Les clés sont récupérées via JWKS depuis interface-service

    // HTTP Status
    public static final int STATUS_UNAUTHORIZED = 401;
    public static final int STATUS_FORBIDDEN = 403;

    // Content Types
    public static final String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";

    // Log Messages - INFO level
    public static final String LOG_INFO_LOADING_PUBLIC_KEY = "Loading RSA public key from classpath: {}";
    public static final String LOG_INFO_PUBLIC_KEY_LOADED = "RSA public key loaded successfully";

    // Log Messages - DEBUG level
    public static final String LOG_DEBUG_VALIDATING_TOKEN = "Validating JWT token with RSA";
    public static final String LOG_DEBUG_TOKEN_VALIDATED = "JWT token validated successfully for subject: {}";
    public static final String LOG_DEBUG_CHECKING_SERVICE_TOKEN = "Checking if token is a service token";
    public static final String LOG_DEBUG_IS_SERVICE_TOKEN = "Token is service token: {}";
    public static final String LOG_DEBUG_SERVICE_TOKEN_VALIDATED = "Service token validated for: {}";

    // Log Messages - WARN level
    public static final String LOG_WARN_MISSING_AUTH_HEADER = "Missing or invalid Authorization header for internal endpoint: {}";
    public static final String LOG_WARN_INVALID_SERVICE_TOKEN_TYPE = "Invalid service token type for: {}";
    public static final String LOG_WARN_SERVICE_TOKEN_VALIDATION_FAILED = "Failed to validate service token: {}";

    // Log Messages - ERROR level
    public static final String LOG_ERROR_AUTH_FAILED = " Authentication failed: {} - URI: {}";
    public static final String LOG_ERROR_TOKEN_VALIDATION_FAILED = "JWT token validation failed: {}";
    public static final String LOG_ERROR_PUBLIC_KEY_LOAD_FAILED = "Failed to load public key from: {}";
    public static final String LOG_ERROR_SERVICE_TOKEN_VALIDATION_FAILED = "Service token validation failed";

    // Error Response Keys
    public static final String KEY_TIMESTAMP = "timestamp";
    public static final String KEY_STATUS = "status";
    public static final String KEY_ERROR = "error";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_PATH = "path";

    // Error Response Values
    public static final String ERROR_UNAUTHORIZED = "Unauthorized";

    // Error Messages
    public static final String MSG_AUTH_FAILED_DEFAULT = "Authentification échouée. Token JWT invalide ou manquant.";
    public static final String MSG_TOKEN_EXPIRED = "Token JWT expiré. Veuillez vous reconnecter.";
    public static final String MSG_INVALID_SIGNATURE = "Signature du token JWT invalide. Token non autorisé.";
    public static final String MSG_MALFORMED_TOKEN = "Token JWT malformé ou invalide.";
    public static final String MSG_TOKEN_MISSING = "Token d'authentification manquant. Veuillez fournir un token JWT valide.";
    public static final String MSG_AUTH_FAILED_PREFIX = "Authentification échouée: ";
    public static final String MSG_SERVICE_TOKEN_REQUIRED = "Service token required";
    public static final String MSG_INVALID_SERVICE_TOKEN = "Invalid service token";

    // Exception Messages
    public static final String EX_PUBLIC_KEY_NOT_FOUND = "Public key file not found: %s";
    public static final String EX_PUBLIC_KEY_LOAD_FAILED = "Failed to load public key";

    // Token Validation Keywords
    public static final String KEYWORD_EXPIRED = "expired";
    public static final String KEYWORD_EXPIRE_FR = "expiré";
    public static final String KEYWORD_SIGNATURE = "signature";
    public static final String KEYWORD_SIGNATURE_CAP = "Signature";
    public static final String KEYWORD_MALFORMED = "malformed";
    public static final String KEYWORD_INVALID = "Invalid";
    public static final String KEYWORD_BEARER_TOKEN = "Bearer token";
    public static final String KEYWORD_MISSING = "missing";

    // Comments
    public static final String COMMENT_AUTH_SERVICE_SOURCE = "Auth-service est la source de vérité pour:";
    public static final String COMMENT_AUTH_DATA_EMAIL = "- Email, phoneNumber, firstName, lastName";
    public static final String COMMENT_AUTH_DATA_ROLE = "- Role, isActive, isPhoneVerified, isEmailVerified";
    public static final String COMMENT_AUTH_DATA_LOGIN = "- lastLogin";
    public static final String COMMENT_USER_SERVICE_DATA = "User-service stocke uniquement:";
    public static final String COMMENT_USER_DATA_REF = "- authUserId (référence)";
    public static final String COMMENT_USER_DATA_PROFILE = "- Données de profil (dateOfBirth, profilePictureUrl, status, etc.)";
    public static final String COMMENT_PRODUCTION_CONFIG = "Note: En production, cette valeur devrait venir du config-server";
}
