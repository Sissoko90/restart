package com.abdatytch.market_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Une demande de reservation de stock, venue d'order-service.
 *
 * <h2>LA PORTEE DE L'ACHETEUR EST DANS LA DEMANDE</h2>
 *
 * Et c'est market-service qui l'applique, pas l'appelant. La regle de
 * visibilite n'a qu'un seul detenteur dans ce depot — {@code PorteeDuCatalogue},
 * et {@code RegleDeVisibiliteUniqueTest} refuse qu'il y en ait un second.
 *
 * <p>Laisser order-service decider de la visibilite aurait duplique la regle :
 * un article reserve aux professionnels finirait achetable par un particulier
 * le jour ou l'une des deux copies oublierait une condition. order-service
 * transmet donc ce qu'il sait de l'acheteur, et ne conclut rien.
 *
 * <h2>Pourquoi un lot, et non un appel par ligne</h2>
 *
 * Un panier se reserve en TOUT ou RIEN. Un appel par ligne laisserait les deux
 * premieres reservees quand la troisieme manque, et il faudrait les rendre une
 * par une — avec le risque qu'un echec en cours de route laisse du stock
 * retenu pour une commande qui n'existera jamais.
 *
 * @param authUserId   l'acheteur, pour la portee
 * @param pays         son pays, lu chez user-service par order-service
 * @param boutiqueId   sa boutique s'il en a une, pour qu'un vendeur puisse
 *                     voir ses propres articles non encore approuves
 * @param professionnel s'il est professionnel : il voit les articles reserves
 *                      aux pro et ceux des autres pays
 * @param lignes       les articles a reserver, en tout ou rien
 */
public record ReservationStockRequest(
        @NotNull String authUserId,
        @NotNull @Size(min = 2, max = 2) String pays,
        UUID boutiqueId,
        boolean professionnel,
        @NotEmpty @Size(max = 50) @Valid List<Ligne> lignes) {

    /**
     * @param produitId  le produit, pour le controle de visibilite
     * @param varianteId la declinaison, qui porte le stock
     * @param quantite   bornee a 100 : au-dela c'est une commande de gros, qui
     *                   se traite autrement
     */
    public record Ligne(
            @NotNull UUID produitId,
            @NotNull UUID varianteId,
            @Positive @jakarta.validation.constraints.Max(100) int quantite) {
    }
}
