package com.abdatytch.interface_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * DTO pour la vérification de téléphone avec code OTP
 */
@Schema(description = "Requête de vérification de téléphone avec code OTP")
public class VerifyPhoneRequest {

    @Schema(description = "Numéro de téléphone de l'utilisateur", example = "+223123456783")
    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Format de téléphone invalide (format international requis)")
    private String phoneNumber;

    @Schema(description = "Code de vérification OTP (6 chiffres)", example = "123456")
    @NotBlank(message = "Le code de vérification est obligatoire")
    @Pattern(regexp = "^\\d{6}$", message = "Le code doit être composé de 6 chiffres")
    private String otpCode;

    // Constructeurs
    public VerifyPhoneRequest() {}

    public VerifyPhoneRequest(String phoneNumber, String otpCode) {
        this.phoneNumber = phoneNumber;
        this.otpCode = otpCode;
    }

    // Getters
    public String getPhoneNumber() { return phoneNumber; }
    public String getOtpCode() { return otpCode; }

    // Setters
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public void setOtpCode(String otpCode) { this.otpCode = otpCode; }
}
