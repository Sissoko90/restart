package com.abdatytch.market_service.dto.response;

import com.abdatytch.market_service.enums.StatutBoutique;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une boutique, telle que son proprietaire ou un moderateur la voit.
 *
 * <h2>{@code motifDecision} est RENDU</h2>
 *
 * C'est l'exception a la regle « aucun message interne ne franchit la
 * frontiere », et elle est voulue : un refus dont on ignore la cause se
 * represente a l'identique.
 *
 * <h2>Ce qui n'est PAS rendu dans la vitrine publique</h2>
 *
 * {@link #publique(BoutiqueResponse)} efface le RCCM, le NIF et le motif. Un
 * acheteur n'a pas a lire le dossier administratif d'un commercant.
 */
public record BoutiqueResponse(
        UUID id,
        String nom,
        String description,
        String rccm,
        String nif,
        String pays,
        String ville,
        String adresse,
        String telephone,
        String logoDocumentId,
        String couvertureDocumentId,
        StatutBoutique statut,
        String motifDecision,
        LocalDateTime createdAt) {

    /** La meme boutique, sans son dossier administratif. */
    public static BoutiqueResponse publique(BoutiqueResponse complete) {
        return new BoutiqueResponse(complete.id(), complete.nom(), complete.description(),
                null, null, complete.pays(), complete.ville(), complete.adresse(),
                complete.telephone(), complete.logoDocumentId(),
                complete.couvertureDocumentId(), complete.statut(), null,
                complete.createdAt());
    }
}
