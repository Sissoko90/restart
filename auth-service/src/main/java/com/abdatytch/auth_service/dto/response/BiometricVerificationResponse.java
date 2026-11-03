package com.abdatytch.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response pour l'authentification biométrique
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricVerificationResponse {
    
    private Boolean success;
    private String message;
    private String challenge;
    private String credentialId;
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
}
