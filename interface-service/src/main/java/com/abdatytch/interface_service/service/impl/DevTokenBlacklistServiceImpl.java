package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.service.IDevTokenBlacklistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service de blacklist des tokens JWT pour les développeurs
 * Utilise Redis pour stocker les tokens révoqués
 */
@Service
public class DevTokenBlacklistServiceImpl implements IDevTokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(DevTokenBlacklistServiceImpl.class);
    
    private static final String TOKEN_BLACKLIST_PREFIX = "dev:token:blacklist:";
    private static final String DEVELOPER_REVOKED_PREFIX = "dev:revoked:";
    
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public DevTokenBlacklistServiceImpl(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> revokeToken(String tokenId, long expirationMs) {
        String key = TOKEN_BLACKLIST_PREFIX + tokenId;
        return redisTemplate.opsForValue()
                .set(key, "revoked", Duration.ofMillis(expirationMs))
                .doOnSuccess(v -> log.info("Token révoqué: {}", tokenId))
                .then();
    }

    @Override
    public Mono<Boolean> isTokenRevoked(String tokenId) {
        String key = TOKEN_BLACKLIST_PREFIX + tokenId;
        return redisTemplate.hasKey(key);
    }

    @Override
    public Mono<Void> revokeAllDeveloperTokens(String developerId) {
        String key = DEVELOPER_REVOKED_PREFIX + developerId;
        return redisTemplate.opsForValue()
                .set(key, String.valueOf(System.currentTimeMillis()), Duration.ofDays(7))
                .doOnSuccess(v -> log.info("Tous les tokens révoqués pour le développeur: {}", developerId))
                .then();
    }

    @Override
    public Mono<Boolean> hasDeveloperRevokedTokens(String developerId) {
        String key = DEVELOPER_REVOKED_PREFIX + developerId;
        return redisTemplate.hasKey(key);
    }
}
