package com.abdatytch.user_service.service;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Interface pour le service de gestion de la blacklist des tokens
 */
public interface TokenBlacklistService {
    
    Mono<Void> revokeToken(String tokenId, Duration ttl);
    
    Mono<Boolean> isTokenRevoked(String tokenId);
    
    Mono<Void> revokeAllUserTokens(String userId);
    
    Mono<Boolean> hasRevokedTokens(String userId);
}
