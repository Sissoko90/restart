package com.abdatytch.auth_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Connexion d'un compte d'administration de la plateforme.
 *
 * <p>L'identifiant accepte une adresse ou un telephone : un administrateur
 * retient plus facilement son adresse, et le compte seme au deploiement porte les
 * deux.
 */
@Data
@Schema(description = "Connexion administrateur : identifiant, mot de passe et code TOTP")
public class AdminLoginRequest {

    @NotBlank(message = "L'identifiant est requis")
    @Schema(description = "Adresse electronique ou numero de telephone",
            example = "admin@example.com")
    private String identifier;

    @NotBlank(message = "Le mot de passe est requis")
    @Schema(description = "Mot de passe")
    private String password;

    /**
     * Code a six chiffres. Obligatoire des lors que le second facteur est enrole ;
     * absent lors de la premiere connexion, qui sert precisement a l'enroler.
     */
    @Schema(description = "Code TOTP a six chiffres", example = "123456")
    private String totpCode;
}
