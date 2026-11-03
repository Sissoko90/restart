package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Implémentation réactive du service de gestion de la blacklist des tokens
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenBlacklistServiceImpl implements TokenBlacklistService {
    
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;
    
    private static final String REVOKED_TOKEN_PREFIX = "revoked:token:";
    private static final String REVOKED_USER_PREFIX = "revoked:user:";
    
    @Override
    public Mono<Void> revokeToken(String tokenId, Duration ttl) {
        log.info("Revoking token: {}", tokenId);
        String key = REVOKED_TOKEN_PREFIX + tokenId;
        return reactiveRedisTemplate.opsForValue()
            .set(key, "revoked", ttl)
            .doOnSuccess(v -> log.info("Token revoked successfully: {}", tokenId))
            .then();
    }
    
    @Override
    public Mono<Boolean> isTokenRevoked(String tokenId) {
        String key = REVOKED_TOKEN_PREFIX + tokenId;
        return reactiveRedisTemplate.hasKey(key);
    }
    
    @Override
    public Mono<Void> revokeAllUserTokens(String userId) {
        log.info("Revoking all tokens for user: {}", userId);
        String key = REVOKED_USER_PREFIX + userId;
        return reactiveRedisTemplate.opsForValue()
            .set(key, "revoked", Duration.ofDays(30))
            .doOnSuccess(v -> log.info("All tokens revoked for user: {}", userId))
            .then();
    }
    
    @Override
    public Mono<Boolean> hasRevokedTokens(String userId) {
        String key = REVOKED_USER_PREFIX + userId;
        return reactiveRedisTemplate.hasKey(key);
    }
}
