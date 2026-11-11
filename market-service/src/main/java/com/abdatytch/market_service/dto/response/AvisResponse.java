package com.abdatytch.market_service.dto.response;

import com.abdatytch.market_service.enums.StatutModeration;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un avis.
 *
 * <h2>{@code auteur} est un LIBELLE, jamais l'identifiant</h2>
 *
 * Rendre le {@code sub} du porteur relierait chaque avis au compte qui l'a
 * ecrit, sur une page publique. Le libelle est tronque et stable : « cl…f3 ».
 */
public record AvisResponse(
        UUID id,
        UUID produitId,
        String auteur,
        Short note,
        String commentaire,
        StatutModeration statutModeration,
        String motifDecision,
        LocalDateTime createdAt) {
}
