package com.abdatytch.auth_service.constants;

/**

 * Constantes pour la couche Service
 */
public final class ServiceConstants {

    private ServiceConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Log Messages - INFO level - AuthService
    public static final String LOG_INFO_LOGIN_ATTEMPT = "Tentative de connexion pour le téléphone: {}";
    public static final String LOG_INFO_LOGOUT_SUCCESS = "Déconnexion réussie pour l'utilisateur: {}";
    /**
     * L'argument est une EMPREINTE, jamais le jeton.
     *
     * <p>Le gabarit disait « pour l'utilisateur » et recevait le <b>jeton de
     * rafraîchissement</b>. C'est ce décalage — le message annonce un
     * identifiant, l'appel passe un justificatif — qui explique que la fuite
     * soit restée invisible : lire la chaîne de format seule ne révèle rien.
     */
    public static final String LOG_INFO_REFRESH_TOKEN = "Rafraîchissement d'un token {}";
    public static final String LOG_INFO_FORGOT_PASSWORD = "Demande de réinitialisation de mot de passe pour: {}";
    public static final String LOG_INFO_RESET_PASSWORD = "Réinitialisation du mot de passe pour: {}";
    public static final String LOG_INFO_UPDATE_PROFILE = "Mise à jour du profil pour l'utilisateur: {}";
    public static final String LOG_INFO_PHONE_AUTH_REQUEST = "Demande d'authentification par téléphone pour: {}";
    public static final String LOG_INFO_PHONE_AUTH_VERIFY = "Vérification d'authentification par téléphone pour: {}";
    public static final String LOG_INFO_PHONE_REGISTER_REQUEST = "Demande d'inscription par téléphone pour: {}";
    public static final String LOG_INFO_PHONE_REGISTER_VERIFY = "Vérification d'inscription par téléphone pour: {}";
    public static final String LOG_INFO_EMAIL_AUTH_REQUEST = "Demande d'authentification par email pour: {}";
    public static final String LOG_INFO_EMAIL_AUTH_VERIFY = "Vérification d'authentification par email pour: {}";
    public static final String LOG_INFO_EMAIL_REGISTER = "Inscription par email pour: {}";
    public static final String LOG_INFO_EMAIL_REGISTER_VERIFY = "Vérification d'inscription par email pour: {}";
    public static final String LOG_INFO_RESEND_CODE = "Renvoi du code de vérification pour: {}";

    // Log Messages - INFO level - JwtService
    public static final String LOG_INFO_JWT_INITIALIZED = " JwtService initialized successfully with RSA";
    public static final String LOG_INFO_LOADING_PRIVATE_KEY = "Loading RSA private key from classpath: {}";
    public static final String LOG_INFO_JWT_GENERATED = "JWT généré pour l'utilisateur: {}";
    public static final String LOG_INFO_SERVICE_TOKEN_GENERATED = "Service token généré pour: {}";

    // Log Messages - INFO level - EmailService
    public static final String LOG_INFO_SENDING_VERIFICATION_EMAIL = "Envoi email de vérification à: {} pour type: {}";
    public static final String LOG_INFO_SENDING_WELCOME_EMAIL = "Envoi email de bienvenue à: {}";
    public static final String LOG_INFO_SENDING_EMAIL = "Envoi email - From: {}, To: {}, Subject: {}";

    /**
     * Code de verification affiche en clair, sur interrupteur explicite.
     *
     * <p>Remplace un emprunt a {@code LOG_INFO_SMS_DEV_MODE}, dont le texte
     * disait « MODE DEV - Code SMS » alors que l'appelant est le service de
     * COURRIEL. Un journal qui nomme le mauvais canal envoie chercher l'incident
     * au mauvais endroit, et le defaut n'est apparu qu'au deplacement du
     * sous-systeme SMS dans notification-service.
     *
     * <p>Le libelle ne nomme donc plus aucun canal : c'est le meme code, quel
     * que soit le chemin qui le porte.
     */
    public static final String LOG_INFO_CODE_EN_DEVELOPPEMENT =
            "MODE DEV - Code de vérification : {}";
    public static final String LOG_INFO_EMAIL_SENT_SUCCESS = " Email envoyé avec succès à: {}";

