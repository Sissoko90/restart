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
import org.springframework.web.server.ResponseStatusException;
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

    /**
     * L'identifiant du porteur, seule source du propriétaire.
     *
     * <h3>Pourquoi un seul extracteur</h3>
     *
     * {@code UUID.fromString(jwt.getSubject())} était écrit <b>six fois</b>,
     * une par endpoint. Six copies d'une conversion qui peut échouer, donc six
     * endroits à corriger le jour où elle doit échouer autrement.
     *
     * <h3>Un sujet malformé rend 401, et non 500</h3>
     *
     * {@code UUID.fromString} lève {@code IllegalArgumentException} sur un
     * sujet qui n'est pas un UUID. Rien ne la rattrapait : le conseil global
     * l'aurait rendue en <b>500</b>, c'est-à-dire en « panne de notre côté »
     * pour un jeton que nous n'avons pas émis.
     *
     * <p>Un sujet non conforme signifie que le jeton ne vient pas de cette
     * plateforme — {@code DevJwtServiceImpl} pose toujours
     * {@code developer.getId().toString()}, et {@code User.id} est un
     * {@code UUID}. C'est donc un défaut d'authentification : 401, pour que le
     * client se réauthentifie au lieu de réessayer.
     *
     * <p>Le motif n'est pas rendu au client : voir
     * {@code GlobalExceptionHandler#handleResponseStatus}.
     */
    private UUID identifiantDe(Jwt jwt) {
        String sujet = jwt.getSubject();

        // Le cas nul est traite SEPAREMENT, et ce n'est pas une precaution de
        // style : UUID.fromString(null) leve NullPointerException, pas
        // IllegalArgumentException. Un catch sur la seconde ne l'attrape donc
        // pas, et un jeton sans claim « sub » — que le decodeur de ce module
        // n'exige pas — serait sorti en 500.
        if (sujet == null || sujet.isBlank()) {
            log.warn("Jeton sans sujet, acces refuse");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Jeton invalide");
        }

        try {
            // A savoir : UUID.fromString est TOLERANT. Mesure sur ce JDK :
            //   11111111-1111-1111-1111-11111111111  -> 11111111-...-011111111111
            //   1-1-1-1-1                            -> 00000001-0001-0001-0001-000000000001
            // Les groupes trop courts sont completes par des zeros, donc deux
            // chaines distinctes peuvent designer le meme identifiant.
            //
            // Ce n'est pas exploitable ici : le sujet vient d'un jeton signe par
            // cette plateforme, et DevJwtServiceImpl n'y pose jamais que
            // developer.getId().toString(), deja canonique. La remarque est la
            // pour qui serait tente de deduire de ce code qu'un sujet accepte
            // est forcement bien forme.
            return UUID.fromString(sujet);
        } catch (IllegalArgumentException sujetNonConforme) {
            log.warn("Sujet de jeton non conforme, acces refuse");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Jeton invalide");
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un nouveau projet", description = "Crée un projet de base et publie l'événement Kafka")
    public Mono<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = identifiantDe(jwt);
        log.info("Création d'un projet par l'utilisateur: {}", userId);
        
        return projectService.createProject(request, userId);
    }

    @GetMapping
    @Operation(summary = "Lister les projets", description = "Récupère tous les projets de l'utilisateur connecté")
    public Flux<ProjectResponse> getUserProjects(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = identifiantDe(jwt);
        log.debug("Récupération des projets pour l'utilisateur: {}", userId);
        
        return projectService.findUserProjects(userId);
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "Détails d'un projet", description = "Récupère les détails d'un projet spécifique")
    public Mono<ProjectResponse> getProject(
            @Parameter(description = "ID du projet") @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = identifiantDe(jwt);
        log.debug("Récupération du projet: {} pour l'utilisateur: {}", projectId, userId);
        
        return projectService.findProjectById(projectId, userId);
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "Mettre à jour un projet", description = "Met à jour le nom et la description d'un projet")
    public Mono<ProjectResponse> updateProject(
            @Parameter(description = "ID du projet") @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = identifiantDe(jwt);
        log.info("Mise à jour du projet: {} par l'utilisateur: {}", projectId, userId);
        
        return projectService.updateProject(projectId, userId, request);
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un projet", description = "Supprime un projet (soft delete) et publie l'événement")
    public Mono<Void> deleteProject(
            @Parameter(description = "ID du projet") @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        
        UUID userId = identifiantDe(jwt);
        log.info("Suppression du projet: {} par l'utilisateur: {}", projectId, userId);
        
        return projectService.deleteProject(projectId, userId);
    }

    @GetMapping("/can-create")
    @Operation(summary = "Vérifier la possibilité de créer un projet", description = "Vérifie si l'utilisateur peut créer un nouveau projet")
    public Mono<Boolean> canCreateProject(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = identifiantDe(jwt);
        log.debug("Vérification de la possibilité de créer un projet pour l'utilisateur: {}", userId);
        
        return projectService.canCreateProject(userId);
    }
}
