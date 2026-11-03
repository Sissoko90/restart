package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Interface du service projet
 * Interface-service gère uniquement la création de base des projets
 */
public interface ProjectService {

    /**
     * Crée un nouveau projet et publie l'événement Kafka
     */
    Mono<ProjectResponse> createProject(CreateProjectRequest request, UUID userId);

    /**
     * Trouve tous les projets d'un utilisateur
     */
    Flux<ProjectResponse> findUserProjects(UUID userId);

    /**
     * Trouve un projet par ID et utilisateur
     */
    Mono<ProjectResponse> findProjectById(UUID projectId, UUID userId);

    /**
     * Met à jour un projet (nom et description uniquement)
     */
    Mono<ProjectResponse> updateProject(UUID projectId, UUID userId, CreateProjectRequest request);

    /**
     * Supprime un projet (soft delete) et publie l'événement
     */
    Mono<Void> deleteProject(UUID projectId, UUID userId);

    /**
     * Vérifie si l'utilisateur peut créer un nouveau projet
     */
    Mono<Boolean> canCreateProject(UUID userId);
}
