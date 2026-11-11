package com.abdatytch.market_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * La decision d'un moderateur.
 *
 * <h2>Le motif est OBLIGATOIRE au refus</h2>
 *
 * Verifie par le service et non par une annotation : la regle depend du sens
 * de la decision, et {@code @NotBlank} sur un champ facultatif a l'approbation
 * refuserait aussi celle-ci.
 *
 * <p>Un refus dont on ignore la cause se represente a l'identique, et fait deux
 * fois le travail de l'equipe de moderation.
 */
public record DecisionRequest(
        @NotNull(message = "Le sens de la decision est requis")
        Sens sens,

        @Size(max = 2000) String motif) {

    /** Ce qu'un moderateur peut decider. */
    public enum Sens {
        APPROUVER,
        REJETER,
        /** Ne vaut que pour une boutique deja approuvee. */
        SUSPENDRE
    }

    public boolean exigeUnMotif() {
        return sens == Sens.REJETER || sens == Sens.SUSPENDRE;
    }
}
