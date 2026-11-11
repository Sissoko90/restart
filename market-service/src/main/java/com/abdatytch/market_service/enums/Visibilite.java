package com.abdatytch.market_service.enums;

/**
 * A qui un produit est destine.
 *
 * <p>La visibilite ne fait PAS tout : le pays compte aussi. Un produit
 * {@link #PUBLIQUE} du Senegal n'est pas visible d'un particulier malien. La
 * regle complete vit en un seul endroit, {@code PorteeDuCatalogue} — dispersee
 * dans les requetes, elle diverge.
 */
public enum Visibilite {

    /** Visible de tous, dans la limite du pays pour un particulier. */
    PUBLIQUE,

    /**
     * Reservee aux comptes professionnels : le prix de gros.
     *
     * <p>Un particulier ne doit pas la voir du tout — ni le produit, ni son
     * prix, ni son existence. Un refus rend donc 404 et jamais 403 : un 403
     * confirmerait que la reference existe.
     */
    RESERVEE_AUX_PRO
}