    // Log Messages - INFO level - SmsService
    //
    // Le gabarit annoncait « pour type: {} » et recevait le CONTENU du SMS,
    // donc le code a usage unique. Le decalage entre ce qu'un gabarit annonce
    // et ce qu'on lui passe est la raison pour laquelle cette fuite a survecu a
    // une relecture : seul l'ARGUMENT la revele. Un seul emplacement, desormais.
    // Accuses de reception. Le champ `message` de l'accuse n'est JAMAIS
    // journalise : chaine libre venue d'un tiers, qui pourrait echo ce qu'on
    // lui a envoye, donc le code a usage unique.

    // Log Messages - INFO level - VerificationCodeService
    public static final String LOG_INFO_GENERATING_CODE = "Génération d'un code de vérification pour: {} de type: {}";
    /**
     * Le code de vérification ne figure PLUS dans ce message.
     *
     * <p>Le gabarit était {@code "Code de vérification généré: {} pour: {}"},
     * avec {@code (code, phoneNumber)} : chaque code à usage unique jamais émis
     * se trouvait en clair dans les journaux, accompagné du numéro auquel il
     * était destiné. C'est tout ce qu'il faut pour prendre un compte, à la
     * portée de quiconque lit Loki.
     *
     * <p>Aucun masquage par empreinte n'est possible : six chiffres font un
     * million de candidats, qu'un haché ne protège pas. Voir
     * {@code SecretMasking.masquerCodeCourt}. Le destinataire et l'horodatage
     * suffisent à savoir qu'un code a été émis.
     */
    public static final String LOG_INFO_CODE_GENERATED = "Code de vérification émis pour: {}";
    public static final String LOG_INFO_VERIFYING_CODE = "Vérification du code pour: {}";
    public static final String LOG_INFO_CODE_VERIFIED = "Code vérifié avec succès pour: {}";
    public static final String LOG_INFO_RESENDING_CODE = "Renvoi du code de vérification pour: {}";

    // Log Messages - INFO level - RefreshTokenService
    public static final String LOG_INFO_CREATING_REFRESH_TOKEN = "Création d'un refresh token pour l'utilisateur: {}";
    public static final String LOG_INFO_REFRESH_TOKEN_CREATED = "Refresh token créé pour l'utilisateur: {}";
    /**
     * L'argument est une EMPREINTE, jamais le jeton.
     *
     * <p>Le gabarit était {@code "Validation du refresh token: {}"} et recevait
     * le jeton en clair. Un jeton de rafraîchissement dans les journaux permet
     * de renouveler une session indéfiniment : c'est un justificatif de longue
     * durée, pas un identifiant.
     */
    public static final String LOG_INFO_VALIDATING_REFRESH_TOKEN =
            "Validation d'un refresh token {}";

    /** Jeton de rafraîchissement révoqué. Argument : une empreinte. */
    public static final String LOG_WARN_REFRESH_TOKEN_REVOKED =
            "Refresh token révoqué présenté {}";

    /**
     * Jeton de rafraîchissement expiré. Argument : une empreinte.
     *
     * <p>Ces deux cas réutilisaient {@code LOG_WARN_CODE_RESEND_TOO_SOON} —
     * « Tentative de renvoi trop rapide pour: {} » — qui ne décrit ni l'un ni
     * l'autre. Le message faux est la raison pour laquelle la fuite est passée
     * inaperçue : rien, dans « renvoi trop rapide », ne laisse soupçonner qu'un
     * jeton est journalisé.
     */
    public static final String LOG_WARN_REFRESH_TOKEN_EXPIRED =
            "Refresh token expiré présenté {}";
    public static final String LOG_INFO_REFRESH_TOKEN_VALID = "Refresh token valide pour l'utilisateur: {}";
    /**
     * L'argument est une EMPREINTE, jamais le jeton.
     *
     * <p>Le gabarit disait « pour l'utilisateur » et recevait le <b>jeton</b> —
     * même décalage que {@code LOG_INFO_REFRESH_TOKEN}. Lire la chaîne de format
     * seule laissait croire qu'un identifiant était journalisé.
     */
    public static final String LOG_INFO_REVOKING_REFRESH_TOKEN =
            "Révocation d'un refresh token {}";

