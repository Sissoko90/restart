package com.abdatytch.order_service.repository;

import com.abdatytch.order_service.entities.CommandeLigne;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public interface CommandeLigneRepository extends ReactiveCrudRepository<CommandeLigne, UUID> {

    Flux<CommandeLigne> findByCommandeIdOrderByCreatedAtAsc(UUID commandeId);
}
