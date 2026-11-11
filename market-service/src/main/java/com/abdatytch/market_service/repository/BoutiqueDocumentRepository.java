package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.BoutiqueDocument;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les pieces justificatives d'une boutique. */
@Repository
public interface BoutiqueDocumentRepository extends ReactiveCrudRepository<BoutiqueDocument, UUID> {

    Flux<BoutiqueDocument> findByBoutiqueId(UUID boutiqueId);

    Mono<Void> deleteByBoutiqueId(UUID boutiqueId);
}
