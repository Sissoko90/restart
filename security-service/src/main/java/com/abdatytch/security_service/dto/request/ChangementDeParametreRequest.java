package com.abdatytch.security_service.dto.request;

import com.abdatytch.security_service.constant.SecurityConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nouvelle valeur d'un parametre de securite.
 *
 * <h2>Pourquoi ni la cle ni l'auteur ne sont ici</h2>
 *
 * La cle est dans le chemin : elle designe la ressource, et la mettre aussi dans
 * le corps permettrait de modifier un parametre different de celui que l'URL
 * annonce — un desaccord dont aucun journal ne rendrait compte.
 *
 * <p>L'auteur vient du jeton. Dans le corps, un administrateur pourrait signer
 * ses actes du nom d'un autre, et le journal d'audit deviendrait une source de
 * desinformation.
 *
 * <h2>Pourquoi la valeur est une chaine</h2>
 *
 * Le type reel est porte par le catalogue, qui valide et convertit. Un champ par
 * type aurait demande au client de savoir lequel remplir, donc de porter une
 * copie du catalogue.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Nouvelle valeur d'un parametre. Le type est celui du catalogue.")
public class ChangementDeParametreRequest {

    @NotBlank(message = SecurityConstants.VALEUR_REQUISE)
    @Size(max = 500, message = SecurityConstants.VALEUR_TROP_LONGUE)
    @Schema(description = "Valeur, validee contre le type et les bornes du parametre",
            example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
    private String valeur;
}
