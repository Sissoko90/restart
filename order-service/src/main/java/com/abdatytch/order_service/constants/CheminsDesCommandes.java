package com.abdatytch.order_service.constants;

/**
 * Les prefixes servis par ce module, nommes UNE fois.
 *
 * <p>Ces chemins figurent a quatre endroits : le controleur, la route de la
 * passerelle, {@code CheminCible}, et le generateur de collections Postman. Une
 * faute de frappe dans l'un des quatre produit un 404 que rien n'explique.
 *
 * <p>La passerelle retire {@code /api/v1} et ne reecrit rien : ce service recoit
 * donc {@code /orders/**}. L'administration est servie sous
 * {@code /admin/orders/**}, qui doit etre declaree AVANT la route generale
 * {@code /api/v1/admin/**} de la passerelle, sinon interface-service la
 * capterait. C'est exactement le piege que market-service a rencontre.
 */
public final class CheminsDesCommandes {

    /** Le panier de l'acheteur courant. */
    public static final String PANIER = "/orders/panier";

    /** Les commandes de l'acheteur courant. */
    public static final String COMMANDES = "/orders/commandes";

    /** Les commandes recues par la boutique de l'utilisateur courant. */
    public static final String BOUTIQUE = "/orders/boutique";

    /** L'arbitrage et l'exploitation, cote Sankadi. */
    public static final String ADMIN_COMMANDES = "/admin/orders/commandes";
    public static final String ADMIN_RETOURS = "/admin/orders/retours";

    private CheminsDesCommandes() {
    }
}
