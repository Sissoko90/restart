package com.abdatytch.interface_service;

import com.abdatytch.interface_service.client.QuotasServiceClient;
import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import com.abdatytch.interface_service.entity.Project;
import com.abdatytch.interface_service.exception.ProjectLimitExceededException;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.mapper.ProjectMapper;
import com.abdatytch.interface_service.metrics.ProjectMetrics;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.service.UserService;
import com.abdatytch.interface_service.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Vérifie que la création de projet ne bloque aucun thread réactif.
 *
 * <p>Ce chemin est sensible pour deux raisons :
 * <ul>
 *   <li>il appelle <b>quotas-service en synchrone</b> avant de créer, pour refuser
 *       au bon moment un dépassement de quota ;</li>
 *   <li>il publie un événement Kafka. {@code KafkaTemplate.send()} n'est pas garanti
 *       non bloquant : le producteur peut attendre les métadonnées du topic ou la
 *       libération de son buffer ({@code max.block.ms} vaut 60 s par défaut).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("interface-service : absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final UUID USER_ID = UUID.randomUUID();

    /**
     * Remet les ordonnanceurs a neuf, <b>puis</b> installe BlockHound.
     *
     * <p>Reactor met l'ordonnanceur {@code parallel} en cache pour toute la
     * JVM, a sa premiere utilisation. Si cette premiere utilisation tombe dans
     * une fenetre ou la fabrique globale d'ordonnanceurs a ete remplacee —
     * {@code StepVerifier.withVirtualTime} le fait, le temps d'une
     * verification — le cache retient un ordonnanceur dont les fils sont de
     * simples {@code Thread}, sans le marqueur {@code NonBlocking} sur lequel
     * repose toute la detection. Le cache survit ensuite au retablissement de
     * la fabrique.
     *
     * <p>Consequence observee dans auth-service : le controle negatif echouait
     * deux fois sur trois en suite complete, et passait toujours en isolation.
     * Les deux vrais tests de non-blocage passaient alors <b>pour la mauvaise
     * raison</b>, sans rien verifier.
     *
     * <p>{@code shutdownNow()} vide ce cache : les ordonnanceurs suivants sont
     * crees par la vraie fabrique, quelle que soit l'histoire de la JVM avant
     * cette classe.
     */
    @BeforeAll
    static void installBlockHound() {
        Schedulers.shutdownNow();
        // Le chargement de classes est autorise : voir le javadoc de
        // media-service/NoBlockingCallsTest. BlockHound arme la detection pour
        // TOUTE la JVM, et un chargement de classe paresseux depuis un JAR
        // faisait echouer, en integration continue seulement, des tests sans
        // rapport avec le non-blocage.
        BlockHound.install(builder ->
                builder.allowBlockingCallsInside("java.lang.ClassLoader", "loadClass"));
    }

    @Mock private ProjectRepository projectRepository;
    @Mock private UserService userService;
    @Mock private ProjectMapper projectMapper;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private ReactiveRedisTemplate<String, Object> redisTemplate;
    @Mock private ProjectMetrics metrics;
    @Mock private QuotasServiceClient quotasServiceClient;

    private ProjectServiceImpl projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectServiceImpl(projectRepository, userService, projectMapper,
                kafkaTemplate, redisTemplate, metrics, quotasServiceClient);
    }

    private CreateProjectRequest request() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setProjectName("mon-projet");
        request.setDescription("Un projet de test");
        return request;
    }

    @Test
    @DisplayName("BlockHound est bien actif : un Thread.sleep sur un thread réactif est détecté")
    void blockHoundIsActive() {
        // Contrôle négatif : sans lui, « aucun blocage détecté » pourrait simplement
        // vouloir dire que BlockHound n'est pas installé.
        Mono<Long> blocking = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1L;
        }).subscribeOn(Schedulers.parallel());

        assertThatThrownBy(() -> blocking.block(Duration.ofSeconds(5)))
                .hasRootCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    @DisplayName("la création de projet ne bloque aucun thread réactif")
    void projectCreationDoesNotBlock() {
        // findById renvoie un DTO, pas l'entité : c'est ce qu'attend createProject
        UserResponse user = new UserResponse();
        user.setEmail("dev@example.com");
        user.setUsername("dev");

        Project project = new Project();
        project.setName("mon-projet");
        project.setUserId(USER_ID);

        when(quotasServiceClient.canCreateProject(USER_ID)).thenReturn(Mono.just(true));
        when(projectRepository.existsByNameAndUserId(anyString(), any(UUID.class)))
                .thenReturn(Mono.just(false));
        when(userService.findById(USER_ID)).thenReturn(Mono.just(user));
        when(projectMapper.toEntity(any(), any(UUID.class))).thenReturn(project);
        when(projectRepository.save(any(Project.class))).thenReturn(Mono.just(project));
        when(projectMapper.toResponse(any(Project.class))).thenReturn(new ProjectResponse());
        // L'envoi Kafka est simulé : ce qui compte est le thread sur lequel il part
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThatCode(() -> {
            ProjectResponse response = projectService.createProject(request(), USER_ID)
                    .subscribeOn(Schedulers.parallel())
                    .block(Duration.ofSeconds(30));
            assertThat(response).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un refus de quota ne bloque pas non plus (chemin d'erreur)")
    void quotaRejectionDoesNotBlock() {
        when(quotasServiceClient.canCreateProject(USER_ID)).thenReturn(Mono.just(false));

        assertThatThrownBy(() -> projectService.createProject(request(), USER_ID)
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(30)))
                // L'important est le type d'échec : un dépassement de quota, pas un
                // BlockingOperationError
                .isNotInstanceOf(BlockingOperationError.class)
                .isInstanceOf(ProjectLimitExceededException.class)
                .hasMessageContaining("Limite de projets atteinte");
    }
}
