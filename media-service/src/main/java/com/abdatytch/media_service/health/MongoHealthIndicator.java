package com.abdatytch.media_service.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class MongoHealthIndicator implements ReactiveHealthIndicator {
    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public Mono<Health> health() {
        return mongoTemplate.executeCommand("{ ping: 1 }")
            .map(result -> Health.up().withDetail("database", "MongoDB").build())
            .timeout(Duration.ofSeconds(5))
            .onErrorResume(e -> {
                log.error("MongoDB health check failed", e);
                return Mono.just(Health.down().withException(e).build());
            });
    }
}
