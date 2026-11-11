package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.abdatytch.market_service.enums.TypeDocumentBoutique;
import jakarta.validation.constraints.NotNull;

/**
 * Le rattachement d'une piece au dossier d'une boutique.
 *
 * <p>{@code documentId} est l'identifiant rendu par media-service apres un
 * depot. Aucun binaire ne transite par ce service.
 */
public record PieceJustificativeRequest(
        @NotNull(message = "Le type de piece est requis")
        TypeDocumentBoutique type,

        @NotBlank(message = "L'identifiant du document est requis")
        @Size(max = 64)
        String documentId) {
}
