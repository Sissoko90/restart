package com.abdatytch.order_service.dto.response;

import java.util.UUID;

/**
 * Une ligne de commande, telle qu'elle a ete figee a l'achat.
 *
 * <p>Ces valeurs ne changent jamais. La boutique peut renommer son article ou
 * en changer le prix : la commande continue de dire ce qui a ete achete et a
 * quel prix.
 */
public record LigneCommandeResponse(
        UUID produitId,
        UUID varianteId,
        String nomProduit,
        String couleur,
        String taille,
        String sku,
        long prixUnitaire,
        int quantite,
        long montantLigne) {
}
