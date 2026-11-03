package com.abdatytch.auth_service.mappers;

import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import com.abdatytch.auth_service.dto.response.UserResponseDTO;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Mapper pour construire les OAuth2AuthResponse
 * Centralise la logique de construction des réponses OAuth2
 */
@Component
@RequiredArgsConstructor
public class OAuth2ResponseMapper {
    
    private final UserMapper userMapper;
    
    /**
     * Construit une OAuth2AuthResponse complète (profil complet)
     * @param accessToken Token JWT d'accès
     * @param refreshToken Token de rafraîchissement
     * @param user Utilisateur authentifié
     * @param message Message de succès
     * @return OAuth2AuthResponse
     */
    public OAuth2AuthResponse toCompleteProfileResponse(String accessToken, RefreshToken refreshToken, 
                                                          User user, String message) {
        return OAuth2AuthResponse.builder()
                .accessToken(accessToken)
                // Le SECRET, pas l'empreinte : voir AuthResponseMapper.
                .refreshToken(refreshToken.valeurAEnvoyerAuClient())
                .expiresIn(3600L)
                .tokenType("Bearer")
                .user(userMapper.toResponseDTO(user))
                .profileComplete(true)
                .message(message)
                .build();
    }
    
    /**
     * Construit une OAuth2AuthResponse pour profil incomplet
     * @param tempToken Token temporaire pour complétion
     * @param missingFields Champs manquants
     * @param user Utilisateur avec profil incomplet
     * @param message Message d'information
     * @return OAuth2AuthResponse
     */
    public OAuth2AuthResponse toIncompleteProfileResponse(String tempToken, String[] missingFields, 
                                                            User user, String message) {
        return OAuth2AuthResponse.builder()
                .profileComplete(false)
                .tempToken(tempToken)
                .missingFields(missingFields)
                .user(userMapper.toResponseDTO(user))
                .message(message)
                .build();
    }
}
