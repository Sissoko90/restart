package com.abdatytch.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request pour authentification biométrique (WebAuthn)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricAuthRequest {
    
    @NotBlank(message = "L'ID du credential est requis")
    private String credentialId;
    
    @NotBlank(message = "La signature est requise")
    private String signature;
    
    @NotBlank(message = "Les données d'authentification sont requises")
    private String authenticatorData;
    
    @NotBlank(message = "Les données client sont requises")
    private String clientDataJSON;
    
    private String userHandle;
}
