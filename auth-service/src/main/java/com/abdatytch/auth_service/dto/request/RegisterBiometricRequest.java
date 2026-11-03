package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.enums.BiometricType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request pour enregistrer des credentials biométriques (WebAuthn)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterBiometricRequest {
    
    @NotNull(message = "Le type biométrique est requis")
    private BiometricType biometricType;
    
    @NotBlank(message = "L'ID du credential est requis")
    private String credentialId;
    
    @NotBlank(message = "La clé publique est requise")
    private String publicKey;
    
    private String deviceId;
    
    private String deviceName;
    
    private String deviceOs;
    
    private String aaguid;
    
    private String attestationFormat;
    
    @NotBlank(message = "La réponse d'attestation est requise")
    private String attestationObject;
    
    @NotBlank(message = "Les données client sont requises")
    private String clientDataJSON;
}
