package com.abdatytch.security_service.enums;

/**
 * Pourquoi une adresse est bloquee.
 *
 * <h2>Pourquoi le motif est une enumeration et non du texte libre</h2>
 *
 * {@code IpBlacklistWebFilter} bloquait sans motif : la liste noire ne portait
 * qu'une adresse et une duree de vie d'un jour, figee dans le code. Impossible
 * de savoir, trois jours plus tard, pourquoi une adresse avait ete bloquee —
 * donc impossible de decider s'il fallait la debloquer.
 *
 * <p>Du texte libre aurait regle la tracabilite et rien d'autre. Une enumeration
 * permet en plus :
 *
 * <ul>
 *   <li>de compter par motif dans Prometheus, avec des etiquettes <b>bornees</b>
 *       — un motif libre ferait exploser la cardinalite et rendrait la metrique
 *       inexploitable ;</li>
 *   <li>de distinguer ce qui se debloque tout seul de ce qui exige une
 *       decision : une detection automatique peut se tromper, une decision
 *       humaine non.</li>
 * </ul>
 */
public enum MotifDeRegleIp {

    /**
     * Force brute detectee automatiquement.
     *
     * <p>Automatique, donc faillible : derriere un NAT d'operateur, une seule
     * personne malveillante fait bloquer tout un quartier. Ce motif doit porter
     * une echeance.
     */
    FORCE_BRUTE(true),

    /** Debit excessif detecte automatiquement. Faillible pour la meme raison. */
    DEBIT_EXCESSIF(true),

    /** Tentative d'injection ou de traversee de chemin detectee par un filtre. */
    ATTAQUE_APPLICATIVE(true),

    /**
     * Decision d'un administrateur.
     *
     * <p>Peut etre permanente : quelqu'un a regarde et a tranche. C'est le seul
     * motif pour lequel une regle sans echeance se justifie.
     */
    DECISION_ADMINISTRATIVE(false),

    /** Signalement externe — fournisseur, plainte, autorite. */
    SIGNALEMENT_EXTERNE(false);

    private final boolean detectionAutomatique;

    MotifDeRegleIp(boolean detectionAutomatique) {
        this.detectionAutomatique = detectionAutomatique;
    }

    /**
     * Vrai si la regle vient d'une detection et non d'une personne.
     *
     * <p>Une detection automatique <b>doit</b> porter une echeance : sans elle,
     * un faux positif enferme une adresse pour toujours et personne ne le
     * remarque, puisque la victime ne peut meme plus se connecter pour se
     * plaindre.
     */
    public boolean estUneDetectionAutomatique() {
        return detectionAutomatique;
    }
}
