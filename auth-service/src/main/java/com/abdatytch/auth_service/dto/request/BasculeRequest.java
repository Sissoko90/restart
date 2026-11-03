package com.abdatytch.auth_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Activation ou désactivation d'un réglage.
 *
 * <h2>Pourquoi un corps, et non deux endpoints</h2>
 *
 * `POST /reglage` pour activer et `DELETE /reglage` pour désactiver décrirait
 * mal l'opération : rien n'est créé ni supprimé, un booléen change d'état.
 * `PUT` avec la valeur voulue est idempotent — rejouer la requête laisse le même
 * état — là où une bascule sans valeur (« inverse le réglage ») ne l'est pas :
 * un client qui réessaie après un délai d'attente inverserait deux fois.
 *
 * <h2>Pourquoi `actif` est obligatoire</h2>
 *
 * Un `Boolean` absent valant « faux » ferait d'une requête incomplète une
 * désactivation silencieuse d'une protection. {@code @NotNull} force le client à
 * dire ce qu'il veut.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Etat voulu pour un reglage")
public class BasculeRequest {

    /**
     * Etat voulu.
     *
     * <p>Obligatoire : un champ absent interprété comme « faux » transformerait
     * une requête mal formée en désactivation d'une protection.
     */
    @NotNull(message = "Précisez l'état voulu : true pour activer, false pour désactiver")
    @Schema(description = "true pour activer, false pour desactiver",
            example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean actif;
}
