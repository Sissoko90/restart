package com.abdatytch.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request pour vérifier une authentification biométrique
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricVerificationRequest {
    
    @NotBlank(message = "Le credentialId est obligatoire")
    private String credentialId;
    
    @NotBlank(message = "L'authenticatorData est obligatoire")
    private String authenticatorData;
    
    @NotBlank(message = "Le clientDataJSON est obligatoire")
    private String clientDataJSON;
    
    @NotBlank(message = "La signature est obligatoire")
    private String signature;
    
    @NotBlank(message = "Le challenge est obligatoire")
    private String challenge;
    
    private String userHandle;
}
