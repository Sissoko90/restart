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
import com.abdatytch.interface_service.exception.RessourceIntrouvableException;
import com.abdatytch.interface_service.mapper.ProjectMapper;
import com.abdatytch.interface_service.metrics.ProjectMetrics;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.service.ProjectService;
import com.abdatytch.interface_service.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
                                // Le compteur etait DECLARE et jamais incremente.
                                // ProjectMetrics est injecte ici et dans
                                // UserServiceImpl, et n'etait appele par aucun des
                                // deux : neuf metriques definies, zero emission.
                                metrics.incrementProjectCreated(savedProject.getUserId().toString());

                                // Publier l'événement Kafka de manière asynchrone
                                publishProjectCreatedEvent(savedProject, user)
                                        .subscribe(
                                            success -> log.info("Événement ProjectCreated publié pour le projet: {}", savedProject.getId()),
                                            error -> log.error("Erreur lors de la publication de l'événement: {}", error.getMessage())
                                        );
                            })
                            .map(projectMapper::toResponse);
                })
                // Le retryWhen a ete RETIRE d'ici, et il ne doit pas revenir tel
                // quel. Il etait :
                //
                //   .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                //           .filter(t -> !(t instanceof RuntimeException)))
                //
                // Son filtre n'admettait que les throwables qui ne sont PAS des
                // RuntimeException. Or tout ce que cette chaine peut emettre en
                // est une : ProjectLimitExceededException et BusinessException
                // en heritent, les erreurs R2DBC passent par
                // DataAccessException, et celles de Kafka par KafkaException.
                // Les Error fatales, elles, ne sont pas signalees mais levees
                // (Exceptions.throwIfFatal). Le filtre ne laissait donc rien
                // passer : trois tentatives configurees, zero effectuee.
                //
                // Et le faire fonctionner serait PIRE que de le retirer. La
                // politique porte sur la chaine entiere, save() compris : si le
                // projet etait enregistre puis qu'une etape ulterieure echouait,
                // la tentative suivante rejouerait canCreateProject, le controle
                // d'unicite et save() — donc creerait un SECOND projet. Un
                // reessai n'est sur que sur une operation idempotente, ce que
                // cette creation n'est pas.
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
                .switchIfEmpty(Mono.error(projetIntrouvable(projectId)));
    }

    /**
     * Le refus commun aux trois accès par identifiant.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * Les trois levaient {@code new RuntimeException("Projet non trouvé")}.
     * Aucun gestionnaire ne connaît {@code RuntimeException} : le conseil global
     * les rendait donc en <b>500</b>. Un client ne pouvait pas distinguer « ce
     * projet n'existe pas » d'une panne, et le trafic normal alimentait le taux
     * d'erreurs 5xx sur lequel reposent les alertes.
     *
     * <h3>Le message ne nomme pas le propriétaire</h3>
     *
     * {@code findByIdAndUserId} ne rend rien dans deux cas qu'il ne distingue
     * pas : le projet n'existe pas, ou il appartient à quelqu'un d'autre. Les
     * deux donnent le même 404 — distinguer permettrait d'énumérer les projets
     * des autres en lisant le seul code de statut.
     */
    private RessourceIntrouvableException projetIntrouvable(UUID projectId) {
        return new RessourceIntrouvableException("Projet " + projectId + " introuvable");
    }

    @Override
    public Mono<ProjectResponse> updateProject(UUID projectId, UUID userId, CreateProjectRequest request) {
        log.info("Mise à jour du projet: {} pour l'utilisateur: {}", projectId, userId);
        
        return projectRepository.findByIdAndUserId(projectId, userId)
                .switchIfEmpty(Mono.error(projetIntrouvable(projectId)))
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
                .switchIfEmpty(Mono.error(projetIntrouvable(projectId)))
                .flatMap(project -> {
                    project.markAsDeleted();
                    return projectRepository.save(project);
                })
                .doOnSuccess(project -> {
                    metrics.incrementProjectDeleted(project.getUserId().toString());

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
     * Compte l'aboutissement d'un envoi Kafka.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * {@code KafkaTemplate.send()} rend un {@code CompletableFuture} et ne lève
     * rien si le courtier refuse ou si le délai d'attente des métadonnées
     * expire. Le résultat était ignoré : l'échec survenait <b>après</b> le
     * retour de la méthode, donc ni le {@code Mono} rendu ni le
     * {@code log.debug} qui suivait l'appel ne le voyaient. Un
     * {@code log.debug("événement envoyé")} s'écrivait alors même que rien ne
     * partait.
     *
     * <p>{@code interface.kafka.event.failed.total} existait pour exactement
     * cela, et n'était incrémenté nulle part.
     *
     * <h3>Ce que cela ne corrige pas</h3>
     *
     * La publication reste détachée de la requête : un échec n'est pas rejoué
     * et l'appelant reçoit quand même un 201. C'est un choix assumé — faire
     * échouer la création parce que Kafka est indisponible serait pire — mais
     * cela signifie qu'une divergence avec quotas-service reste possible. Le
     * compteur la rend seulement <b>visible</b>, ce qui est la condition pour
     * qu'une réconciliation puisse un jour être décidée.
     *
     * @param typeDEvenement étiquette de cardinalité bornée : le nom de
     *                       l'événement, jamais un identifiant
     */
    private void observerEnvoi(java.util.concurrent.CompletableFuture<?> envoi,
                               String typeDEvenement) {
        envoi.whenComplete((resultat, erreur) -> {
            if (erreur == null) {
                metrics.incrementKafkaEventSent(typeDEvenement);
                return;
            }
            // Le nom simple de la classe, et non le message : un message porte
            // des noms de courtier et des identifiants, donc une cardinalite
            // non bornee qui ferait exploser le nombre de series Prometheus.
            metrics.incrementKafkaEventFailed(typeDEvenement,
                    erreur.getClass().getSimpleName());
            log.error("Publication Kafka {} echouee : {}", typeDEvenement,
                    erreur.getMessage());
        });
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

                    // Le resultat de send() etait IGNORE.
                    //
                    // send() rend un CompletableFuture et ne leve rien si le
                    // courtier refuse : l'echec arrivait apres le retour de la
                    // methode, donc ni le Mono ni le log.debug ci-dessous ne le
                    // voyaient. L'evenement etait porte disparu en silence, et
                    // quotas-service ne decomptait jamais le projet.
                    //
                    // Observer le future ne change pas la semantique — la
                    // publication reste detachee de la requete — mais l'echec
                    // devient comptable, donc alertable.
                    observerEnvoi(kafkaTemplate.send(ApplicationConstants.TOPIC_PROJECT_CREATED,
                            project.getId().toString(), event), "ProjectCreated");
                    log.debug("Événement ProjectCreated remis au producteur: {}", event);
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

                    // Meme raison qu'au-dessus, et l'enjeu est plus direct ici :
                    // cet evenement porte le userId pour que quotas-service
                    // restitue le quota libere. Perdu en silence, le quota reste
                    // consomme par un projet supprime.
                    observerEnvoi(kafkaTemplate.send(ApplicationConstants.TOPIC_PROJECT_DELETED,
                            project.getId().toString(), event), "ProjectDeleted");
                    log.debug("Événement ProjectDeleted remis au producteur: {}", project.getId());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
