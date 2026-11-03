package com.abdatytch.media_service.health;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioHealthIndicator implements ReactiveHealthIndicator {
    private final MinioClient minioClient;
    
    @Value("${minio.bucket-name}")
    private String bucketName;

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(() -> {
            minioClient.bucketExists(io.minio.BucketExistsArgs.builder().bucket(bucketName).build());
            return Health.up().withDetail("storage", "MinIO").withDetail("bucket", bucketName).build();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .timeout(Duration.ofSeconds(5))
        .onErrorResume(e -> {
            log.error("MinIO health check failed", e);
            return Mono.just(Health.down().withException(e).build());
        });
    }
}
