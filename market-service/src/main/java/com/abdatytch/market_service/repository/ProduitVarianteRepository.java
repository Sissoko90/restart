package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.ProduitVariante;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les declinaisons d'un produit : couleur, taille, prix, stock. */
@Repository
public interface ProduitVarianteRepository extends ReactiveCrudRepository<ProduitVariante, UUID> {

    Flux<ProduitVariante> findByProduitId(UUID produitId);

    Mono<Void> deleteByProduitId(UUID produitId);
}
