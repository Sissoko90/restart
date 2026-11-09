package com.abdatytch.auth_service.constants;

public final class AppConstants {

    private AppConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas être instanciée");
    }

    // Verification Code
    public static final int VERIFICATION_CODE_LENGTH = 6;
    public static final int VERIFICATION_CODE_EXPIRY_MINUTES = 10;
    public static final int MAX_VERIFICATION_ATTEMPTS = 3;
    public static final int CODE_RESEND_DELAY_SECONDS = 60;

    // Password
    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 100;
    public static final String PASSWORD_PATTERN = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$";

    // Phone
    public static final String PHONE_PATTERN = "^\\+?[1-9]\\d{1,14}$"; // E.164 format
    public static final int PHONE_MAX_LENGTH = 20;

    // Email
    public static final String EMAIL_PATTERN = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    public static final int EMAIL_MAX_LENGTH = 100;
    public static final String EMAIL_INVALID_MESSAGE = "Format d'email invalide";
    public static final String EMAIL_TOO_LONG_MESSAGE = "Email trop long";

    // Name Firstname
    public static final String FIRSTNAME_TOO_LONG_MESSAGE = "Prénom trop long";
    public static final String FIRSTNAME_REQUIRED_MESSAGE = "Le prénom est obligatoire";
    public static final String NAME_TOO_LONG_MESSAGE = "Nom trop long";
    public static final String NAME_REQUIRED_MESSAGE = "Le nom est obligatoire";
    public static final String EMAIL_REQUIRED_MESSAGE = "L'email est obligatoire";
    public static final String USER_NOT_FOUND_MESSAGE = "Utilisateur non trouvé";
    public static final String LOGOUT_SUCCESS_MESSAGE = "Déconnexion réussie";
    public static final String RESET_PASSWORD_SUCCESS_MESSAGE = "Mot de passe réinitialisé avec succès";
    public static final String UPDATE_PROFILE_SUCCESS_MESSAGE = "Profil mis à jour avec succès";
    public static final String UNAUTHORIZED_MESSAGE = "Non autorisé";


    // Validation Messages - Phone
    public static final String PHONE_REQUIRED_MESSAGE = "Le numéro de téléphone est obligatoire";
    public static final String PHONE_INVALID_MESSAGE = "Format de numéro de téléphone invalide";
    public static final String PHONE_INVALID_E164_MESSAGE = "Format de numéro de téléphone invalide (E.164)";
    public static final String PHONE_TOO_LONG_MESSAGE = "Numéro de téléphone trop long";

    // Validation Messages - Country Code
    public static final String COUNTRY_CODE_REQUIRED_MESSAGE = "L'indicatif pays est obligatoire";
    public static final String COUNTRY_CODE_INVALID_MESSAGE = "Indicatif pays invalide";

    // Validation Messages - Password
    public static final String PASSWORD_REQUIRED_MESSAGE = "Le mot de passe est obligatoire";
    public static final String PASSWORD_SIZE_MESSAGE = "Le mot de passe doit contenir entre 8 et 100 caractères";
    public static final String PASSWORD_PATTERN_MESSAGE = "Le mot de passe doit contenir au moins une majuscule, une minuscule et un chiffre";
    public static final String NEW_PASSWORD_REQUIRED_MESSAGE = "Le nouveau mot de passe est obligatoire";
    public static final String CONFIRM_PASSWORD_REQUIRED_MESSAGE = "La confirmation du mot de passe est obligatoire";

    // Validation Messages - Verification Code
    public static final String CODE_REQUIRED_MESSAGE = "Le code de vérification est obligatoire";
    public static final String CODE_SIZE_MESSAGE = "Le code doit contenir exactement 6 chiffres";
    public static final String CODE_PATTERN_MESSAGE = "Le code doit contenir uniquement des chiffres";
    public static final String CODE_NUMERIC_PATTERN = "^[0-9]{6}$";

    // Validation Messages - Token
    public static final String REFRESH_TOKEN_REQUIRED_MESSAGE = "Le refresh token est obligatoire";

    // Validation Messages - Template
    public static final String TEMPLATE_TYPE_REQUIRED_MESSAGE = "Le type de template est obligatoire";
    public static final String TEMPLATE_CONTENT_REQUIRED_MESSAGE = "Le contenu est obligatoire";
    public static final String TEMPLATE_SUBJECT_TOO_LONG_MESSAGE = "Le sujet ne peut pas dépasser 200 caractères";
    public static final String TEMPLATE_DESCRIPTION_TOO_LONG_MESSAGE = "La description ne peut pas dépasser 500 caractères";
    public static final String TEMPLATE_VARIABLES_TOO_LONG_MESSAGE = "Les variables ne peuvent pas dépasser 500 caractères";

    // Account Locking
    /**
     * Message de {@code forgot-password}, volontairement <b>neutre</b>.
     *
     * <p>Il ne dit pas si le compte existe : « Code de réinitialisation envoyé »
     * sur un compte existant et 404 sur un compte inconnu permettaient
     * d'énumérer les numéros inscrits, un appel à la fois.
     *
     * <p>La formulation est au conditionnel pour rester exacte dans les deux
     * cas — annoncer un envoi qui n'a pas eu lieu serait un autre mensonge.
     */
    /** Envoi actif : le code est bien parti par SMS. */
    public static final String OTP_ENVOYE_PAR_SMS = "Code OTP envoyé par SMS";

    /**
     * Envoi coupé : le code existe, mais aucun SMS n'est parti.
     *
     * <p>{@code sendSms} se termine par un {@code Mono} vide quand
     * {@code sms.enabled} est faux — un succès, indistinguable d'un envoi
     * réussi. La réponse annonçait donc un SMS qui ne partait pas, et
     * l'utilisateur l'attendait.
     */
    public static final String OTP_GENERE_SANS_ENVOI =
            "Code OTP généré, mais l'envoi de SMS est désactivé sur cet environnement";

    public static final String FORGOT_PASSWORD_NEUTRAL_MESSAGE =
            "Si ce compte existe, un code de réinitialisation vient d'être envoyé.";

    /**
     * Refus UNIFORME du début d'authentification biométrique.
     *
     * <p>La même phrase pour un identifiant inconnu et pour un compte sans
     * authentificateur : deux formulations distinctes permettraient d'énumérer
     * les comptes sur un endpoint public.
     */
    public static final String BIOMETRIE_INDISPONIBLE =
            "Authentification biométrique indisponible pour cet identifiant";

    /** Confirmation d'un enrolement biometrique, formulee par la couche service. */
    public static final String BIOMETRIE_ENREGISTREMENT_REUSSI =
            "Enregistrement biometrique reussi";

    public static final String IDENTIFIER_REQUIRED_MESSAGE =
            "L'identifiant est requis";

    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;
    public static final int ACCOUNT_LOCK_DURATION_MINUTES = 30;

    // JWT
    public static final String JWT_HEADER = "Authorization";
    public static final String JWT_PREFIX = "Bearer ";
    public static final String JWT_CLAIM_USER_ID = "userId";
    public static final String JWT_CLAIM_PHONE = "phone";
    public static final String JWT_CLAIM_ROLES = "roles";

    // Refresh Token
    public static final int REFRESH_TOKEN_VALIDITY_DAYS = 7;

    // SMS Messages
    public static final String SMS_VERIFICATION_TEMPLATE = "Votre code de vérification est: %s. Valide pendant %d minutes.";
    public static final String SMS_PASSWORD_RESET_TEMPLATE = "Votre code de réinitialisation est: %s. Valide pendant %d minutes.";

    // Email Subjects
    public static final String EMAIL_VERIFICATION_SUBJECT = "Code de vérification";
    public static final String EMAIL_PASSWORD_RESET_SUBJECT = "Réinitialisation de mot de passe";
    public static final String EMAIL_WELCOME_SUBJECT = "Bienvenue sur notre plateforme";

    // API Endpoints
    public static final String API_BASE_PATH = "/auth";

    // OAuth2 Endpoints
    public static final String OAUTH2_CALLBACK_ENDPOINT = "/oauth2/callback";
    public static final String OAUTH2_COMPLETE_PROFILE_ENDPOINT = "/oauth2/complete-profile";
    public static final String OAUTH2_CALLBACK_PROVIDER_ENDPOINT = "/oauth2/callback/{provider}";
    public static final String LOGIN_ENDPOINT = "/login";
    public static final String LOGOUT_ENDPOINT = "/logout";
    public static final String REFRESH_TOKEN_ENDPOINT = "/refresh";
    public static final String FORGOT_PASSWORD_ENDPOINT = "/forgot-password";
    public static final String RESET_PASSWORD_ENDPOINT = "/reset-password";
    public static final String RESEND_CODE_ENDPOINT = "/resend-code";
    public static final String UPDATE_PROFILE_ENDPOINT = "/profile";

    // ------------------------------------------------------------------
    // Libre-service du compte : chemins qui manquaient entierement
    // ------------------------------------------------------------------
    /** Changement de mot de passe par une personne connectee. */
    public static final String CHANGE_PASSWORD_ENDPOINT = "/password/change";

    /**
     * Second facteur en libre-service, en deux etapes.
     *
     * <p>Distinct de {@link #ADMIN_TOTP_ENROLL_ENDPOINT} : celui-la reprend un
     * secret pose par le semis et active aussitot, celui-ci produit un secret
     * et attend une preuve avant d'activer. Deux publics, deux compromis — voir
     * {@code ISecondFacteurService}.
     */
    public static final String TOTP_ENROLL_ENDPOINT = "/totp/enroll";
    public static final String TOTP_CONFIRM_ENDPOINT = "/totp/confirm";
    public static final String TOTP_ENDPOINT = "/totp";

    /** Sessions ouvertes du compte. */
    public static final String SESSIONS_ENDPOINT = "/sessions";
    public static final String SESSION_PAR_ID_ENDPOINT = "/sessions/{sessionId}";

    /** Confirmation du contact apres inscription, canal par canal. */
    public static final String EMAIL_CONFIRM_REQUEST_ENDPOINT = "/email/confirm-request";
    public static final String EMAIL_CONFIRM_VERIFY_ENDPOINT = "/email/confirm-verify";
    public static final String PHONE_CONFIRM_REQUEST_ENDPOINT = "/phone/confirm-request";
    public static final String PHONE_CONFIRM_VERIFY_ENDPOINT = "/phone/confirm-verify";

    /**
     * Remplacement du numero, en deux etapes.
     *
     * <p>Distinct de {@code /phone/confirm-*}, qui VERIFIE le numero courant.
     * Les confondre permettrait de confirmer un changement avec un code demande
     * pour une simple verification.
     */
    public static final String PHONE_CHANGE_REQUEST_ENDPOINT = "/phone/change-request";
    public static final String PHONE_CHANGE_VERIFY_ENDPOINT = "/phone/change-verify";

    /** Appareils de confiance. */
    public static final String APPAREILS_DE_CONFIANCE_ENDPOINT = "/trusted-devices";
    public static final String APPAREIL_DE_CONFIANCE_PAR_ID_ENDPOINT =
            "/trusted-devices/{appareilId}";

    /**
     * Codes de secours du second facteur.
     *
     * <p>Un seul chemin, deux verbes : {@code POST} regenere le lot,
     * {@code GET} rend son etat. Pas d'endpoint separe pour le nombre restant —
     * il voyage dans la reponse qui porte la liste.
     */
    public static final String CODES_DE_SECOURS_ENDPOINT = "/backup-codes";

    /**
     * Alerte de nouvelle connexion : un reglage, donc GET et PUT.
     *
     * <p>Pas de POST/DELETE : rien n'est cree ni supprime, un booleen change
     * d'etat. PUT avec la valeur voulue est idempotent, la ou une bascule sans
     * valeur ne l'est pas — un client qui reessaie apres un delai d'attente
     * inverserait deux fois.
     */
    public static final String ALERTE_DE_CONNEXION_ENDPOINT = "/alerts/new-login";

    /** Comptes sociaux lies, et fournisseurs disponibles. */
    public static final String OAUTH2_ACCOUNTS_ENDPOINT = "/oauth2/accounts";
    public static final String OAUTH2_ACCOUNT_PAR_FOURNISSEUR_ENDPOINT =
            "/oauth2/accounts/{provider}";
    public static final String OAUTH2_PROVIDERS_ENDPOINT = "/oauth2/providers";

    // API Endpoints - Auth by Phone (OTP)
    public static final String PHONE_AUTH_REQUEST_ENDPOINT = "/phone/auth-request";
    public static final String PHONE_AUTH_VERIFY_ENDPOINT = "/phone/auth-verify";
    public static final String PHONE_REGISTER_REQUEST_ENDPOINT = "/phone/register-request";
    public static final String PHONE_REGISTER_VERIFY_ENDPOINT = "/phone/register-verify";

    // API Endpoints - Auth by Email (OTP)
    public static final String EMAIL_AUTH_REQUEST_ENDPOINT = "/email/auth-request";
    public static final String EMAIL_AUTH_VERIFY_ENDPOINT = "/email/auth-verify";
    public static final String EMAIL_REGISTER_ENDPOINT = "/email/register";
    public static final String EMAIL_REGISTER_VERIFY_ENDPOINT = "/email/register-verify";

    // API Endpoints - Admin
    public static final String ADMIN_BASE_PATH = "/admin";
    public static final String TEMPLATES_PATH = "/templates";
    public static final String TEMPLATE_BY_ID_PATH = "/templates/{templateId}";
    public static final String LOGS_PATH = "/logs";
    public static final String LOGS_USER_PATH = "/user/{userId}";
    /**
     * Recherche par téléphone et par adresse IP : le critère est dans le CORPS.
     *
     * <p>Les chemins étaient {@code /phone/{phoneNumber}} et
     * {@code /by-ip/{ipAddress}}. Un numéro de téléphone et une adresse IP sont
     * des données personnelles — {@code AccountErasureService} les efface tous
     * deux à ce titre — et une URL est écrite en clair dans les journaux d'accès
     * du Gateway, des proxys et de la supervision, qui sont hors de portée de
     * cet effacement.
     *
     * <p>Ne pas remplacer par un paramètre de requête : la chaîne de requête est
     * journalisée avec le chemin.
     */
    public static final String LOGS_PHONE_SEARCH_PATH = "/search/by-phone";

    /** Voir {@link #LOGS_PHONE_SEARCH_PATH}. */
    public static final String LOGS_IP_SEARCH_PATH = "/search/by-ip";
    public static final String LOGS_EVENT_STATUS_PATH = "/event/{eventType}/status/{status}";
    public static final String LOGS_CLEANUP_PATH = "/cleanup";

    // API Endpoints - second facteur (TOTP) des administrateurs
    /** Connexion des administrateurs. Conserve : voir {@code AdminAuthController}. */
    public static final String ADMIN_LOGIN_ENDPOINT = "/admin/login";
    public static final String ADMIN_TOTP_ENROLL_ENDPOINT = "/admin/totp/enroll";
    public static final String ADMIN_TOTP_RESET_ENDPOINT = "/admin/totp/reset";
    /** Retrait force par un SUPER_ADMIN, sous {@code /admin/accounts}. */
    public static final String ACCOUNT_TOTP_RESET_PATH = "/{identifier}/totp/reset";

    // Second facteur (TOTP) - libelles et journaux
    /**
     * Nom affiche dans l'application d'authentification, a cote du compte.
     *
     * <p>Valeur par defaut assumee, contrairement a {@code app.frontend-url} qui
     * refuse de demarrer sans configuration : un emetteur est un libelle, pas
     * une adresse. Un libelle errone se corrige ; une redirection vers localhost
     * en production casse la connexion.
     */
    public static final String TOTP_EMETTEUR_DEFAUT = "Restart";
    // Administration des comptes - messages de confirmation
    public static final String COMPTE_DEVERROUILLE_MESSAGE = "Compte deverrouille";
    public static final String LOG_INFO_ADMIN_LOCKOUT_STATUS =
            "Admin - verification du statut de verrouillage pour {}";
    public static final String LOG_INFO_ADMIN_UNLOCK =
            "Admin - deverrouillage manuel du compte {}";
    public static final String LOG_INFO_ADMIN_RESET_ATTEMPTS =
            "Admin - reinitialisation du compteur de tentatives pour {}";
    public static final String LOG_INFO_ADMIN_REMAINING_ATTEMPTS =
            "Admin - consultation des tentatives restantes pour {}";
    public static final String LOG_WARN_ADMIN_LOCK =
            "Admin - verrouillage manuel du compte {} pour {} minutes";
    public static final String COMPTE_VERROUILLE_MESSAGE = "Compte verrouille manuellement";
    public static final String COMPTEUR_REINITIALISE_MESSAGE =
            "Compteur de tentatives reinitialise";

    public static final String EFFACEMENT_ENGAGE_MESSAGE =
            "Effacement engage. Conservez cet identifiant : il permet de justifier "
                    + "de votre demande.";
    public static final String IDENTIFIANT_REQUIS_MESSAGE =
            "Un identifiant est requis : numero de telephone ou adresse electronique";
    public static final String TOTP_MESSAGE_ENROLEMENT =
            "Scannez ce QR Code maintenant : ce secret ne sera plus affiche.";
    public static final String TOTP_MESSAGE_REINITIALISATION =
            "Second facteur remplace. Scannez ce QR Code maintenant : l'ancien "
                    + "appareil ne fonctionne plus et ce secret ne sera plus affiche.";
    public static final String TOTP_MESSAGE_RETIRE =
            "Second facteur retire. L'administrateur doit le re-enroler.";
    /**
     * Message de l'etape 1 du libre-service.
     *
     * <p>Il dit explicitement que le second facteur <b>n'est pas encore
     * actif</b>. Sans cette phrase, la personne croit avoir termine, ne
     * confirme jamais, et se croit protegee alors que sa connexion continue de
     * passer sans code — l'inverse de ce qu'elle a demande.
     */
    /**
     * Prefixe des numeros de substitution.
     *
     * <p>Une inscription par adresse seule n'a pas de numero : la colonne,
     * obligatoire, recoit une valeur prefixee ainsi. Ce n'est <b>pas</b> un
     * numero joignable, et tout ce qui envoie un SMS doit le reconnaitre.
     *
     * <p>Etait un champ prive de {@code VerificationCodeServiceImpl}. Des qu'un
     * second service a eu besoin de la meme reconnaissance, la constante devait
     * avoir un detenteur unique — sinon deux litteraux {@code "temp_"} a
     * maintenir d'accord, et le jour ou l'un change, l'autre envoie des SMS
     * vers une valeur qui n'est pas un numero.
     */
    public static final String TEMP_PHONE_PREFIX = "temp_";

    public static final String RESEND_CANAL_UNIQUE_REQUIS =
            "Indiquez un numero de telephone OU une adresse electronique, pas les deux";

    // Messages du libre-service
    public static final String TOTP_MESSAGE_CONFIRME =
            "Second facteur actif. Un code sera desormais demande a chaque connexion.";
    public static final String TOTP_MESSAGE_DESACTIVE =
            "Second facteur desactive. La connexion ne demande plus de code.";
    public static final String SESSION_FERMEE = "Session fermee";
    /**
     * Suffixe du message de fermeture en masse.
     *
     * <p>Le nombre precede : « 3 session(s) fermee(s) ». Dire combien evite la
     * question qui suit immediatement, et signale le cas a zero — aucune autre
     * session n'etait ouverte, ce qui est une information utile apres un
     * soupçon.
     */
    public static final String SESSIONS_FERMEES_SUFFIXE = " session(s) fermee(s)";
    public static final String COMPTE_SOCIAL_DELIE = "Fournisseur delie du compte";

    // ------------------------------------------------------------------
    // Appareils de confiance
    // ------------------------------------------------------------------
    /**
     * Message de remise du jeton d'appareil.
     *
     * <p>Il dit explicitement que le jeton ne sera plus affiche. Le jeton n'est
     * stocke que hache : personne ne peut le relire, administrateur compris. Un
     * client qui ne le conserve pas devra repasser le second facteur, et la
     * confiance precedente restera dans sa liste jusqu'a son echeance sans
     * qu'il sache a quoi elle correspond.
     *
     * <p>Meme precaution que TOTP_MESSAGE_ENROLEMENT_A_CONFIRMER.
     */
    public static final String CONFIANCE_JETON_UNIQUE =
            "Conservez ce jeton maintenant : il ne sera plus affiche. Presentez-le "
                    + "dans l'en-tete X-Device-Token pour ne plus saisir de code sur "
                    + "cet appareil.";

    public static final String APPAREIL_NOM_TROP_LONG =
            "Le nom de l'appareil ne peut pas depasser 120 caracteres";

    /** Nom de repli quand l'User-Agent n'apprend rien. */
    public static final String APPAREIL_NOM_INCONNU = "Appareil inconnu";

    /** Auteurs possibles d'une revocation. Valeurs BORNEES : etiquettes d'audit. */
    public static final String REVOQUE_PAR_PROPRIETAIRE = "proprietaire";
    public static final String REVOQUE_PAR_ADMINISTRATION = "administration";

    public static final String CONFIANCE_REVOQUEE = "Appareil retire des appareils de confiance";
    public static final String CONFIANCES_REVOQUEES_SUFFIXE = " appareil(s) retire(s) de la confiance";

    /** Le JETON n'est jamais journalise : il dispense du second facteur. */
    public static final String LOG_INFO_CONFIANCE_ACCORDEE =
            "Confiance accordee a l'appareil {} du compte {}";
    public static final String LOG_INFO_CONFIANCES_REVOQUEES =
            "{} appareil(s) de confiance revoque(s) pour {}";
    public static final String LOG_WARN_CONFIANCE_AUTRE_COMPTE =
            "Jeton d'appareil {} presente sur le compte {} : refuse";
    public static final String LOG_WARN_CONFIANCE_REVOCATION_REFUSEE =
            "Revocation refusee : appareil {} absent ou d'un autre compte que {}";
    public static final String LOG_ERROR_CONFIANCE_ILLISIBLE =
            "Appareils de confiance illisibles ({}) : le second facteur sera demande";

    // ------------------------------------------------------------------
    // Codes de secours du second facteur
    // ------------------------------------------------------------------

    /**
     * Avertissement accompagnant la remise d'un lot.
     *
     * <p>Les codes ne sont rendus qu'une fois : ils sont haches en base, donc
     * irrecuperables ensuite. Le dire au moment de la remise est la seule
     * occasion de le dire utilement.
     */
    public static final String CODES_DE_SECOURS_UNIQUES =
            "Conservez ces codes hors de votre telephone. Ils ne seront plus "
            + "affiches : chacun ne sert qu'une fois, et toute regeneration "
            + "invalide ce lot.";

    public static final String CODES_DE_SECOURS_AUCUN_RESTANT =
            "Aucun code de secours utilisable : regenerez un lot avant de perdre "
            + "l'acces a votre second facteur";

    /** Les CODES ne sont jamais journalises : ils remplacent le second facteur. */
    public static final String LOG_INFO_CODES_REGENERES =
            "{} code(s) de secours remis au compte {} ({} ancien(s) invalide(s))";
    public static final String LOG_INFO_CODES_INVALIDES =
            "{} code(s) de secours invalide(s) sur le compte {} : le secours suit "
            + "le secret qu'il secourait";
    public static final String LOG_INFO_CODES_SANS_SECOND_FACTEUR =
            "Codes de secours refuses au compte {} : second facteur inactif";
    public static final String LOG_WARN_CODE_DE_SECOURS_CONSOMME =
            "Code de secours {} consomme sur le compte {} : second facteur contourne";
    public static final String LOG_WARN_CODES_PRESQUE_EPUISES =
            "Plus que {} code(s) de secours sur le compte {} : sans regeneration, "
            + "la perte du second facteur deviendra irreversible";
    public static final String LOG_ERROR_CODES_ILLISIBLES =
            "Codes de secours illisibles ({}) : le code presente est refuse";

    // ------------------------------------------------------------------
    // Alertes de securite
    // ------------------------------------------------------------------
    // Ces textes ne contiennent JAMAIS de code, de jeton ni de lien d'action.
    //
    // Un courriel d'alerte est lu par qui a acces a la boite — et c'est
    // precisement ce dont on n'est pas sur a l'instant ou on l'envoie. Un lien
    // « ce n'etait pas moi, reinitialiser ici » offrirait a un attaquant ayant
    // acces a la boite un chemin de reprise en main ; la personne est donc
    // invitee a passer par l'application, qu'elle atteint elle-meme.

    // ------------------------------------------------------------------
    // Les TEXTES d'alerte ne sont plus ici
    // ------------------------------------------------------------------
    // Douze constantes vivaient a cet endroit : un sujet, un corps et un
    // message court par evenement, plus la conduite a tenir commune. Elles sont
    // parties dans modeles-notification.properties de notification-service,
    // sous les cles :
    //
    //   securite.mot-de-passe-modifie    securite.adresse-modifiee
    //   securite.numero-modifie          securite.nouvelle-connexion
    //
    // POURQUOI LE DEPLACEMENT. Ce module composait le texte puis le transmettait
    // tout fait a notification-service, qui tient lui aussi un moteur de
    // modeles. Deux detenteurs du meme message : la correction d'une faute
    // demandait une livraison d'auth-service, et le texte n'etait relisible
    // qu'en ouvrant ce fichier Java.
    //
    // Les textes sont desormais corrigeables a chaud par
    // PUT /api/v1/admin/modeles, et une variante courte pour le SMS est une
    // propriete du MODELE, non une constante separee que l'appelant choisit.
    //
    // Les constantes d'ASSEMBLAGE restent ci-dessous : les lignes « Quand /
    // Lieu / Adresse IP / Appareil » sont des FAITS, et ce service est le seul a
    // les connaitre. Il les assemble dans la variable {circonstances}, que les
    // quatre modeles placent.

    /** Le modele d'un code de verification, tous canaux. */
    public static final String MODELE_CODE_DE_VERIFICATION = "securite.code-de-verification";

    /** Le modele d'un code de reinitialisation de mot de passe, tous canaux. */
    public static final String MODELE_REINITIALISATION = "securite.reinitialisation-mot-de-passe";

    public static final String MODELE_ALERTE_MOT_DE_PASSE = "securite.mot-de-passe-modifie";
    public static final String MODELE_ALERTE_ADRESSE = "securite.adresse-modifiee";
    public static final String MODELE_ALERTE_NUMERO = "securite.numero-modifie";
    public static final String MODELE_ALERTE_CONNEXION = "securite.nouvelle-connexion";

    /**
     * La variable que les quatre modeles d'alerte placent.
     *
     * <p>Elle porte les lignes deja assemblees. Une seule variable et non quatre
     * parce que le lieu et l'adresse IP sont parfois INCONNUS, et la ligne doit
     * alors disparaitre : le moteur de modeles ne porte aucune conditionnelle —
     * volontairement, une conditionnelle supposant l'evaluation d'expressions
     * sur un texte desormais modifiable a chaud en base.
     */
    public static final String VARIABLE_CIRCONSTANCES = "circonstances";

    // ------------------------------------------------------------------
    // Changement de numero de telephone
    // ------------------------------------------------------------------

    /** Le code part au NOUVEAU numero : lui seul prouve qu'on le detient. */
    public static final String CHANGEMENT_NUMERO_CODE_ENVOYE =
            "Un code de confirmation a ete envoye au nouveau numero";
    public static final String NUMERO_CHANGE =
            "Numero de telephone modifie. L'ancien numero a ete prevenu.";

    public static final String LOG_INFO_CHANGEMENT_NUMERO_DEMANDE =
            "Changement de numero demande par le compte {}";
    public static final String LOG_INFO_NUMERO_CHANGE =
            "Numero change pour le compte {} : l'ancien est prevenu";
    public static final String LOG_WARN_CHANGEMENT_NUMERO_SANS_MOT_DE_PASSE =
            "Changement de numero refuse au compte {} : aucun mot de passe a opposer";
    public static final String LOG_WARN_CHANGEMENT_NUMERO_SANS_DEMANDE =
            "Confirmation de changement de numero refusee au compte {} : "
            + "aucune demande en cours ou demande expiree";

    // Les lignes de CIRCONSTANCES : des faits, que ce service est seul a
    // connaitre. Il les assemble, le modele les place. ALERTE_SEPARATEUR n'est
    // plus utilise ici — le modele porte lui-meme le saut de ligne avant
    // {circonstances} — mais reste disponible pour l'assemblage.
    public static final String ALERTE_SEPARATEUR = "\n\n";
    public static final String ALERTE_LIGNE_QUAND = "Quand : %s%n";
    public static final String ALERTE_LIGNE_LIEU = "Lieu : %s%n";
    public static final String ALERTE_LIGNE_ADRESSE_IP = "Adresse IP : %s%n";
    public static final String ALERTE_LIGNE_APPAREIL = "Appareil : %s%n";

    // ALERTE_CONDUITE_A_TENIR a disparu d'ici. Ce paragraphe — « Si vous
    // reconnaissez cette operation… » — fait partie du TEXTE de l'alerte, et
    // figure desormais dans les quatre modeles de notification-service.
    // ModelesSansDoublonTest verifie que les quatre copies restent identiques.

    public static final String ALERTE_DE_CONNEXION_ACTIVEE =
            "Vous serez prevenu par courriel a chaque connexion";
    public static final String ALERTE_DE_CONNEXION_DESACTIVEE =
            "Les alertes de connexion sont desactivees";

    public static final String LOG_INFO_ALERTE_DE_CONNEXION =
            "Alerte de nouvelle connexion mise a {} pour le compte {}";
    public static final String LOG_INFO_ALERTE_SANS_ADRESSE =
            "Alerte de connexion refusee au compte {} : adresse non verifiee";
    /**
     * L'envoi a eu lieu et a ete refuse — distinct d'une panne.
     *
     * <p>Ni le canal ni le motif ne figurent ici : le client les journalise au
     * point d'appel, ou il les connait precisement. Ce message-ci dit QUELLE
     * alerte et QUEL compte n'ont pas abouti, ce que le client ignore.
     */
    public static final String LOG_WARN_ALERTE_NON_REMISE =
            "Alerte de securite « {} » non remise au compte {} : la personne "
            + "n'a pas ete prevenue";

    public static final String LOG_WARN_ALERTE_SANS_DESTINATAIRE =
            "Alerte « {} » non envoyee au compte {} : aucune coordonnee verifiee";
    public static final String LOG_ERROR_ALERTE_NON_ENVOYEE =
            "Alerte « {} » non envoyee au compte {} ({}) : l'operation a bien eu lieu";

    // Messages de confirmation du contact
    public static final String CONFIRMATION_EMAIL_ENVOYEE =
            "Un code de confirmation a ete envoye a votre adresse electronique";
    public static final String CONFIRMATION_TELEPHONE_ENVOYEE =
            "Un code de confirmation a ete envoye par SMS";
    public static final String EMAIL_CONFIRME = "Adresse electronique confirmee";
    public static final String TELEPHONE_CONFIRME = "Numero de telephone confirme";

    // Messages de validation du changement de mot de passe
    public static final String ANCIEN_MOT_DE_PASSE_REQUIS =
            "Le mot de passe actuel est requis";
    public static final String NOUVEAU_MOT_DE_PASSE_REQUIS =
            "Le nouveau mot de passe est requis";
    public static final String CONFIRMATION_MOT_DE_PASSE_REQUISE =
            "La confirmation du mot de passe est requise";
    public static final String MOT_DE_PASSE_TROP_COURT =
            "Le mot de passe doit comporter au moins 8 caracteres";
    public static final String NOUVEAU_MOT_DE_PASSE_IDENTIQUE =
            "Le nouveau mot de passe doit differer de l'actuel";
    public static final String CONFIRMATION_MOT_DE_PASSE_DIFFERENTE =
            "La confirmation ne correspond pas au nouveau mot de passe";
    public static final String MOT_DE_PASSE_CHANGE =
            "Mot de passe modifie. Toutes les sessions ont ete fermees.";

    public static final String TOTP_MESSAGE_ENROLEMENT_A_CONFIRMER =
            "Scannez ce QR Code maintenant : ce secret ne sera plus affiche. "
                    + "Le second facteur n'est PAS encore actif : saisissez un "
                    + "code pour le confirmer.";
    public static final String LOG_WARN_SECOND_FACTEUR_ABSENT =
            "Connexion administrateur {} sans second facteur : enrolement requis";
    public static final String LOG_WARN_TOTP_DEJA_ACTIF =
            "Enrolement TOTP refuse pour {} : deja actif";
    public static final String LOG_WARN_TOTP_PAS_ACTIF =
            "Reinitialisation TOTP refusee pour {} : aucun second facteur actif";
    public static final String LOG_ERROR_TOTP_SECRET_ABSENT =
            "Compte d'administration {} sans secret TOTP";
    public static final String LOG_INFO_TOTP_ENROLE =
            "Second facteur enrole pour {} - le secret ne sera plus remis";
    public static final String LOG_WARN_TOTP_RETIRE =
            "Second facteur retire pour {} par un super administrateur";
    public static final String LOG_INFO_ADMIN_LOGIN_ATTEMPT =
            "Tentative de connexion administrateur";
    public static final String LOG_WARN_ADMIN_LOGIN_REFUSEE =
            "Connexion administrateur refusee pour l'identifiant fourni";
    public static final String LOG_AUDIT_ADMIN_LOGIN_REFUSEE =
            "Connexion administrateur refusee";
    public static final String LOG_AUDIT_TOTP_RETIRE =
            "Second facteur retire par le super administrateur ";
    public static final String LOG_WARN_ADMIN_TOTP_RETRAIT_DEMANDE =
            "Super administrateur - retrait du second facteur demande";



    // Feign Client
    public static final String LOG_FEIGN_CLIENT_INTERCEPTOR = "Feign Client Interceptor - {}";
    public static final String LOG_FEIGN_CLIENT_INTERCEPTOR_ERROR = "Feign Client Interceptor Error - {}";

    // Log Messages
    public static final String LOG_VALIDATION_ERROR = "Validation error: {}";

    // Exception Messages
    public static final String AUTH_EXCEPTION = "Auth exception: {} - {}";
    public static final String AUTH_UNEXPECTED_EXCEPTION = "Unexpected exception";

    // Gateway Access Filter
    public static final String GATEWAY_ACCESS_FILTER = " Accès refusé - Clé secrète invalide ou absente - URI: {}";
    public static final String X_SECRET_KEY_INVALID = "Header X-Secret-Key invalide ou absent";
    public static final String RQ_AUTHORIZED = " Requête autorisée - URI: {}";
    public static final String KEY_REQUIRED = " Accès refusé. Clé secrète requise.";
    public static final String X_SECRET_KEY_REQUIRED = "Contacter le developpeur pour obtenir plus d'informations.";

    // Token Constants
    public static final String BEARER_TOKEN = "Bearer";

    // OAuth2 Constants
    public static final String OAUTH2_PROVIDER_GOOGLE = "google";
    public static final String OAUTH2_PROVIDER_FACEBOOK = "facebook";
    public static final String OAUTH2_STATE_PREFIX = "oauth2_state_";
    public static final String OAUTH2_TEMP_TOKEN_PREFIX = "oauth2_temp_";
    public static final int OAUTH2_TEMP_TOKEN_EXPIRATION_MINUTES = 10;

    /**
     * Prefixe du code de remise a usage unique.
     *
     * <p>Remplace le transport des jetons dans la chaine de requete de la
     * redirection. Voir {@code CodeDeRemiseOAuth2Redis}.
     */
    public static final String OAUTH2_REMISE_PREFIX = "oauth2_remise_";

    /**
     * Echange du code de remise contre les jetons, en POST.
     *
     * <p>Sans le prefixe {@code /auth} : il vient de {@code API_BASE_PATH}, pose
     * par le {@code @RequestMapping} de classe d'{@code AuthController}. Meme
     * forme que {@link #OAUTH2_CALLBACK_ENDPOINT} juste au-dessus.
     *
     * <p>Le prefixe y figurait, et le chemin reel devenait
     * {@code /auth/auth/oauth2/exchange}. Les listes de chemins publics, elles,
     * portaient la bonne valeur : la garde qui les compare passait donc, et
     * l'endpoint aurait ete injoignable. C'est la reference frontend engendree
     * — qui part de l'annotation et non des listes — qui a montre le doublon.
     */
    public static final String OAUTH2_EXCHANGE_ENDPOINT = "/oauth2/exchange";

    // OAuth2 URLs
    public static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo";
    public static final String FACEBOOK_TOKEN_URL = "https://graph.facebook.com/v18.0/oauth/access_token";
    public static final String FACEBOOK_USERINFO_URL = "https://graph.facebook.com/me?fields=id,name,email,picture";

    /**
     * Point d'autorisation d'Apple.
     *
     * <p>Deux parametres y sont <b>imposes</b> par Apple et non facultatifs :
     * {@code response_type=code id_token} — l'identite vient du
     * {@code id_token}, Apple n'ayant aucun point de profil — et
     * {@code response_mode=form_post}, qu'Apple exige des que le perimetre
     * demande {@code name} ou {@code email}. C'est ce dernier qui impose un
     * rappel en POST la ou Google et Facebook se contentent d'un GET.
     */
    public static final String APPLE_AUTH_URL = "https://appleid.apple.com/auth/authorize";

    /** Echange du code d'autorisation chez Apple. */
    public static final String APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";

    /** Cles publiques d'Apple. Accessible sans compte developpeur. */
    public static final String APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys";

    /**
     * Revocation d'un jeton chez Apple.
     *
     * <p>Son appel n'est pas optionnel pour une application distribuee sur l'App
     * Store qui propose Sign in with Apple et la suppression de compte : c'est
     * une condition de revue depuis 2022, et Apple teste le parcours.
     */
    public static final String APPLE_REVOKE_URL = "https://appleid.apple.com/auth/revoke";

    /**
     * Domaine des adresses relayees par Apple.
     *
     * <p>Quand la personne choisit « Masquer mon adresse », Apple fournit une
     * adresse de ce domaine, qui relaie le courrier vers la vraie. Elle est
     * valide et doit etre acceptee : la refuser reviendrait a refuser la
     * connexion a toute personne ayant fait ce choix, qui est le defaut propose
     * par Apple.
     */
    public static final String APPLE_PRIVATE_RELAY_DOMAIN = "@privaterelay.appleid.com";

    // OAuth2 Messages
    public static final String OAUTH2_LOGIN_SUCCESS = "Authentification OAuth2 réussie";
    public static final String OAUTH2_PROFILE_INCOMPLETE = "Profil incomplet - veuillez compléter vos informations";
    public static final String OAUTH2_PROFILE_COMPLETED = "Profil complété avec succès";
    public static final String OAUTH2_INVALID_PROVIDER = "Provider OAuth2 invalide";
    public static final String OAUTH2_INVALID_CODE = "Code d'autorisation OAuth2 invalide";
    public static final String OAUTH2_INVALID_TEMP_TOKEN = "Token temporaire invalide ou expiré";
    public static final String OAUTH2_ACCOUNT_LINKED = "Compte OAuth2 lié avec succès";

    // OAuth2 Error Messages
    public static final String OAUTH2_ERROR_EXCHANGE_TOKEN = "Erreur lors de l'échange du code d'autorisation";
    public static final String OAUTH2_ERROR_FETCH_USERINFO = "Erreur lors de la récupération des informations utilisateur";
    public static final String OAUTH2_ERROR_PROVIDER_NOT_SUPPORTED = "Provider non supporté: {}";

    // OAuth2 Missing Fields
    public static final String OAUTH2_MISSING_PHONE = "phoneNumber";
    public static final String OAUTH2_MISSING_COUNTRY_CODE = "countryCode";

    // OAuth2 Validation Messages
    public static final String OAUTH2_PHONE_REQUIRED = "Le numéro de téléphone est requis";
    public static final String OAUTH2_PHONE_INVALID_FORMAT = "Format de numéro de téléphone invalide";
    public static final String OAUTH2_COUNTRY_CODE_REQUIRED = "Le code pays est requis";
    public static final String OAUTH2_COUNTRY_CODE_SIZE = "Le code pays doit contenir entre 2 et 5 caractères";
    public static final String OAUTH2_TEMP_TOKEN_REQUIRED = "Le token temporaire OAuth2 est requis";
    public static final String OAUTH2_CALLBACK_CODE_REQUIRED = "Le code d'autorisation est requis";
    public static final String OAUTH2_CALLBACK_PROVIDER_REQUIRED = "Le provider OAuth2 est requis";
    public static final String OAUTH2_CALLBACK_REDIRECT_URI_REQUIRED = "L'URI de redirection est requis";

    // OAuth2 Regex Patterns
    public static final String OAUTH2_PHONE_PATTERN = "^\\+?[1-9]\\d{1,14}$";

    // OAuth2 Endpoint Paths (pour frontend)
    public static final String OAUTH2_GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String OAUTH2_FACEBOOK_AUTH_URL = "https://www.facebook.com/v18.0/dialog/oauth";

}
