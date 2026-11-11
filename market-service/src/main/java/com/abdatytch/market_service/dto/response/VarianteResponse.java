package com.abdatytch.market_service.dto.response;

import java.util.UUID;

/** Une declinaison rendue a l'appelant. */
public record VarianteResponse(
        UUID id, String sku, String couleur, String taille,
        Long prix, Integer stock, Boolean actif) {
}
