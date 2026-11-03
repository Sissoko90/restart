package com.abdatytch.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO de réponse pour l'authentification OAuth2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuth2AuthResponse {
    
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    private String tokenType;
    private UserResponseDTO user;
    
    // Indique si le profil est complet ou nécessite une complétion
    private boolean profileComplete;
    
    // Token temporaire pour la complétion de profil (si profileComplete = false)
    private String tempToken;
    
    // Informations manquantes à compléter
    private String[] missingFields;
    
    private String message;
}
