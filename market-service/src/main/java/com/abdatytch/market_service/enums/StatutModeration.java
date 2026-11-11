package com.abdatytch.market_service.enums;

/**
 * L'etat de moderation d'un produit ou d'un avis.
 *
 * <p>Rien n'est publie directement. Un produit cree par une boutique et un avis
 * ecrit par un client passent tous deux par la file de moderation : l'un porte
 * un prix et des photos sous le nom de la plateforme, l'autre du texte libre
 * sous le nom d'une boutique qui ne l'a pas ecrit.
 */
public enum StatutModeration {

    EN_ATTENTE,
    APPROUVE,
    REJETE;

    /** Visible au-dela de son auteur. */
    public boolean estPublie() {
        return this == APPROUVE;
    }
}
