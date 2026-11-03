package com.abdatytch.interface_service.repository;

import com.abdatytch.interface_service.entity.Project;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository pour les projets
 * Interface-service gère uniquement la création de base des projets
 */
@Repository
public interface ProjectRepository extends ReactiveCrudRepository<Project, UUID> {

    /**
     * Trouve tous les projets d'un utilisateur
     */
    @Query("SELECT * FROM projects WHERE user_id = :userId AND is_active = true ORDER BY created_at DESC")
    Flux<Project> findByUserId(UUID userId);

    /**
     * Trouve un projet par ID et utilisateur
     */
    @Query("SELECT * FROM projects WHERE id = :projectId AND user_id = :userId AND is_active = true")
    Mono<Project> findByIdAndUserId(UUID projectId, UUID userId);

    /**
     * Compte le nombre de projets actifs d'un utilisateur
     */
    @Query("SELECT COUNT(*) FROM projects WHERE user_id = :userId AND is_active = true")
    Mono<Long> countByUserId(UUID userId);

    /**
     * Vérifie si un nom de projet existe déjà pour un utilisateur
     */
    @Query("SELECT COUNT(*) > 0 FROM projects WHERE name = :name AND user_id = :userId AND is_active = true")
    Mono<Boolean> existsByNameAndUserId(String name, UUID userId);
}
