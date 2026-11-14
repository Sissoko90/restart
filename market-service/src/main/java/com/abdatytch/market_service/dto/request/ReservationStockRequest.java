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
 * <h2>L'APPELANT NE TRANSMET QU'UN IDENTIFIANT</h2>
 *
 * Ni le pays, ni la boutique, ni la qualite de professionnel : market-service
 * les RESOUT lui-meme, par {@code ResolveurDePortee.pourLeCompte}.
 *
 * <p>C'est volontaire, et c'est une question de securite avant d'etre une
 * question de propriete. Si order-service annoncait la portee, il suffirait
 * d'un appel forge — ou d'un defaut dans order-service — pour se declarer
 * professionnel et acheter un article qui ne vous est pas destine.
 *
 * <p>La boutique d'un compte et sa qualite de professionnel vivent ici. La
 * regle de visibilite aussi : {@code PorteeDuCatalogue} en est le seul
 * detenteur, et {@code RegleDeVisibiliteUniqueTest} refuse qu'il y en ait un
 * second.
 *
 * <h2>Pourquoi un lot, et non un appel par ligne</h2>
 *
 * Un panier se reserve en TOUT ou RIEN. Un appel par ligne laisserait les deux
 * premieres reservees quand la troisieme manque, et il faudrait les rendre une
 * par une — avec le risque qu'un echec en cours de route laisse du stock
 * retenu pour une commande qui n'existera jamais.
 *
 * @param authUserId l'acheteur. Sa portee est resolue ici
 * @param lignes     les articles a reserver, en tout ou rien
 */
public record ReservationStockRequest(
        @NotNull String authUserId,
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
