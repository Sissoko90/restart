package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.ErasureAcknowledgement;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ErasureAcknowledgementRepository
        extends ReactiveCrudRepository<ErasureAcknowledgement, String> {

    Flux<ErasureAcknowledgement> findByRequestId(String requestId);

    Mono<Boolean> existsByRequestIdAndServiceName(String requestId, String serviceName);

    Mono<Long> countByRequestId(String requestId);
}
