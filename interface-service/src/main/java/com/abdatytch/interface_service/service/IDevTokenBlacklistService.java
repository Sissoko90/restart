package com.abdatytch.interface_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de blacklist des tokens JWT pour les développeurs
 */
public interface IDevTokenBlacklistService {
    
    /**
     * Révoque un token spécifique
     */
    Mono<Void> revokeToken(String tokenId, long expirationMs);
    
    /**
     * Vérifie si un token est révoqué
     */
    Mono<Boolean> isTokenRevoked(String tokenId);
    
    /**
     * Révoque tous les tokens d'un développeur
     */
    Mono<Void> revokeAllDeveloperTokens(String developerId);
    
    /**
     * Vérifie si un développeur a des tokens révoqués
     */
    Mono<Boolean> hasDeveloperRevokedTokens(String developerId);
}
