package com.abdatytch.media_service.repository;

import com.abdatytch.media_service.entity.MediaAccessLog;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

/**
 * Repository réactif pour MediaAccessLog
 * Gestion des logs d'audit d'accès aux médias
 */
@Repository
public interface MediaAccessLogRepository extends ReactiveMongoRepository<MediaAccessLog, String> {

    Flux<MediaAccessLog> findByMediaId(String mediaId);

    Flux<MediaAccessLog> findByUserId(String userId);

    Flux<MediaAccessLog> findByMediaIdAndAction(String mediaId, String action);

    Flux<MediaAccessLog> findByTimestampBetween(LocalDateTime start, LocalDateTime end);
}
