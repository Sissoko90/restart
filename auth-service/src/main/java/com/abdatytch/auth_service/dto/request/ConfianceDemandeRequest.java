package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Demande de confiance pour l'appareil courant.
 *
 * <h2>Pourquoi un code est exige</h2>
 *
 * Un appareil de confiance dispense du second facteur. L'accorder sans l'avoir
 * exige une fois donnerait a qui detient le mot de passe un moyen de s'en
 * dispenser definitivement : la protection s'annulerait elle-meme.
 *
 * <p>L'appelant est deja authentifie — mais sa session peut avoir ete ouverte
 * depuis un autre appareil de confiance, donc sans code. Le demander ici
 * garantit que la personne detient bien son second facteur a cet instant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Accorde la confiance a l'appareil courant. Le code TOTP est "
        + "exige : un appareil de confiance dispense du second facteur.")
public class ConfianceDemandeRequest {

    @NotBlank(message = AppConstants.CODE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.CODE_NUMERIC_PATTERN,
            message = AppConstants.CODE_PATTERN_MESSAGE)
    @Schema(description = "Code TOTP a six chiffres", example = "123456",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    /**
     * Nom lisible, facultatif.
     *
     * <p>Sans nom, il est deduit de l'User-Agent. Un nom choisi par la personne
     * reste preferable : « Portable du bureau » se reconnait mieux que
     * « Chrome 131 / Windows 10 », surtout quand deux appareils portent le meme
     * navigateur.
     */
    @Size(max = 120, message = AppConstants.APPAREIL_NOM_TROP_LONG)
    @Schema(description = "Nom de l'appareil. Deduit de l'User-Agent si absent.",
            example = "Portable du bureau")
    private String nom;
}
