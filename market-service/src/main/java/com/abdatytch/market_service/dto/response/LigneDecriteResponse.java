package com.abdatytch.market_service.dto.response;

import java.util.UUID;

/**
 * Un article decrit, SANS reservation.
 *
 * <h2>Pourquoi ce pendant en lecture existe</h2>
 *
 * order-service doit afficher un panier : nom, prix, et surtout si l'article
 * est encore achetable. Le reserver pour l'afficher serait absurde — un panier
 * consulte dix fois retiendrait dix fois le stock.
 *
 * <h2>Ce que {@code disponible} vaut, et ce qu'il ne vaut pas</h2>
 *
 * C'est un INSTANTANE indicatif, pas une garantie. Entre cette lecture et le
 * passage en caisse, un autre acheteur peut prendre le dernier article. La
 * seule garantie est la reservation conditionnelle, et c'est pourquoi elle
 * existe separement.
 *
 * <p>Afficher « disponible » puis refuser au paiement n'est donc pas un
 * defaut : c'est la seule reponse honnete sur une place de marche ou plusieurs
 * personnes regardent le meme article.
 *
 * @param motif pourquoi l'article n'est pas disponible, en clair. Une ligne
 *              barree sans motif fait recommencer l'acheteur jusqu'a ce qu'il
 *              abandonne
 */
public record LigneDecriteResponse(
        UUID produitId,
        UUID varianteId,
        UUID boutiqueId,
        String pays,
        String nomProduit,
        String couleur,
        String taille,
        String sku,
        Long prixUnitaire,
        String devise,
        int quantiteDemandee,
        boolean disponible,
        String motif) {
}
