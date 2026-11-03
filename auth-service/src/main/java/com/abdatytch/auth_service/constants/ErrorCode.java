package com.abdatytch.auth_service.constants;

public enum ErrorCode {
    
    // Authentication Errors (1xxx)
    INVALID_CREDENTIALS("AUTH_1001", "Identifiants invalides"),
    ACCOUNT_LOCKED("AUTH_1002", "Compte verrouillé. Réessayez dans {0} minutes"),
    ACCOUNT_DISABLED("AUTH_1003", "Compte désactivé"),
    PHONE_NOT_VERIFIED("AUTH_1004", "Numéro de téléphone non vérifié"),
    EMAIL_NOT_VERIFIED("AUTH_1005", "Email non vérifié"),
    TWO_FACTOR_REQUIRED("AUTH_1009", "L'authentification à deux facteurs est obligatoire"),
    /**
     * Contexte de securite vide sur un chemin qui exige une session.
     *
     * <p>Prefixe {@code AUTH_}, donc 401 : les controleurs levaient une
     * {@code IllegalArgumentException} dans ce cas, ce qui rendait <b>400</b>
     * et laissait croire a une requete malformee.
     */
    SESSION_ABSENTE("AUTH_1010", "Authentification requise"),
    TOKEN_EXPIRED("AUTH_1006", "Token expiré"),
    TOKEN_INVALID("AUTH_1007", "Token invalide"),
    REFRESH_TOKEN_EXPIRED("AUTH_1008", "Refresh token expiré"),
    
    // Registration Errors (2xxx)
    PHONE_ALREADY_EXISTS("REG_2001", "Ce numéro de téléphone est déjà utilisé"),
    EMAIL_ALREADY_EXISTS("REG_2002", "Cet email est déjà utilisé"),
    INVALID_PHONE_FORMAT("REG_2003", "Format de numéro de téléphone invalide"),
    INVALID_EMAIL_FORMAT("REG_2004", "Format d'email invalide"),
    WEAK_PASSWORD("REG_2005", "Mot de passe trop faible. Minimum 8 caractères avec majuscule, minuscule et chiffre"),
    /**
     * Contact déjà confirmé : la demande n'a rien à faire.
     *
     * <p>Refus <b>nommé</b> et non succès silencieux. Répondre « code envoyé »
     * à qui a déjà confirmé son adresse ferait attendre un message qui ne
     * partira pas, et renvoyer un code vers une adresse déjà prouvée ouvrirait
     * un envoi gratuit déclenchable à volonté.
     *
     * <p>Préfixe {@code REG_}, donc 400 : la demande est mal formée au regard
     * de l'état du compte, ce n'est pas une panne.
     */
    EMAIL_ALREADY_VERIFIED("REG_2006", "Cette adresse est déjà confirmée"),
    PHONE_ALREADY_VERIFIED("REG_2007", "Ce numéro est déjà confirmé"),

    // Verification Errors (3xxx)
    VERIFICATION_CODE_INVALID("VER_3001", "Code de vérification invalide"),
    VERIFICATION_CODE_EXPIRED("VER_3002", "Code de vérification expiré"),
    VERIFICATION_CODE_USED("VER_3003", "Code de vérification déjà utilisé"),
    TOO_MANY_ATTEMPTS("VER_3004", "Trop de tentatives. Veuillez demander un nouveau code"),
    CODE_SEND_LIMIT_REACHED("VER_3005", "Limite d'envoi de codes atteinte. Réessayez dans {0} minutes"),
    VERIFICATION_CODE_PENDING("VER_3006", "Un code de vérification est déjà en attente. Veuillez utiliser le code existant ou attendre son expiration"),
    
    // User Errors (4xxx)
    USER_NOT_FOUND("USER_4001", "Utilisateur non trouvé"),
    USER_ALREADY_EXISTS("USER_4002", "Utilisateur déjà existant"),
    
    // System Errors (5xxx)
    INTERNAL_SERVER_ERROR("SYS_5001", "Erreur interne du serveur"),
    SMS_SERVICE_ERROR("SYS_5002", "Erreur lors de l'envoi du SMS"),
    EMAIL_SERVICE_ERROR("SYS_5003", "Erreur lors de l'envoi de l'email"),
    DATABASE_ERROR("SYS_5004", "Erreur de base de données"),
    SERVICE_UNAVAILABLE("SYS_5005", "Service temporairement indisponible"),
    /**
     * Le fournisseur SMS ne repond pas — distinct d'un refus de sa part.
     *
     * <p>{@code SMS_SERVICE_ERROR} melangeait les deux : une panne de transport
     * et un refus du fournisseur (numero invalide, credit epuise, reponse
     * illisible) rendaient le meme code. Or les deux appellent des reactions
     * opposees : une panne doit ouvrir le disjoncteur et rendre 503, un refus
     * signale que le fournisseur <b>fonctionne</b> et ne doit rien ouvrir.
     *
     * <p>Le nom contient {@code UNAVAILABLE}, ce qui le classe automatiquement
     * en 503 par {@code GlobalExceptionHandler} et le fait compter par
     * {@code DisjoncteurSortantServiceImpl} — une seule convention, appliquee
     * aux deux endroits.
     */
    SMS_PROVIDER_UNAVAILABLE("SYS_5006", "Fournisseur SMS indisponible"),
    
