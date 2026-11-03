package com.abdatytch.interface_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO pour la connexion utilisateur
 * Accepte email ou numéro de téléphone (priorité à l'email)
 */
@Schema(description = "Requête de connexion utilisateur")
public class LoginRequest {

    @Schema(description = "Email ou numéro de téléphone de l'utilisateur (priorité à l'email)", example = "dev@example.com ou +22300000000")
    @NotBlank(message = "L'identifiant est obligatoire")
    private String identifier;

    @Schema(description = "Mot de passe de l'utilisateur", example = "SecurePass123!")
    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 8, max = 100, message = "Le mot de passe doit contenir entre 8 et 100 caractères")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$",
             message = "Le mot de passe doit contenir au moins une minuscule, une majuscule, un chiffre et un caractère spécial")
    private String password;

    @Schema(description = "Se souvenir de moi (session prolongée)", example = "false")
    private Boolean rememberMe = false;

    // Constructeurs
    public LoginRequest() {}

    public LoginRequest(String identifier, String password, Boolean rememberMe) {
        this.identifier = identifier;
        this.password = password;
        this.rememberMe = rememberMe;
    }

    // Getters
    public String getIdentifier() { return identifier; }
    public String getPassword() { return password; }
    public Boolean getRememberMe() { return rememberMe; }

    // Setters
    public void setIdentifier(String identifier) { this.identifier = identifier; }
    public void setPassword(String password) { this.password = password; }
    public void setRememberMe(Boolean rememberMe) { this.rememberMe = rememberMe; }

    // Méthodes utilitaires pour détecter le type d'identifiant
    public boolean isEmail() {
        return identifier != null && identifier.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

    public boolean isPhoneNumber() {
        return identifier != null && identifier.matches("^\\+[1-9]\\d{1,14}$");
    }
}
