package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Un attribut libre : « marque », « matiere », « garantie ». */
public record CaracteristiqueRequest(
        @NotBlank(message = "La clef de la caracteristique est requise")
        @Size(max = 80) String cle,

        @NotBlank(message = "La valeur est requise")
        @Size(max = 400) String valeur) {
}
