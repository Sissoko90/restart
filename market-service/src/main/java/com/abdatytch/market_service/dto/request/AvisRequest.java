package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * La note et le commentaire d'un client.
 *
 * <p>Un seul avis par personne et par produit : la base le garantit. Un
 * deuxieme envoi MODIFIE le premier au lieu d'echouer sur une contrainte dont
 * le message ne dirait rien a personne.
 */
public record AvisRequest(
        @NotNull(message = "La note est requise")
        @Min(value = 1, message = "La note va de 1 a 5")
        @Max(value = 5, message = "La note va de 1 a 5")
        Short note,

        @Size(max = 4000, message = "Le commentaire ne peut depasser 4000 caracteres")
        String commentaire) {
}
