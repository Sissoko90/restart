package com.abdatytch.auth_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Acquittement d'un accuse de reception SMS.
 *
 * <h2>Ce que ce « vrai » signifie, et ce qu'il ne signifie pas</h2>
 *
 * Il atteste que l'accuse a ete <b>reçu et authentifie</b>, rien de plus. Il
 * est rendu meme quand l'accuse est inexploitable — identifiant inconnu,
 * registre purge, Redis indisponible — parce qu'un fournisseur qui reçoit une
 * erreur reessaie indefiniment pour une information deja perdue.
 *
 * <p>Il ne faut donc pas le lire comme « statut de livraison enregistre ».
 * C'est la raison pour laquelle le champ s'appelle {@code recu} et non
 * {@code traite}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Acquittement d'un accuse de reception SMS")
public class AccuseRecuResponse {

    @Schema(description = "Vrai : l'accuse a ete reçu et authentifie")
    private boolean recu;

    public static AccuseRecuResponse acquitte() {
        return AccuseRecuResponse.builder().recu(true).build();
    }
}