    // Log Messages - INFO level - AuthenticationLogService
    public static final String LOG_INFO_LOGGING_AUTH_EVENT = "Enregistrement d'un événement d'authentification: {} - {} - {}";

    // Log Messages - INFO level - EventPublisher
    public static final String LOG_INFO_PUBLISHING_USER_CREATED = " Publication de l'événement UserCreatedEvent pour authUserId: {}";
    public static final String LOG_INFO_USER_CREATED_PUBLISHED = " Événement UserCreatedEvent publié avec succès";
    public static final String LOG_INFO_PUBLISHING_USER_LOGGED_IN = " Publication de l'événement UserLoggedInEvent pour authUserId: {}";
    public static final String LOG_INFO_USER_LOGGED_IN_PUBLISHED = " Événement UserLoggedInEvent publié avec succès";

    // Log Messages - WARN level
    public static final String LOG_WARN_EMAIL_DISABLED = "Service Email désactivé. Email non envoyé à: {}";
    public static final String LOG_WARN_EMAIL_SEND_ERROR = "Erreur lors de l'envoi de l'email de bienvenue";
    public static final String LOG_WARN_CODE_RESEND_TOO_SOON = "Tentative de renvoi trop rapide pour: {}";

    // Log Messages - ERROR level
    public static final String LOG_ERROR_JWT_INIT_FAILED = " Failed to initialize JwtService with RSA";
    public static final String LOG_ERROR_EMAIL_SEND_FAILED = " Erreur lors de l'envoi de l'email à: {}";
    public static final String LOG_ERROR_SMS_SEND_FAILED = " Erreur lors de l'envoi du SMS à: {}";
    public static final String LOG_ERROR_EMAIL_SERVICE = "Erreur lors de l'envoi de l'email";
    public static final String LOG_ERROR_SMS_SERVICE = "Erreur lors de l'envoi du SMS";
    // Fournisseurs SMS. Aucun de ces gabarits ne prend le contenu du message ni
    // un identifiant d'API : chez AfrikSMS, les deux voyagent dans l'URI, et le
    // message d'une exception WebClient contient cette URI. Les arguments
    // admis sont donc un STATUT, un CODE ou un NOM DE CLASSE, jamais l'objet
    // exception et jamais le corps de la reponse.
    public static final String LOG_ERROR_SMS_REPLI =
            "Disjoncteur SMS ouvert, repli pour: {} ({})";
    public static final String LOG_ERROR_INVALID_JWT = "JWT invalide: {}";
    public static final String LOG_ERROR_EXPIRED_JWT = "JWT expiré: {}";
    public static final String LOG_ERROR_UNSUPPORTED_JWT = "JWT non supporté: {}";
    public static final String LOG_ERROR_MALFORMED_JWT = "JWT malformé: {}";
    public static final String LOG_ERROR_SIGNATURE_JWT = "Signature JWT invalide: {}";
    public static final String LOG_ERROR_EVENT_PUBLISH_FAILED = " Erreur lors de la publication de l'événement";

    // Log Messages - DEBUG level
    public static final String LOG_DEBUG_JWT_VALIDATION = "Validation du JWT pour l'utilisateur: {}";
    public static final String LOG_DEBUG_CODE_VALIDATION = "Validation du code: {} pour: {}";

    // JWKS - Clés publiques chargées depuis interface-service
    // Note: auth-service ne gère plus les clés RSA localement
    // Les clés sont récupérées via JWKS depuis interface-service
    public static final String LOG_INFO_JWKS_LOADING = "Chargement des clés publiques depuis JWKS: {}";
    public static final String LOG_INFO_JWKS_LOADED = " Clés publiques JWKS chargées avec succès";
    public static final String LOG_ERROR_JWKS_LOAD_FAILED = " Échec du chargement des clés JWKS";

    // JWT Service - Default Values
    public static final String JWT_CLAIM_DEFAULT_SUBJECT = "TEMP";
    public static final String JWT_CLAIM_SERVICE_USER_ID = "SERVICE";
    public static final String JWT_CLAIM_SERVICE_ROLE = "ROLE_SERVICE";
    public static final String JWT_CLAIM_SERVICE_NAME = "service";
    public static final String JWT_SERVICE_NAME = "auth-service";
    public static final String JWT_SERVICE_SUBJECT = "SERVICE";
    public static final String ROLE_PREFIX = "ROLE_";

