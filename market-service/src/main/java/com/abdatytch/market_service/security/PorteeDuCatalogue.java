package com.abdatytch.market_service.security;

import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.enums.Visibilite;

import java.util.UUID;

/**
 * Ce que le demandeur a le droit de voir du catalogue.
 *
 * <h2>LE SEUL DETENTEUR DE LA REGLE DE VISIBILITE</h2>
 *
 * Quatre conditions decident qu'un produit est visible, et aucune n'est
 * evidente :
 *
 * <ul>
 *   <li>la boutique qui le vend est APPROUVEE ;</li>
 *   <li>le produit est approuve par la moderation et actif ;</li>
 *   <li>un professionnel voit tous les pays et les deux visibilites ;</li>
 *   <li>un particulier ne voit que SON pays, et seulement ce qui est
 *       {@link Visibilite#PUBLIQUE}.</li>
 * </ul>
 *
 * <p>Recopier ces conditions dans chaque requete serait la facon la plus sure
 * d'en oublier une, un jour, dans une seule. Ce depot a paye cinq fois cette
 * erreur : cinq listes de chemins publics qui divergeaient, dont trois
 * ouvraient plus large que leur intention.
 *
 * <p>Il existe donc <b>une</b> clause SQL, {@link #CLAUSE_SQL}, et <b>une</b>
 * decision unitaire, {@link #voit(Produit)}. Les deux doivent dire la meme
 * chose, et {@code RegleDeVisibiliteUniqueTest} verifie qu'aucune autre requete
 * du module ne filtre sur {@code visibilite} ou {@code statut_moderation}.
 *
 * <h2>Pourquoi le pays vient d'AILLEURS</h2>
 *
 * {@code pays} est lu chez user-service, jamais dans la requete. Un paramètre
 * de requete laisserait un particulier elargir sa portee en se declarant d'un
 * autre pays — ce qui est exactement la frontiere que ce service defend.
 *
 * <h2>Un refus rend 404</h2>
 *
 * Jamais 403. Un produit reserve aux professionnels ne doit pas etre
 * distinguable d'un produit inexistant : un 403 confirmerait la reference, et
 * une reference confirmee se revend.
 *
 * @param authUserId     le compte demandeur
 * @param pays           son pays, lu chez user-service
 * @param boutiqueId     sa boutique, ou {@code null} s'il n'en a pas
 * @param professionnel  sa boutique est APPROUVEE
 * @param administrateur il modere, donc il voit tout
 */
public record PorteeDuCatalogue(
        String authUserId,
        String pays,
        UUID boutiqueId,
        boolean professionnel,
        boolean administrateur) {

    /**
     * La clause SQL de visibilite, ecrite UNE fois.
     *
     * <p>Les trois branches, dans l'ordre du moins couteux au plus couteux :
     * l'administrateur voit tout, le proprietaire voit sa propre boutique quel
     * que soit l'etat de ses produits, et le reste passe par les quatre
     * conditions.
     *
     * <p>{@code :boutiqueId IS NOT NULL AND} est obligatoire devant la
     * comparaison : en SQL, {@code x = NULL} ne vaut pas faux mais INCONNU, et
     * la branche ne serait jamais franchie — ce qui marcherait par accident
     * ici, et casserait a la premiere reecriture en {@code OR NOT}.
     *
     * <p>La jointure sur {@code boutique} n'est pas evitable : suspendre une
     * boutique doit retirer ses produits de la vitrine, et denormaliser son
     * statut sur chaque produit creerait un second detenteur de cet etat.
     */
    public static final String CLAUSE_SQL = """
            (:administrateur = TRUE
             OR (:boutiqueId IS NOT NULL AND p.boutique_id = :boutiqueId)
             OR (b.statut = 'APPROUVEE'
                 AND p.statut_moderation = 'APPROUVE'
                 AND p.actif = TRUE
                 AND (:professionnel = TRUE
                      OR (p.pays = :pays AND p.visibilite = 'PUBLIQUE'))))
            """;

    /**
     * La meme decision, sur un produit deja charge.
     *
     * <p>Sert aux lectures unitaires et aux tests. Elle ne remplace pas la
     * clause SQL : filtrer en memoire une liste ramenee en entier ferait lire a
     * la base ce que l'appelant n'a pas le droit de voir, et paginerait sur le
     * mauvais ensemble — la page 2 sauterait des articles.
     *
     * @param statutBoutiqueApprouve l'etat de la boutique vendeuse, que le
     *                               produit ne porte pas
     */
    public boolean voit(Produit produit, boolean statutBoutiqueApprouve) {
        if (produit == null) {
            return false;
        }
        if (administrateur) {
            return true;
        }
        if (boutiqueId != null && boutiqueId.equals(produit.getBoutiqueId())) {
            return true;
        }
        if (!statutBoutiqueApprouve || !produit.estPublie()) {
            return false;
        }
        if (professionnel) {
            return true;
        }
        return Visibilite.PUBLIQUE == produit.getVisibilite()
                && pays != null && pays.equals(produit.getPays());
    }

    /** La portee d'un administrateur, qui ne depend d'aucune boutique. */
    public static PorteeDuCatalogue administrateur(String authUserId) {
        return new PorteeDuCatalogue(authUserId, null, null, false, true);
    }
}
