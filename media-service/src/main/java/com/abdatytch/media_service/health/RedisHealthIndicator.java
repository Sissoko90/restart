package com.abdatytch.media_service.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements ReactiveHealthIndicator {
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    @Override
    public Mono<Health> health() {
        return redisTemplate.execute(connection -> connection.ping())
            .next()
            .map(response -> Health.up().withDetail("cache", "Redis").build())
            .timeout(Duration.ofSeconds(3))
            .onErrorResume(e -> {
                log.error("Redis health check failed", e);
                return Mono.just(Health.down().withException(e).build());
            });
    }
}
