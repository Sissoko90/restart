package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Implémentation du service de cache Redis
 */
@Service
@RefreshScope
@Slf4j
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${cache.redis.enabled:true}")
    private boolean cacheEnabled;

    @Value("${cache.redis.key-prefix:media-service:}")
    private String keyPrefix;

    @Value("${cache.redis.ttl.document-metadata:3600}")
    private long defaultTtl;

    @Override
    public <T> Mono<T> get(String key, Class<T> type) {
        if (!cacheEnabled) {
            return Mono.empty();
        }

        String fullKey = keyPrefix + key;
        return redisTemplate.opsForValue()
            .get(fullKey)
            .map(value -> objectMapper.convertValue(value, type))
            .doOnSuccess(value -> {
                if (value != null) {
                    log.debug("Cache HIT: {}", key);
                } else {
                    log.debug("Cache MISS: {}", key);
                }
            })
            .onErrorResume(e -> {
                log.warn("Redis error on GET {}: {}", key, e.getMessage());
                return Mono.empty();
            });
    }

    @Override
    public <T> Mono<Boolean> set(String key, T value) {
        return set(key, value, Duration.ofSeconds(defaultTtl));
    }

    @Override
    public <T> Mono<Boolean> set(String key, T value, Duration ttl) {
        if (!cacheEnabled) {
            return Mono.just(false);
        }

        String fullKey = keyPrefix + key;
        return redisTemplate.opsForValue()
            .set(fullKey, value, ttl)
            .doOnSuccess(success -> {
                if (Boolean.TRUE.equals(success)) {
                    log.debug("Cache SET: {} (TTL: {}s)", key, ttl.getSeconds());
                }
            })
            .onErrorResume(e -> {
                log.warn("Redis error on SET {}: {}", key, e.getMessage());
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Boolean> delete(String key) {
        if (!cacheEnabled) {
            return Mono.just(false);
        }

        String fullKey = keyPrefix + key;
        return redisTemplate.delete(fullKey)
            .map(count -> count > 0)
            .doOnSuccess(deleted -> {
                if (Boolean.TRUE.equals(deleted)) {
                    log.debug("Cache DELETE: {}", key);
                }
            })
            .onErrorResume(e -> {
                log.warn("Redis error on DELETE {}: {}", key, e.getMessage());
                return Mono.just(false);
            });
    }

    @Override
    public Mono<Long> deletePattern(String pattern) {
        if (!cacheEnabled) {
            return Mono.just(0L);
        }

        String fullPattern = keyPrefix + pattern;
        return redisTemplate.keys(fullPattern)
            .flatMap(redisTemplate::delete)
            .reduce(0L, Long::sum)
            .doOnSuccess(count -> log.debug("Cache DELETE pattern {}: {} keys", pattern, count))
            .onErrorResume(e -> {
                log.warn("Redis error on DELETE pattern {}: {}", pattern, e.getMessage());
                return Mono.just(0L);
            });
    }

    @Override
    public Mono<Boolean> exists(String key) {
        if (!cacheEnabled) {
            return Mono.just(false);
        }

        String fullKey = keyPrefix + key;
        return redisTemplate.hasKey(fullKey)
            .onErrorResume(e -> {
                log.warn("Redis error on EXISTS {}: {}", key, e.getMessage());
                return Mono.just(false);
            });
    }

    @Override
    public <T> Mono<T> getOrCompute(String key, Class<T> type, Mono<T> supplier, Duration ttl) {
        return get(key, type)
            .switchIfEmpty(
                supplier
                    .flatMap(value -> 
                        set(key, value, ttl)
                            .thenReturn(value)
                    )
            );
    }
}
