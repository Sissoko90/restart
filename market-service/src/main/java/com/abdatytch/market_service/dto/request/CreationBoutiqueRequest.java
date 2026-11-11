package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * La demande d'ouverture d'une boutique.
 *
 * <h2>Ce qui n'y figure PAS : le pays</h2>
 *
 * Il est lu chez user-service. C'est lui qui decide de la visibilite des
 * produits : le laisser saisir permettrait a quiconque d'elargir sa portee en
 * se declarant d'ailleurs.
 *
 * <h2>Ni le statut</h2>
 *
 * Une boutique nait EN_ATTENTE, et seule la moderation en change. L'accepter
 * ici ferait d'un champ de formulaire une approbation.
 */
public record CreationBoutiqueRequest(
        @NotBlank(message = "Le nom de la boutique est requis")
        @Size(max = 160, message = "Le nom ne peut depasser 160 caracteres")
        String nom,

        @Size(max = 4000, message = "La description ne peut depasser 4000 caracteres")
        String description,

        @NotBlank(message = "Le numero RCCM est requis")
        @Size(max = 64, message = "Le RCCM ne peut depasser 64 caracteres")
        String rccm,

        @NotBlank(message = "Le numero NIF est requis")
        @Size(max = 64, message = "Le NIF ne peut depasser 64 caracteres")
        String nif,

        @Size(max = 120) String ville,
        @Size(max = 500) String adresse,
        @Size(max = 32) String telephone) {
}
