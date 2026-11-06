package com.abdatytch.notification_service.constants;

/**
 * Les codes d'erreur de ce service.
 *
 * <h2>Pourquoi trois codes seulement</h2>
 *
 * Le sous-systeme SMS arrive d'auth-service, ou il utilisait un
 * {@code ErrorCode} de cent entrees dont il n'en touchait que deux. Recopier
 * l'enum entiere aurait donne a notification-service un vocabulaire de
 * verrouillage de compte, de jetons perimes et de deux facteurs, qu'aucune ligne
 * de ce module ne peut lever : une surface a maintenir pour rien, et des codes
 * definis a deux endroits qui divergent des la premiere correction.
 *
 * <h2>Pourquoi les memes chaines {@code SYS_...} qu'auth-service</h2>
 *
 * Ce ne sont pas des codes « recopies » : ce sont les <b>memes conditions</b>.
 * Un fournisseur SMS injoignable est le meme incident, qu'il soit constate ici
 * ou signale par le client d'auth-service. Leur donner un prefixe different
 * obligerait a chercher deux codes dans les journaux pour une seule panne.
 *
 * <h2>Le nom porte la regle</h2>
 *
 * {@code UNAVAILABLE} dans le nom signifie <b>indisponibilite de la
 * dependance</b>, et deux mecanismes s'appuient dessus sans se connaitre :
 * {@link com.abdatytch.notification_service.exception.GlobalExceptionHandler}
 * pour rendre un 503 plutot qu'un 500, et
 * {@code DisjoncteurSortantConfig.estUneIndisponibilite} pour decider ce qui
 * compte comme un echec. Une seule regle, deux endroits qui s'y conforment.
 */
public enum ErrorCode {

    /**
     * Le fournisseur a repondu, et il a refuse — ou sa reponse etait
     * inexploitable.
     *
     * <p><b>N'ouvre pas le disjoncteur</b>, volontairement : un refus prouve que
     * le fournisseur fonctionne. Le compter comme une panne fermerait le chemin
     * SMS sur du trafic sain, par exemple une rafale de numeros mal formes.
     */
    SMS_SERVICE_ERROR("SYS_5002", "Erreur lors de l'envoi du SMS"),

    /**
     * Le fournisseur SMS ne repond pas — distinct d'un refus de sa part.
     *
     * <p>La distinction n'est pas cosmetique : elle decide du reessai. Un refus
     * se reessaie en vain et <b>coute un credit SMS a chaque tentative</b> ;
     * une panne de transport, elle, vaut d'etre reessayee plus tard.
     */
    SMS_PROVIDER_UNAVAILABLE("SYS_5006", "Fournisseur SMS indisponible"),

    /**
     * Le disjoncteur des appels sortants est ouvert : l'appel n'a pas eu lieu.
     *
     * <p>Nomme {@code UNAVAILABLE} pour que les deux mecanismes cites dans la
     * javadoc de classe le traitent correctement sans cas particulier.
     */
    SERVICE_UNAVAILABLE("SYS_5005", "Service temporairement indisponible");

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
}
