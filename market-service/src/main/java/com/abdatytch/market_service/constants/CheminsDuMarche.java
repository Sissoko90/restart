package com.abdatytch.market_service.constants;

/**
 * Les prefixes servis par ce module, nommes UNE fois.
 *
 * <h2>Pourquoi des constantes et non des litteraux d'annotation</h2>
 *
 * Ces chemins figurent a quatre endroits : le controleur, la route de la
 * passerelle, {@code CheminCible}, et le generateur de collections Postman. Une
 * faute de frappe dans l'un des quatre produit un 404 que rien n'explique.
 *
 * <p>Les constantes ne relient que les deux premiers — la passerelle est un
 * autre module — mais elles rendent au moins la valeur CITABLE depuis un test,
 * ce qui permet de pincer l'accord.
 *
 * <h2>Le prefixe public est {@code /api/v1/market}</h2>
 *
 * La passerelle retire {@code /api/v1} et ne reecrit rien : ce service recoit
 * donc {@code /market/**}. L'administration est servie sous
 * {@code /admin/market/**}, qui tombe dans la regle {@code /admin/**} de la
 * chaine de securite — et doit etre declaree AVANT la route generale
 * {@code /api/v1/admin/**} de la passerelle, sinon interface-service la
 * capterait.
 */
public final class CheminsDuMarche {

    public static final String PRODUITS = "/market/produits";
    public static final String BOUTIQUES = "/market/boutiques";
    public static final String CATEGORIES = "/market/categories";

    public static final String ADMIN_BOUTIQUES = "/admin/market/boutiques";
    public static final String ADMIN_PRODUITS = "/admin/market/produits";
    public static final String ADMIN_AVIS = "/admin/market/avis";
    public static final String ADMIN_CATEGORIES = "/admin/market/categories";

    private CheminsDuMarche() {
    }
}
