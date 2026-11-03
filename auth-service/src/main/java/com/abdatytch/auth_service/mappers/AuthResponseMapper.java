package com.abdatytch.auth_service.mappers;

import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.UserResponse;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Mapper pour construire les AuthResponse
 * Centralise la logique de construction des réponses d'authentification
 */
@Component
@RequiredArgsConstructor
public class AuthResponseMapper {
    
    private final UserMapper userMapper;
    
    /**
     * Construit une AuthResponse complète avec tokens et user
     * @param accessToken Token JWT d'accès
     * @param refreshToken Token de rafraîchissement
     * @param expiresInSeconds Durée de validité en secondes
     * @param user Utilisateur authentifié
     * @return AuthResponse
     */
    public AuthResponse toAuthResponse(String accessToken, RefreshToken refreshToken, 
                                        long expiresInSeconds, User user) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                // Le SECRET, pas l'empreinte. getToken() n'existe plus : la base
                // ne garde que l'empreinte, et la renvoyer au client donnerait
                // un jeton que le rafraichissement refuserait — l'empreinte de
                // l'empreinte ne correspond a aucune ligne.
                .refreshToken(refreshToken.valeurAEnvoyerAuClient())
                .tokenType("Bearer")
                .expiresIn(expiresInSeconds)
                .user(userMapper.toResponse(user))
                .build();
    }
    
    /**
     * Construit une AuthResponse avec tokens uniquement (sans user)
     * @param accessToken Token JWT d'accès
     * @param refreshTokenString Token de rafraîchissement (string)
     * @param expiresInSeconds Durée de validité en secondes
     * @param user Utilisateur authentifié
     * @return AuthResponse
     */
    public AuthResponse toAuthResponse(String accessToken, String refreshTokenString, 
                                        long expiresInSeconds, User user) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshTokenString)
                .tokenType("Bearer")
                .expiresIn(expiresInSeconds)
                .user(userMapper.toResponse(user))
                .build();
    }
    
    /**
     * Construit une AuthResponse avec user uniquement (pour getUserProfile)
     * @param user Utilisateur
     * @return AuthResponse
     */
    public AuthResponse toAuthResponseWithUser(User user) {
        return AuthResponse.builder()
                .user(userMapper.toResponse(user))
                .build();
    }
}
