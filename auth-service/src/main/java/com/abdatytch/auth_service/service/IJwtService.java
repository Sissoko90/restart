package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.UserRole;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface du service JWT
 */
public interface IJwtService {
    
    /**
     * Génère un access token. Réactif : la lecture des rôles en base et l'appel
     * de signature à interface-service sont tous deux non bloquants.
     */
    Mono<String> generateToken(User user);

    /**
     * Validation réactive du token JWT (vérifie signature + blacklist)
     * @param token Le token JWT à valider
     * @return Mono<Boolean> true si valide, false sinon
     */
    Mono<Boolean> validateTokenReactive(String token);
    
    String getUserIdFromToken(String token);
    
    String getPhoneFromToken(String token);
    
    UserRole getRoleFromToken(String token);
    
    String getTokenIdFromToken(String token);

    Mono<String> generateTokenWithClaims(Map<String, Object> claims);
    
    Map<String, Object> extractAllClaims(String token);
    
    long getExpirationMs();
    
    void revokeToken(String token);
    
    void revokeAllUserTokens(String userId);
    
    /** Token temporaire de vérification 2FA (5 minutes). */
    Mono<String> generateTempToken(User user);
}
