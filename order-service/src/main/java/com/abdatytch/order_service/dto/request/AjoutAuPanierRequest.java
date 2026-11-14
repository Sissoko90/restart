package com.abdatytch.order_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Ajouter un article au panier.
 *
 * <p>Le plafond de quantite est {@code order.panier.quantite-maximum-par-ligne},
 * applique par le service et non ici : il est configurable, et une annotation
 * porte une constante de compilation. L'annotation garde la borne absolue qui
 * ne depend d'aucun reglage.
 */
public record AjoutAuPanierRequest(
        @NotNull UUID produitId,
        @NotNull UUID varianteId,
        @Positive @jakarta.validation.constraints.Max(1000) int quantite) {
}
