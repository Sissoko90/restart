package com.abdatytch.interface_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO pour l'authentification admin avec TOTP
 */
@Schema(description = "Requête d'authentification administrateur avec TOTP")
public class AdminLoginRequest {

    @Schema(description = "Email de l'administrateur", example = "admin@example.com")
    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "Format d'email invalide")
    private String email;

    @Schema(description = "Mot de passe de l'administrateur", example = "AdminPass123!")
    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 8, message = "Le mot de passe doit contenir au moins 8 caractères")
    private String password;

    @Schema(description = "Code TOTP à 6 chiffres", example = "123456")
    @NotBlank(message = "Le code TOTP est obligatoire")
    @Pattern(regexp = "^\\d{6}$", message = "Le code TOTP doit contenir exactement 6 chiffres")
    private String totpCode;

    // Constructeurs
    public AdminLoginRequest() {}

    public AdminLoginRequest(String email, String password, String totpCode) {
        this.email = email;
        this.password = password;
        this.totpCode = totpCode;
    }

    // Getters
    public String getEmail() { return email; }
    public String getPassword() { return password; }
    public String getTotpCode() { return totpCode; }

    // Setters
    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public void setTotpCode(String totpCode) { this.totpCode = totpCode; }
}
