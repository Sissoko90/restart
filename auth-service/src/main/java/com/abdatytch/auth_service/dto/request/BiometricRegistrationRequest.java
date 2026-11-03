package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.enums.BiometricType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request pour finaliser l'enregistrement d'une credential biométrique
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricRegistrationRequest {
    
    @NotNull(message = "Le type biométrique est obligatoire")
    private BiometricType type;
    
    @NotBlank(message = "Le credentialId est obligatoire")
    private String credentialId;
    
    @NotBlank(message = "La publicKey est obligatoire")
    private String publicKey;
    
    @NotBlank(message = "L'attestationObject est obligatoire")
    private String attestationObject;
    
    @NotBlank(message = "Le clientDataJSON est obligatoire")
    private String clientDataJSON;
    
    private String deviceName;
    
    private String challenge;
}
