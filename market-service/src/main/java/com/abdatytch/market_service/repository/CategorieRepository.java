package com.abdatytch.market_service.repository;

import com.abdatytch.market_service.entities.Categorie;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** L'arbre du catalogue. */
@Repository
public interface CategorieRepository extends ReactiveCrudRepository<Categorie, UUID> {

    Mono<Categorie> findBySlug(String slug);

    /** Les enfants directs d'un noeud ; {@code null} pour les racines. */
    @Query("SELECT * FROM categorie WHERE (:parentId IS NULL AND parent_id IS NULL) "
            + "OR parent_id = :parentId ORDER BY position, nom")
    Flux<Categorie> enfantsDe(UUID parentId);
}
