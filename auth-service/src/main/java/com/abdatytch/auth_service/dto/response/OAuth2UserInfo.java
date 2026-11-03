package com.abdatytch.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO pour stocker les informations utilisateur récupérées depuis les providers OAuth2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuth2UserInfo {
    
    private String providerId; // ID unique chez le provider (sub pour Google, id pour Facebook)
    private String email;
    private String name;
    private String firstName;
    private String lastName;
    private String pictureUrl;
    private Boolean emailVerified;
    
    // Informations du token
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
}
