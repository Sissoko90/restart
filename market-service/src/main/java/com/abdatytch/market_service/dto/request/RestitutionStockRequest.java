package com.abdatytch.market_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Une restitution de stock : commande impayee annulee, ou retour recu.
 *
 * <h2>AUCUNE PORTEE ICI, et ce n'est pas un oubli</h2>
 *
 * Rendre du stock n'expose rien : la valeur remonte, personne n'apprend quoi
 * que ce soit sur un article qu'il ne pouvait pas voir. Exiger une portee
 * ferait pire — un acheteur qui perd sa qualite de professionnel entre sa
 * commande et son retour ne pourrait plus rendre, et le stock serait
 * definitivement perdu pour la boutique.
 *
 * <p>Le controle qui compte est ailleurs : ce chemin est sous
 * {@code /internal/**}, donc reserve au reseau interne et au secret partage.
 */
public record RestitutionStockRequest(
        @NotEmpty @Size(max = 50) @Valid List<Ligne> lignes) {

    public record Ligne(
            @NotNull UUID varianteId,
            @Positive @jakarta.validation.constraints.Max(100) int quantite) {
    }
}
