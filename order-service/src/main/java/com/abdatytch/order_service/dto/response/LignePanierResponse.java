package com.abdatytch.order_service.dto.response;

import java.util.UUID;

/**
 * Une ligne de panier, enrichie de ce que market-service en dit aujourd'hui.
 *
 * @param disponible faux si l'article n'est plus visible pour cet acheteur,
 *                   plus actif, ou en stock insuffisant pour la quantite
 *                   demandee
 * @param motif      pourquoi il ne l'est pas, en clair. Une ligne barree sans
 *                   motif fait recommencer l'acheteur jusqu'a ce qu'il
 *                   abandonne
 */
public record LignePanierResponse(
        UUID ligneId,
        UUID produitId,
        UUID varianteId,
        UUID boutiqueId,
        String nomProduit,
        String couleur,
        String taille,
        Long prixUnitaire,
        String devise,
        int quantite,
        Long montantLigne,
        boolean disponible,
        String motif) {
}
