package com.abdatytch.auth_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Reinitialisation du second facteur par l'administrateur lui-meme.
 *
 * <h2>Pourquoi le code courant est exige</h2>
 *
 * Reinitialiser le second facteur, c'est le <b>remplacer</b>. Sans le code
 * courant, un mot de passe seul suffirait a substituer son propre appareil a
 * celui de l'administrateur : le second facteur ne protegerait plus rien,
 * puisqu'il serait contournable par le premier.
 *
 * <p>C'est la difference avec l'enrolement : a l'enrolement il n'y a pas encore
 * de second facteur a opposer, donc le mot de passe est le seul controle
 * disponible. Des qu'il y en a un, il doit etre presente.
 *
 * <h2>Et si l'appareil est perdu</h2>
 *
 * Alors ce chemin est ferme, par construction. La sortie est
 * {@code POST /admin/accounts/{identifier}/totp/reset}, reserve au
 * {@code SUPER_ADMIN}, qui retire le second facteur sans jamais lire le secret.
 * L'administrateur le re-enrole ensuite lui-meme.
 */
@Data
@Schema(description = "Reinitialisation du second facteur : identifiant, mot de "
        + "passe et code courant")
public class TotpResetRequest {

    @NotBlank(message = "L'identifiant est requis")
    @Schema(description = "Adresse electronique ou numero de telephone",
            example = "admin@example.com")
    private String identifier;

    @NotBlank(message = "Le mot de passe est requis")
    @Schema(description = "Mot de passe du compte")
    private String password;

    /**
     * Code produit par l'appareil <b>actuellement</b> enrole.
     *
     * <p>Non annote {@code @NotBlank} a dessein : la validation de format ne
     * sait pas si le compte a un second facteur actif. Le controle est fait dans
     * la couche service, qui le sait — et qui refuse l'operation avec
     * {@code 2FA_9001} quand il n'y a rien a reinitialiser.
     */
    @Schema(description = "Code TOTP a six chiffres de l'appareil actuel",
            example = "123456")
    private String totpCode;
}
