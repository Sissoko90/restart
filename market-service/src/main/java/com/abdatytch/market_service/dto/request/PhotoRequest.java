package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Le rattachement d'une photo deposee chez media-service.
 *
 * <p>{@code principale} : une seule par produit, garantie par un index unique
 * PARTIEL en base. L'appelant n'a pas a retirer l'ancienne — le service le fait
 * avant de poser la nouvelle, sinon la contrainte refuserait et le message
 * parlerait d'un index au lieu de l'intention.
 */
public record PhotoRequest(
        @NotBlank(message = "L'identifiant du document est requis")
        @Size(max = 64) String documentId,

        @PositiveOrZero Integer position,
        boolean principale) {
}
