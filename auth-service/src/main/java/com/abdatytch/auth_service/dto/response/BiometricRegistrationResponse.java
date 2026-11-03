package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.enums.BiometricType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response pour l'enregistrement biométrique
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricRegistrationResponse {
    
    private String credentialId;
    
    private BiometricType biometricType;
    
    private String challenge;
    
    private String deviceName;
    
    private LocalDateTime registeredAt;
    
    private String message;
    
    private Boolean success;
}
