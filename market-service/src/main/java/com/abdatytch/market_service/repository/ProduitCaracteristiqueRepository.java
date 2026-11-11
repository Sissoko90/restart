package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.ProduitCaracteristique;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les attributs libres d'un produit. */
@Repository
public interface ProduitCaracteristiqueRepository extends ReactiveCrudRepository<ProduitCaracteristique, UUID> {

    Flux<ProduitCaracteristique> findByProduitIdOrderByPositionAsc(UUID produitId);

    Mono<Void> deleteByProduitId(UUID produitId);
}
