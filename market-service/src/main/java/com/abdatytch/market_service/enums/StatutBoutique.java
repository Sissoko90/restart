package com.abdatytch.market_service.enums;

/**
 * L'etat d'une boutique dans son cycle de validation.
 *
 * <p>C'est cette enumeration, et non un role porte par le jeton, qui dit si un
 * compte est professionnel. Un role ne saurait pas exprimer « approuvee le 11
 * novembre, par cet administrateur, sur ce RCCM », ni etre retire sans toucher
 * au jeton de la personne.
 */
public enum StatutBoutique {

    /** Soumise, en file de moderation. Le proprietaire la voit, personne d'autre. */
    EN_ATTENTE,

    /** Validee. C'est le SEUL etat qui rend son proprietaire professionnel. */
    APPROUVEE,

    /**
     * Refusee, avec un motif rendu au proprietaire.
     *
     * <p>Le motif est rendu, et c'est delibere : un refus dont on ignore la
     * cause se represente a l'identique, et fait deux fois le travail de
     * l'equipe de moderation.
     */
    REJETEE,

    /**
     * Suspendue apres coup.
     *
     * <p>Distinct de {@link #REJETEE} : une boutique suspendue a deja ete
     * approuvee, donc elle a des produits publies qu'il faut retirer de la
     * vitrine sans les supprimer.
     */
    SUSPENDUE;

    /** Le proprietaire est-il professionnel de ce fait. */
    public boolean rendProfessionnel() {
        return this == APPROUVEE;
    }
}
