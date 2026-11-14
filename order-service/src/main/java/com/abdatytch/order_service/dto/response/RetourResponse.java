package com.abdatytch.order_service.dto.response;

import com.abdatytch.order_service.enums.MotifRetour;
import com.abdatytch.order_service.enums.StatutRetour;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un retour, avec son arbitrage.
 *
 * <p>{@code decidePar} n'est rendu qu'a l'administration : l'acheteur a besoin
 * de savoir que Sankadi a tranche et pourquoi, pas QUI chez Sankadi. Nommer
 * l'agent expose une personne a la pression d'un vendeur mecontent.
 */
public record RetourResponse(
        UUID id,
        UUID commandeId,
        String numeroDeCommande,
        MotifRetour motif,
        String commentaire,
        StatutRetour statut,
        String decidePar,
        LocalDateTime decideLe,
        String motifDecision,
        LocalDateTime recuLe,
        Long montantRembourse,
        LocalDateTime rembourseLe,
        LocalDateTime demandeLe) {
}
