package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.constants.AppConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Etat de l'alerte de nouvelle connexion.
 *
 * <h2>Pourquoi `adresseVerifiee` figure dans la réponse</h2>
 *
 * C'est la condition d'activation. Sans elle, une application qui reçoit
 * « inactif » ne sait pas si la personne peut l'activer ou si le réglage lui est
 * fermé — et affiche donc un interrupteur qui échouera en `VAL_6008` au premier
 * clic, sans expliquer pourquoi.
 *
 * <p>Avec elle, l'écran peut dire la seule chose utile : « confirmez votre
 * adresse pour activer cette alerte ».
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Etat de l'alerte de nouvelle connexion, et sa condition")
public class AlerteDeConnexionResponse {

    @Schema(description = "Vrai si chaque connexion declenche un courriel")
    private Boolean actif;

    /**
     * Condition d'activation.
     *
     * <p>Une alerte par courriel sur un compte sans adresse vérifiée n'aurait
     * nulle part où aller, et une adresse non prouvée n'appartient pas
     * forcément au titulaire.
     */
    @Schema(description = "Vrai si l'adresse est confirmee, donc si l'alerte "
            + "peut etre activee")
    private Boolean adresseVerifiee;

    @Schema(description = "Ce que signifie l'etat courant")
    private String message;

    public static AlerteDeConnexionResponse de(boolean actif, boolean adresseVerifiee) {
        return AlerteDeConnexionResponse.builder()
                .actif(actif)
                .adresseVerifiee(adresseVerifiee)
                .message(actif
                        ? AppConstants.ALERTE_DE_CONNEXION_ACTIVEE
                        : AppConstants.ALERTE_DE_CONNEXION_DESACTIVEE)
                .build();
    }
}
