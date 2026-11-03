package com.abdatytch.auth_service.service;

import reactor.core.publisher.Mono;

/**
 * Service réactif de gestion de la blacklist des tokens JWT révoqués
 * Utilise Redis pour stocker les tokens révoqués avec TTL automatique
 */
public interface ITokenBlacklistService {
    
    /**
     * Révoque un token JWT en l'ajoutant à la blacklist
     * @param tokenId ID unique du token (jti claim)
     * @param expirationMs Durée de vie restante du token en millisecondes
     * @return Mono<Void>
     */
    Mono<Void> revokeToken(String tokenId, long expirationMs);
    
    /**
     * Vérifie si un token est révoqué
     * @param tokenId ID unique du token (jti claim)
     * @return Mono<Boolean> true si le token est révoqué, false sinon
     */
    Mono<Boolean> isTokenRevoked(String tokenId);
    
    /**
     * Révoque tous les tokens d'un utilisateur
     * @param userId ID de l'utilisateur
     * @return Mono<Void>
     */
    Mono<Void> revokeAllUserTokens(String userId);
    
    /**
     * Vérifie si un utilisateur a des tokens révoqués
     * @param userId ID de l'utilisateur
     * @return Mono<Boolean> true si l'utilisateur a des tokens révoqués
     */
    Mono<Boolean> hasRevokedTokens(String userId);
}
