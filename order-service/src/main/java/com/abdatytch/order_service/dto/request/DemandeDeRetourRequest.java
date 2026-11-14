package com.abdatytch.order_service.dto.request;

import com.abdatytch.order_service.enums.MotifRetour;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * L'acheteur demande un retour.
 *
 * <p>Le motif est une valeur d'une liste fermee : un champ libre ne se compte
 * pas, et Sankadi doit pouvoir voir qu'une boutique accumule les
 * {@code NON_CONFORME}. Le commentaire libre existe en plus, pour les details.
 */
public record DemandeDeRetourRequest(
        @NotNull MotifRetour motif,
        @Size(max = 2000) String commentaire) {
}