    // Exception Messages
    public static final String EX_PRIVATE_KEY_NOT_FOUND = "Private key file not found: %s";
    public static final String EX_FAILED_LOAD_PRIVATE_KEY = "Failed to load RSA private key";
    public static final String EX_EMAIL_SEND_FAILED = "Échec de l'envoi de l'email";
    public static final String EX_SMS_SEND_FAILED = "Échec de l'envoi du SMS";

    // Template Variables
    public static final String TEMPLATE_VAR_CODE = "code";
    public static final String TEMPLATE_VAR_MINUTES = "minutes";
    public static final String TEMPLATE_VAR_FIRST_NAME = "firstName";
    public static final String TEMPLATE_VAR_EMPTY = "";

    // Kafka Topics
    public static final String KAFKA_TOPIC_USER_EVENTS = "user-events";

    // OAuth2 Service - Log Messages
    public static final String LOG_INFO_OAUTH2_CALLBACK = "OAuth2 callback reçu - Provider: {}, Code présent: {}";
    public static final String LOG_INFO_OAUTH2_TOKEN_EXCHANGE = "Échange du code d'autorisation avec {} - Code: {}";
    public static final String LOG_INFO_OAUTH2_USERINFO_FETCH = "Récupération des informations utilisateur depuis {}";
    public static final String LOG_INFO_OAUTH2_USER_EXISTS = "Utilisateur OAuth2 existant trouvé - Provider: {}, ID: {}";
    public static final String LOG_INFO_OAUTH2_USER_CREATE = "Création d'un nouveau compte utilisateur via OAuth2 - Provider: {}, Email: {}";
    public static final String LOG_INFO_OAUTH2_ACCOUNT_LINK = "Liaison du compte OAuth2 - User ID: {}, Provider: {}";
    public static final String LOG_INFO_OAUTH2_PROFILE_COMPLETION = "Complétion du profil OAuth2 - User ID: {}";
    public static final String LOG_INFO_OAUTH2_TEMP_TOKEN_GENERATED = "Token temporaire OAuth2 généré - User ID: {}, Expiration: {} minutes";
    public static final String LOG_INFO_OAUTH2_USER_SYNC = "Synchronisation de l'utilisateur avec user-service - User ID: {}";
    public static final String LOG_INFO_OAUTH2_PROFILE_COMPLETED = "Profil OAuth2 complété avec succès";

    // OAuth2 Service - Default Values
    public static final String OAUTH2_IP_ADDRESS = "OAuth2-Provider";
    public static final String OAUTH2_USER_AGENT = "OAuth2-Client";

    // OAuth2 Service - Error Messages
    public static final String LOG_ERROR_OAUTH2_TOKEN_EXCHANGE = "Erreur lors de l'échange du token OAuth2 avec {}: {}";
    public static final String LOG_ERROR_OAUTH2_USERINFO_FETCH = "Erreur lors de la récupération des infos utilisateur depuis {}: {}";
    public static final String LOG_ERROR_OAUTH2_INVALID_PROVIDER = "Provider OAuth2 invalide: {}";
    public static final String LOG_ERROR_OAUTH2_TEMP_TOKEN_INVALID = "Token temporaire OAuth2 invalide ou expiré";
    public static final String LOG_ERROR_OAUTH2_USER_CREATION = "Erreur lors de la création de l'utilisateur OAuth2: {}";

    // OAuth2 Service - Exception Messages
    public static final String EX_OAUTH2_PROVIDER_NOT_SUPPORTED = "Provider OAuth2 non supporté";
    public static final String EX_OAUTH2_TOKEN_EXCHANGE_FAILED = "Échec de l'échange du code d'autorisation";
    public static final String EX_OAUTH2_USERINFO_FETCH_FAILED = "Échec de la récupération des informations utilisateur";
    public static final String EX_OAUTH2_TEMP_TOKEN_INVALID = "Token temporaire invalide ou expiré";
    public static final String EX_OAUTH2_PROFILE_INCOMPLETE = "Le profil utilisateur est incomplet";
}
