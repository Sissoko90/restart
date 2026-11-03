package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service de gestion de la blacklist des tokens JWT révoqués
 */
public interface TokenBlacklistService {
    
    /**
     * Ajoute un token à la blacklist
     */
    Mono<Boolean> blacklistToken(String token, Duration ttl);
    
    /**
     * Vérifie si un token est blacklisté
     */
    Mono<Boolean> isBlacklisted(String token);
    
    /**
     * Supprime un token de la blacklist (pour nettoyage)
     */
    Mono<Boolean> removeFromBlacklist(String token);
}
