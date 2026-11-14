package com.abdatytch.order_service.dto.request;

import jakarta.validation.constraints.Size;

/**
 * L'arbitrage de Sankadi sur un retour.
 *
 * <p>{@code motifDecision} est exige en cas de refus — par la contrainte de
 * base {@code retour_refus_motive} et par le service. Refuser sans dire
 * pourquoi fait revenir l'acheteur, et Sankadi traite deux fois le meme
 * dossier.
 *
 * @param accepte vrai pour accepter, faux pour refuser. Un booleen et non un
 *                statut libre : les seules decisions possibles a ce stade sont
 *                ces deux-la, et un statut libre inviterait a sauter des etapes
 *                (rembourser sans avoir recu, par exemple)
 */
public record DecisionDeRetourRequest(
        boolean accepte,
        @Size(max = 2000) String motifDecision) {
}
