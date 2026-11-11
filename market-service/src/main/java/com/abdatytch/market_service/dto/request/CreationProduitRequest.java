package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import com.abdatytch.market_service.enums.Visibilite;

import java.util.UUID;

/**
 * La creation ou la mise a jour d'un produit.
 *
 * <h2>Ni le pays, ni le statut de moderation</h2>
 *
 * Le pays vient de la boutique. Le statut vient de la moderation : un produit
 * cree ou modifie repart EN_ATTENTE, faute de quoi il suffirait de publier un
 * article anodin puis de le remplacer.
 *
 * <h2>Le prix est un ENTIER</h2>
 *
 * En plus petite unite monetaire. L'arithmetique decimale approchee sur de
 * l'argent finit toujours par couter un centime a quelqu'un.
 */
public record CreationProduitRequest(
        @NotBlank(message = "Le nom du produit est requis")
        @Size(max = 200) String nom,

        @Size(max = 20000) String description,

        @NotNull(message = "Le prix est requis")
        @PositiveOrZero(message = "Le prix ne peut etre negatif")
        Long prix,

        @Size(min = 3, max = 3, message = "La devise suit la norme ISO 4217")
        String devise,

        UUID categorieId,

        @NotNull(message = "La visibilite est requise")
        Visibilite visibilite) {
}
