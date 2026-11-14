package com.abdatytch.market_service.dto.response;

import java.util.UUID;

/**
 * Une ligne reservee, avec l'INSTANTANE de ce qui a ete reserve.
 *
 * <h2>Pourquoi la reponse porte le prix et le nom</h2>
 *
 * order-service a besoin de ces valeurs pour figer sa ligne de commande. Les
 * lire par un second appel laisserait une fenetre : entre la reservation et la
 * lecture, la boutique peut changer le prix, et la commande porterait un
 * montant different de celui qui a ete reserve.
 *
 * <p>Un seul aller-retour, qui reserve ET rend ce qu'il a reserve. C'est la
 * seule facon d'avoir un prix dont on sait qu'il correspond au stock pris.
 *
 * @param boutiqueId la boutique, que order-service ne connait pas autrement et
 *                   dont il a besoin pour decouper le panier en une commande
 *                   par boutique
 * @param devise     portee par le produit, et non devinee : deux boutiques de
 *                   pays differents ne vendent pas dans la meme monnaie
 */
public record LigneReserveeResponse(
        UUID produitId,
        UUID varianteId,
        UUID boutiqueId,
        String pays,
        String nomProduit,
        String couleur,
        String taille,
        String sku,
        long prixUnitaire,
        String devise,
        int quantite) {
}
