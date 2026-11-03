package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.constants.ApplicationConstants;
import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.entity.Project;
import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.client.QuotasServiceClient;
import com.abdatytch.interface_service.event.ProjectCreatedEvent;
import com.abdatytch.interface_service.event.ProjectDeletedEvent;
import com.abdatytch.interface_service.exception.BusinessException;
import com.abdatytch.interface_service.exception.ProjectLimitExceededException;
import com.abdatytch.interface_service.mapper.ProjectMapper;
import com.abdatytch.interface_service.metrics.ProjectMetrics;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.service.ProjectService;
import com.abdatytch.interface_service.service.UserService;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

/**
 * Implémentation du service projet
 * Interface-service : création de base + événements Kafka
 */
@Service
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectRepository projectRepository;
    private final UserService userService;
    private final ProjectMapper projectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ProjectMetrics metrics;
    private final QuotasServiceClient quotasServiceClient;

    public ProjectServiceImpl(ProjectRepository projectRepository, UserService userService,
                             ProjectMapper projectMapper, KafkaTemplate<String, Object> kafkaTemplate,
                             ReactiveRedisTemplate<String, Object> redisTemplate, ProjectMetrics metrics,
                             QuotasServiceClient quotasServiceClient) {
        this.projectRepository = projectRepository;
        this.userService = userService;
        this.projectMapper = projectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
        this.quotasServiceClient = quotasServiceClient;
    }

    @Override
    public Mono<ProjectResponse> createProject(CreateProjectRequest request, UUID userId) {
        log.info("Création d'un nouveau projet: {} pour l'utilisateur: {}", request.getProjectName(), userId);
        
        // Mono.defer autour de chaque étape : `.then(x)` évalue x À L'ASSEMBLAGE.
        // Sans defer, la requête d'unicité du nom et la lecture de l'utilisateur
        // partaient même lorsque le quota était refusé — deux appels inutiles sur
        // le chemin de rejet.
        return canCreateProject(userId)
                .filter(canCreate -> canCreate)
                .switchIfEmpty(Mono.error(new ProjectLimitExceededException(ApplicationConstants.MAX_PROJECTS_PER_USER)))
                .then(Mono.defer(() -> projectRepository.existsByNameAndUserId(request.getProjectName(), userId)))
                .filter(exists -> !exists)
                .switchIfEmpty(Mono.error(new BusinessException("Un projet avec ce nom existe déjà", "PROJECT_NAME_EXISTS")))
                .then(Mono.defer(() -> userService.findById(userId)))
                // userService.findById renvoie un UserResponse (DTO), pas l'entité User.
                // L'ancien `.cast(User.class)` levait donc systématiquement une
                // ClassCastException : toute création de projet échouait.
                .flatMap(user -> {
                    // Créer le projet de base
                    Project project = projectMapper.toEntity(request, userId);
                    
                    return projectRepository.save(project)
                            .doOnSuccess(savedProject -> {
                                // Publier l'événement Kafka de manière asynchrone
                                publishProjectCreatedEvent(savedProject, user)
                                        .subscribe(
                                            success -> log.info("Événement ProjectCreated publié pour le projet: {}", savedProject.getId()),
                                            error -> log.error("Erreur lors de la publication de l'événement: {}", error.getMessage())
                                        );
                            })
                            .map(projectMapper::toResponse);
                })
                .retryWhen(reactor.util.retry.Retry.backoff(3, Duration.ofSeconds(1))
                        .filter(throwable -> !(throwable instanceof RuntimeException)))
                .doOnError(error -> log.error("Erreur lors de la création du projet: {}", error.getMessage()));
    }

    @Override
    public Flux<ProjectResponse> findUserProjects(UUID userId) {
        log.debug("Récupération des projets pour l'utilisateur: {}", userId);
        
        return projectRepository.findByUserId(userId)
                .map(projectMapper::toResponse)
                .doOnError(error -> log.error("Erreur lors de la récupération des projets: {}", error.getMessage()));
    }

    @Override
    public Mono<ProjectResponse> findProjectById(UUID projectId, UUID userId) {
        log.debug("Récupération du projet: {} pour l'utilisateur: {}", projectId, userId);
        
        return projectRepository.findByIdAndUserId(projectId, userId)
                .map(projectMapper::toResponse)
                .switchIfEmpty(Mono.error(new RuntimeException("Projet non trouvé")));
    }

    @Override
    public Mono<ProjectResponse> updateProject(UUID projectId, UUID userId, CreateProjectRequest request) {
        log.info("Mise à jour du projet: {} pour l'utilisateur: {}", projectId, userId);
        
        return projectRepository.findByIdAndUserId(projectId, userId)
                .switchIfEmpty(Mono.error(new RuntimeException("Projet non trouvé")))
                .flatMap(project -> {
                    project.setName(request.getProjectName());
                    project.setDescription(request.getDescription());
                    return projectRepository.save(project);
                })
                .map(projectMapper::toResponse)
                .doOnError(error -> log.error("Erreur lors de la mise à jour du projet: {}", error.getMessage()));
    }

    @Override
    public Mono<Void> deleteProject(UUID projectId, UUID userId) {
        log.info("Suppression du projet: {} pour l'utilisateur: {}", projectId, userId);
        
        return projectRepository.findByIdAndUserId(projectId, userId)
                .switchIfEmpty(Mono.error(new RuntimeException("Projet non trouvé")))
                .flatMap(project -> {
                    project.markAsDeleted();
                    return projectRepository.save(project);
                })
                .doOnSuccess(project -> {
                    // Publier événement de suppression
                    publishProjectDeletedEvent(project)
                            .subscribe(
                                success -> log.info("Événement ProjectDeleted publié pour le projet: {}", project.getId()),
                                error -> log.error("Erreur lors de la publication de l'événement de suppression: {}", error.getMessage())
                            );
                })
                .then()
                .doOnError(error -> log.error("Erreur lors de la suppression du projet: {}", error.getMessage()));
    }

    /**
     * Le droit de créer un projet est décidé par quotas-service, seule source de
     * vérité des limites par palier commercial.
     *
     * <p>L'ancienne implémentation comptait les projets en base et comparait à une
     * constante codée en dur ({@code MAX_PROJECTS_PER_USER = 10}), identique pour
     * tous les utilisateurs quel que soit leur palier.
     *
     * <p>Si quotas-service est injoignable, le client autorise par défaut
     * (voir {@link QuotasServiceClient}).
     */
    @Override
    public Mono<Boolean> canCreateProject(UUID userId) {
        return quotasServiceClient.canCreateProject(userId)
                .doOnNext(canCreate -> log.debug("L'utilisateur {} peut créer un projet: {}", userId, canCreate));
    }

    /**
     * Publie l'événement de création de projet via Kafka
     */
    private Mono<Void> publishProjectCreatedEvent(Project project, UserResponse user) {
        // Tout est construit À L'INTÉRIEUR du scope boundedElastic :
        //  - ProjectCreatedEvent.create() appelle UUID.randomUUID(), donc SecureRandom,
        //    qui lit /dev/random — un appel bloquant ;
        //  - KafkaTemplate.send() peut attendre les métadonnées du topic ou la
        //    libération du buffer producteur (max.block.ms vaut 60 s par défaut).
        // Construire l'événement à l'extérieur l'aurait laissé s'exécuter sur le
        // thread réactif, avant même que subscribeOn ne s'applique.
        return Mono.fromRunnable(() -> {
                    ProjectCreatedEvent event = ProjectCreatedEvent.create(
                            project.getId(),
                            project.getName(),
                            project.getDescription(),
                            project.getUserId(),
                            user.getEmail(),
                            user.getUsername(),
                            project.getCreatedAt());

                    kafkaTemplate.send(ApplicationConstants.TOPIC_PROJECT_CREATED,
                            project.getId().toString(), event);
                    log.debug("Événement ProjectCreated envoyé: {}", event);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Publie l'événement de suppression de projet via Kafka
     */
    private Mono<Void> publishProjectDeletedEvent(Project project) {
        // L'événement porte le userId : sans lui, quotas-service ne peut pas savoir
        // à quel utilisateur restituer le quota libéré.
        // Même raison que pour ProjectCreated : construction (UUID/SecureRandom) et
        // envoi Kafka sont tous deux à l'intérieur du scope boundedElastic.
        return Mono.fromRunnable(() -> {
                    ProjectDeletedEvent event = ProjectDeletedEvent.create(
                            project.getId(), project.getUserId(), project.getName());

                    kafkaTemplate.send(ApplicationConstants.TOPIC_PROJECT_DELETED,
                            project.getId().toString(), event);
                    log.debug("Événement ProjectDeleted envoyé pour le projet: {}", project.getId());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
