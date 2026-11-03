package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.constants.ApplicationConstants;
import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import com.abdatytch.interface_service.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Contrôleur REST pour la gestion des projets
 * Interface-service : création de base uniquement
 */
@RestController
@RequestMapping(ApplicationConstants.PROJECTS_ENDPOINT)
@Tag(name = "Projects", description = "Gestion des projets développeur")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un nouveau projet", description = "Crée un projet de base et publie l'événement Kafka")
    public Mono<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = UUID.fromString(jwt.getSubject());
        log.info("Création d'un projet par l'utilisateur: {}", userId);
        
        return projectService.createProject(request, userId);
    }

    @GetMapping
    @Operation(summary = "Lister les projets", description = "Récupère tous les projets de l'utilisateur connecté")
    public Flux<ProjectResponse> getUserProjects(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        log.debug("Récupération des projets pour l'utilisateur: {}", userId);
        
        return projectService.findUserProjects(userId);
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Détails d'un projet", description = "Récupère les détails d'un projet spécifique")
    public Mono<ProjectResponse> getProject(
            @Parameter(description = "ID du projet") @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = UUID.fromString(jwt.getSubject());
        log.debug("Récupération du projet: {} pour l'utilisateur: {}", projectId, userId);
        
        return projectService.findProjectById(projectId, userId);
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "Mettre à jour un projet", description = "Met à jour le nom et la description d'un projet")
    public Mono<ProjectResponse> updateProject(
            @Parameter(description = "ID du projet") @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = UUID.fromString(jwt.getSubject());
        log.info("Mise à jour du projet: {} par l'utilisateur: {}", projectId, userId);
        
        return projectService.updateProject(projectId, userId, request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un projet", description = "Supprime un projet (soft delete) et publie l'événement")
    public Mono<Void> deleteProject(
            @Parameter(description = "ID du projet") @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = UUID.fromString(jwt.getSubject());
        log.info("Suppression du projet: {} par l'utilisateur: {}", projectId, userId);
        
        return projectService.deleteProject(projectId, userId);
    }

    @GetMapping("/can-create")
    @Operation(summary = "Vérifier la possibilité de créer un projet", description = "Vérifie si l'utilisateur peut créer un nouveau projet")
    public Mono<Boolean> canCreateProject(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        log.debug("Vérification de la possibilité de créer un projet pour l'utilisateur: {}", userId);
        
        return projectService.canCreateProject(userId);
    }
}