    // Validation Errors (6xxx)
    VALIDATION_ERROR("VAL_6001", "Erreur de validation"),
    MISSING_REQUIRED_FIELD("VAL_6002", "Champ obligatoire manquant: {0}"),
    INVALID_INPUT("VAL_6003", "Entrée invalide: {0}"),
    INVALID_REQUEST("VAL_6004", "Requête invalide"),
    INVALID_TOKEN("VAL_6005", "Token invalide"),
    /**
     * Fournisseur social demande mais pas utilisable sur cette installation.
     *
     * <h2>Pourquoi un code distinct, et pourquoi le prefixe VAL_</h2>
     *
     * Un fournisseur sans identifiants est une <b>configuration</b>, pas une
     * panne. Le prefixe {@code VAL_} rend donc 400, et surtout le nom ne
     * contient pas {@code UNAVAILABLE} : par la convention de ce service, un tel
     * nom serait compte par {@code DisjoncteurSortantServiceImpl} et finirait
     * par ouvrir le disjoncteur du fournisseur. Ouvrir un disjoncteur sur une
     * variable vide n'a aucun sens — rien n'est tombe, et le disjoncteur
     * resterait ouvert jusqu'a ce qu'on le remarque.
     *
     * <p>C'est la meme distinction que celle documentee sur
     * {@link #SMS_PROVIDER_UNAVAILABLE} : une panne de transport et un refus ne
     * sont pas le meme evenement.
     *
     * <h2>Le message ne dit pas ce qui manque</h2>
     *
     * Ni le nom de la variable, ni le fournisseur concerne : repondre « secret
     * Apple absent » renseignerait un appelant anonyme sur l'etat de notre
     * configuration.
     */
    OAUTH2_PROVIDER_NOT_CONFIGURED("VAL_6006", "Fournisseur d'authentification non disponible"),
    /**
     * Délier ce fournisseur rendrait le compte inaccessible.
     *
     * <p>Refus <b>nommé</b> et détaillé, contrairement aux refus de connexion
     * qui doivent rester uniformes. La différence : l'appelant est déjà
     * authentifié sur son propre compte, et lui dire « c'est votre dernier
     * moyen de connexion » ne renseigne personne d'autre. Lui répondre un refus
     * opaque l'enverrait chercher une panne qui n'existe pas.
     *
     * <p>La sortie est indiquée dans le message : définir un mot de passe, ou
     * lier un autre fournisseur, puis recommencer.
     */
    DERNIER_MOYEN_DE_CONNEXION("VAL_6007",
            "Impossible de delier : c'est le dernier moyen de connexion de ce compte. "
                    + "Definissez un mot de passe ou liez un autre fournisseur d'abord."),

    /**
     * Activation d'une alerte par courriel sur un compte sans adresse verifiee.
     *
     * <p>Refuser plutot qu'accepter : un reglage affiche comme actif qui
     * n'enverrait jamais rien est la pire des deux reponses, parce que la
     * personne se croirait surveillee. Le message indique la sortie.
     */
    ADRESSE_NON_VERIFIEE("VAL_6008",
            "Confirmez votre adresse electronique avant d'activer cette alerte : "
                    + "sans adresse verifiee, elle ne pourrait etre envoyee nulle part."),

    /**
     * Operation exigeant le mot de passe actuel sur un compte qui n'en a pas.
     *
     * <p>Les comptes crees par OAuth2 ont {@code passwordHash} a {@code null} :
     * leur titulaire s'authentifie chez Google, Facebook ou Apple. Le changement
     * de numero exige le mot de passe actuel, qu'ils ne peuvent donc pas
     * fournir.
     *
     * <p>Refuser plutot que laisser passer sur la seule session : le numero est
     * le canal de la reinitialisation du mot de passe, et une session seule
     * suffirait alors a prendre le controle definitif du compte.
     */
    MOT_DE_PASSE_ABSENT_DU_COMPTE("VAL_6009",
            "Cette operation exige votre mot de passe actuel, et ce compte n'en a "
                    + "pas : il a ete cree par un fournisseur externe."),

    // Template Errors (7xxx)
    TEMPLATE_NOT_FOUND("TPL_7001", "Template non trouvé"),
    TEMPLATE_ALREADY_EXISTS("TPL_7002", "Template déjà existant pour ce type"),
    
    // 2FA Errors (9xxx)
    TWO_FA_NOT_ENABLED("2FA_9001", "Authentification à deux facteurs non activée"),
    TWO_FA_ALREADY_ENABLED("2FA_9002", "Authentification à deux facteurs déjà activée"),
    TWO_FA_CODE_INVALID("2FA_9003", "Code 2FA invalide"),
    TWO_FA_SETUP_NOT_FOUND("2FA_9004", "Configuration 2FA non trouvée"),
    TWO_FA_REQUIRED("2FA_9005", "Authentification à deux facteurs requise"),
    BACKUP_CODE_GENERATION_ERROR("2FA_9006", "Erreur lors de la génération des codes de secours"),
    QR_CODE_GENERATION_ERROR("2FA_9007", "Erreur lors de la génération du QR code"),
    
    // External Service Errors (8xxx)
    EXTERNAL_SERVICE_ERROR("EXT_8001", "Erreur du service externe");
    
    private final String code;
    private final String message;
    
    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getMessage() {
        return message;
    }
    
    public String getFormattedMessage(Object... args) {
        String formatted = message;
        for (int i = 0; i < args.length; i++) {
            formatted = formatted.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return formatted;
    }
}
