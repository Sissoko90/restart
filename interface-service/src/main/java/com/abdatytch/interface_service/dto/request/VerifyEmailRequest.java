package com.abdatytch.interface_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO pour la vérification d'email avec code OTP
 */
@Schema(description = "Requête de vérification d'email avec code OTP")
public class VerifyEmailRequest {

    @Schema(description = "Email de l'utilisateur", example = "dev@example.com")
    @NotBlank(message = "L'email est obligatoire")
    @Pattern(regexp = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "Format d'email invalide")
    private String email;

    @Schema(description = "Code de vérification OTP (6 chiffres)", example = "123456")
    @NotBlank(message = "Le code de vérification est obligatoire")
    @Pattern(regexp = "^\\d{6}$", message = "Le code doit être composé de 6 chiffres")
    private String otpCode;

    // Constructeurs
    public VerifyEmailRequest() {}

    public VerifyEmailRequest(String email, String otpCode) {
        this.email = email;
        this.otpCode = otpCode;
    }

    // Getters
    public String getEmail() { return email; }
    public String getOtpCode() { return otpCode; }

    // Setters
    public void setEmail(String email) { this.email = email; }
    public void setOtpCode(String otpCode) { this.otpCode = otpCode; }
}
