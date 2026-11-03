package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Un code a six chiffres, et rien d'autre.
 *
 * <h2>Pourquoi un seul DTO pour quatre usages</h2>
 *
 * La confirmation du second facteur, sa desactivation, la confirmation de
 * l'adresse et celle du numero ont tous la meme forme : un code a six chiffres.
 * Quatre DTO identiques auraient quadruple le meme ensemble de contraintes, avec
 * la certitude qu'une correction n'en toucherait que l'un — le motif que ce
 * depot a passe la session a retirer.
 *
 * <h2>Ce qui n'y figure pas, et c'est deliberе</h2>
 *
 * Aucun identifiant de compte. Il vient du jeton, par le contexte de securite.
 * Le mettre dans le corps permettrait de confirmer l'adresse ou de desactiver le
 * second facteur <b>d'un autre compte</b> en changeant un champ.
 *
 * <p>Le format est contraint ici et non dans le service : un code a cinq
 * chiffres se refuse a la validation, sans consommer de tentative ni toucher la
 * base.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeRequest {

    @NotBlank(message = AppConstants.CODE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.CODE_NUMERIC_PATTERN,
            message = AppConstants.CODE_PATTERN_MESSAGE)
    private String code;
}
