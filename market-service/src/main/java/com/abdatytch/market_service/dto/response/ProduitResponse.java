package com.abdatytch.market_service.dto.response;

import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.enums.Visibilite;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Un produit, avec ce qui l'accompagne.
 *
 * <h2>{@code photos} porte des URL SIGNEES, pas des identifiants</h2>
 *
 * media-service les signe pour quinze minutes, et le navigateur telecharge
 * ensuite directement depuis MinIO. Rendre l'identifiant brut obligerait le
 * client a un second appel pour chaque image.
 *
 * <p>La liste peut etre plus COURTE que le nombre de photos rattachees : un
 * document dont le type n'est pas signable, ou tombe dans le repli local
 * chiffre, en est absent. Mieux vaut une photo manquante qu'un lien mort.
 */
public record ProduitResponse(
        UUID id,
        UUID boutiqueId,
        String boutiqueNom,
        UUID categorieId,
        String nom,
        String description,
        Long prix,
        String devise,
        String pays,
        Visibilite visibilite,
        StatutModeration statutModeration,
        String motifDecision,
        Boolean actif,
        BigDecimal noteMoyenne,
        Integer nombreAvis,
        List<String> photos,
        List<VarianteResponse> variantes,
        List<CaracteristiqueResponse> caracteristiques,
        LocalDateTime createdAt) {
}
