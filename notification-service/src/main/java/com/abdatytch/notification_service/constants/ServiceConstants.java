package com.abdatytch.notification_service.constants;

/**
 * Messages de journalisation du sous-systeme SMS.
 *
 * <h2>Pourquoi ces constantes ont suivi le code</h2>
 *
 * Elles vivaient dans {@code auth-service.constants.ServiceConstants}, melees
 * aux messages de l'authentification. Le sous-systeme SMS ayant rejoint ce
 * module, les laisser la-bas aurait cree une dependance d'ici vers
 * auth-service pour de simples chaines — et un module de notifications qui
 * depend du module d'authentification pour journaliser est un couplage que
 * rien ne justifie.
 *
 * <p>Elles ont donc ete RETIREES de ServiceConstants, et non recopiees : deux
 * exemplaires d'un meme message auraient diverge a la premiere reformulation,
 * et les journaux de deux services auraient alors decrit differemment le meme
 * evenement.
 *
 * <h2>Ce que ces messages ne contiennent jamais</h2>
 *
 * Ni le contenu d'un SMS — donc jamais un code a usage unique — ni les
 * identifiants d'API du fournisseur. {@code AucunJustificatifDansLesJournauxTest}
 * de platform-commons balaie les sources de tous les modules et verrouille
 * cette regle.
 *
 * <p>Les numeros de telephone, en revanche, apparaissent dans plusieurs
 * messages : ce sont des donnees personnelles, et leur presence dans les
 * journaux releve de la duree de conservation, pas du code. Signale tel quel.
 */
public final class ServiceConstants {

    private ServiceConstants() {
        throw new UnsupportedOperationException("Cette classe ne peut pas etre instanciee");
    }

    public static final String LOG_ERROR_SMS_ACCUSE_REGISTRE = "Lecture du registre des envois SMS impossible ({})";
    public static final String LOG_ERROR_SMS_FOURNISSEUR_NON_CONFIGURE = "Fournisseur SMS '{}' sélectionné mais incomplètement configuré : aucun SMS ne partira";
    public static final String LOG_ERROR_SMS_NUMERO_INEXPLOITABLE = "Numéro de téléphone inexploitable pour le fournisseur SMS, envoi abandonné";
    public static final String LOG_ERROR_SMS_REFUS_FOURNISSEUR = "Le fournisseur SMS a refusé l'envoi (code {})";
    public static final String LOG_ERROR_SMS_REPONSE_ILLISIBLE = "Réponse du fournisseur SMS illisible : champ 'code' absent ou invalide";
    public static final String LOG_ERROR_SMS_SOLDE_EPUISE = "Solde SMS ÉPUISÉ pour {} : {} — les SMS ne partent plus";
    public static final String LOG_ERROR_SMS_SOLDE_ILLISIBLE = "Relevé du solde SMS impossible ({})";
    public static final String LOG_ERROR_SMS_STATUT_HTTP = "Le fournisseur SMS a répondu un statut HTTP {}";
    public static final String LOG_ERROR_SMS_TRANSPORT = "Échec de transport vers le fournisseur SMS ({})";
    public static final String LOG_ERROR_SMS_TYPE_NOTIFICATION = "sms.afriksms.notify-type doit valoir 1 (POST) ou 2 (GET), reçu: {}";
    public static final String LOG_ERROR_SMS_URL_RAPPEL_ABSENTE = "sms.afriksms.notify-url est vide : impossible de déclarer une URL de rappel";
    public static final String LOG_INFO_SENDING_VERIFICATION_SMS = "Envoi SMS de vérification à: {}";
    public static final String LOG_INFO_SMS_ACCUSE = "Accusé de réception SMS pour {} : {}";
    public static final String LOG_INFO_SMS_DEV_MODE = "MODE DEV - Code SMS: {}";
    public static final String LOG_INFO_SMS_FOURNISSEUR_PRET = "Fournisseur SMS actif: {}";
    public static final String LOG_INFO_SMS_SENT_SUCCESS = " SMS envoyé avec succès à: {}";
    public static final String LOG_INFO_SMS_SOLDE = "Solde SMS {} : {}";
    public static final String LOG_INFO_SMS_URL_RAPPEL_DECLAREE = "URL de rappel SMS déclarée chez le fournisseur (typeNotification: {})";
    public static final String LOG_WARN_SMS_ACCUSE_CORPS_ILLISIBLE = "Corps d'accusé de réception SMS illisible ({})";
    public static final String LOG_WARN_SMS_ACCUSE_CORPS_INATTENDU = "Corps d'accusé de réception SMS de forme inattendue (Content-Type: {})";
    public static final String LOG_WARN_SMS_ACCUSE_INCONNU = "Accusé de réception SMS pour un envoi inconnu du registre (statut {})";
    public static final String LOG_WARN_SMS_ACCUSE_REFUSE = "Accusé de réception SMS refusé : clé absente ou invalide";
    public static final String LOG_WARN_SMS_ACCUSE_SANS_IDENTIFIANT = "Accusé de réception SMS sans resourceId exploitable (statut {})";
    public static final String LOG_WARN_SMS_DISABLED = "Service SMS désactivé. SMS non envoyé à: {}";
    public static final String LOG_WARN_SMS_EXPEDITEUR_TRONQUE = "Nom d'expéditeur SMS plus long que {} caractères, tronqué en: {}";
    public static final String LOG_WARN_SMS_GLOBALEMENT_DESACTIVE = "sms.enabled = false : aucun SMS ne sera envoyé par ce service";
    public static final String LOG_WARN_SMS_NON_LIVRE = "SMS non livré à {} : {}";

    /**
     * Numero absent de {@code sms.numeros-autorises}.
     *
     * <p>Le numero EST journalise, contrairement a la regle habituelle, et c'est
     * le but : ce garde-fou n'existe que pour le developpement, et savoir quel
     * numero a ete ecarte est la seule information utile quand un essai ne
     * recoit rien. En production la liste est vide, donc ce message n'apparait
     * jamais.
     */
    public static final String LOG_WARN_SMS_HORS_LISTE =
            "Numéro {} absent de sms.numeros-autorises : aucun SMS envoyé";

    /**
     * Plafond journalier de depense atteint.
     *
     * <p>En {@code ERROR} et non en {@code WARN} : des utilisateurs ne
     * recoivent plus leur code, et la cause n'est pas une panne mais une limite
     * voulue. Sans ce message, le symptome serait indistinguable d'un
     * fournisseur en panne.
     */
    public static final String LOG_ERROR_SMS_PLAFOND_ATTEINT =
            "Plafond journalier de SMS atteint : envoi refusé vers {}";

    public static final String LOG_WARN_SMS_REGISTRE_INDISPONIBLE = "Registre des envois SMS indisponible, accusé non rattachable ({})";
    public static final String LOG_WARN_SMS_SOLDE_BAS = "Solde SMS bas pour {} : {} (seuil d'alerte {})";
    public static final String LOG_WARN_SMS_SOLDE_VIDE = "Le fournisseur n'a rendu aucun solde : rien à surveiller";
}
