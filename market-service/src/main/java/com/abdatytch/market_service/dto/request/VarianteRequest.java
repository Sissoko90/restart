package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Une declinaison : couleur, taille, prix et stock propres. */
public record VarianteRequest(
        @Size(max = 64) String sku,
        @Size(max = 60) String couleur,
        @Size(max = 40) String taille,

        @NotNull(message = "Le prix de la variante est requis")
        @PositiveOrZero(message = "Le prix ne peut etre negatif")
        Long prix,

        @NotNull(message = "Le stock est requis")
        @PositiveOrZero(message = "Le stock ne peut etre negatif")
        Integer stock) {
}
