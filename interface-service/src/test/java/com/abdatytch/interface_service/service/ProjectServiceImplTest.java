package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.client.QuotasServiceClient;
import com.abdatytch.interface_service.entity.Project;
import com.abdatytch.interface_service.exception.BusinessException;
import com.abdatytch.interface_service.exception.ProjectLimitExceededException;
import com.abdatytch.interface_service.exception.RessourceIntrouvableException;
import com.abdatytch.interface_service.mapper.ProjectMapper;
import com.abdatytch.interface_service.metrics.ProjectMetrics;
import com.abdatytch.interface_service.repository.ProjectRepository;
import com.abdatytch.interface_service.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La logique de projet, exercée pour la première fois.
 *
 * <h2>Les défauts trouvés ici</h2>
 *
 * <ol>
 *   <li><b>Trois refus rendaient 500.</b> Lecture, mise à jour et suppression
 *       levaient {@code new RuntimeException("Projet non trouvé")}. Aucun
 *       gestionnaire ne connaît {@code RuntimeException} : le conseil global
 *       les rendait en erreur serveur. Un client ne pouvait pas distinguer
 *       « ce projet n'existe pas » d'une panne, et le trafic normal alimentait
 *       le taux d'erreurs 5xx dont dépendent les alertes.</li>
 *   <li><b>Le {@code retryWhen} ne réessayait rien.</b> Son filtre
 *       ({@code !(t instanceof RuntimeException)}) n'admettait que les
 *       throwables qui ne sont pas des {@code RuntimeException}, alors que tout
 *       ce que cette chaîne émet en est une. Trois tentatives configurées, zéro
 *       effectuée — et le faire fonctionner aurait créé des projets en double,
 *       la politique portant sur la chaîne entière, {@code save()} compris.</li>
 *   <li><b>Neuf métriques déclarées, zéro émission.</b> {@code ProjectMetrics}
 *       était injecté ici <i>et</i> dans {@code UserServiceImpl}, et appelé par
 *       aucun des deux.</li>
 *   <li><b>Le résultat de {@code kafkaTemplate.send()} était ignoré.</b> Il
 *       rend un {@code CompletableFuture} et ne lève rien si le courtier
 *       refuse : l'échec survenait après le retour de la méthode, donc le
 *       {@code log.debug("événement envoyé")} s'écrivait alors que rien ne
 *       partait.</li>
 * </ol>
 *
 * <h2>Ce que ce test garde en priorité</h2>
 *
 * Que le dépôt soit <b>toujours</b> interrogé avec l'identifiant du projet
 * <i>et</i> celui du porteur, et que les deux cas « inexistant » et
 * « appartient à autrui » soient <b>indistinguables</b>. C'est la seule chose
 * ici qui, si elle cassait, laisserait lire les projets des autres.
 */
@DisplayName("ProjectServiceImpl - propriete, refus et evenements")
class ProjectServiceImplTest {

    private static final UUID PORTEUR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTRUI = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PROJET = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ProjectRepository depot;
    private UserService serviceUtilisateur;
    private ProjectMapper mappeur;
    private KafkaTemplate<String, Object> kafka;
    private ProjectMetrics metriques;
    private QuotasServiceClient quotas;
    private ProjectServiceImpl service;

    private CreateProjectRequest requete;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        depot = Mockito.mock(ProjectRepository.class);
        serviceUtilisateur = Mockito.mock(UserService.class);
        mappeur = Mockito.mock(ProjectMapper.class);
        kafka = Mockito.mock(KafkaTemplate.class);
        metriques = Mockito.mock(ProjectMetrics.class);
        quotas = Mockito.mock(QuotasServiceClient.class);

        service = new ProjectServiceImpl(depot, serviceUtilisateur, mappeur, kafka,
                Mockito.mock(ReactiveRedisTemplate.class), metriques, quotas);

        // De VRAIS DTO, pas des mocks : ils ont un constructeur sans argument et
        // des setters. Mocker un porteur de donnees ne verifie que le mock, et
        // la premiere version de ce fichier s'y est cassee les dents sur un
        // UnfinishedStubbing.
        requete = new CreateProjectRequest();
        requete.setProjectName("mon-projet");
        requete.setDescription("une description");

        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private Project projet(UUID proprietaire) {
        Project p = new Project();
        p.setId(PROJET);
        p.setUserId(proprietaire);
        p.setName("mon-projet");
        p.setDescription("une description");
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    private UserResponse utilisateur() {
        UserResponse u = new UserResponse();
        u.setEmail("dev@example.test");
        u.setUsername("dev");
        return u;
    }

    private ProjectResponse reponseProjet() {
        ProjectResponse r = new ProjectResponse();
        r.setId(PROJET);
        r.setName("mon-projet");
        return r;
    }

    private static Throwable refusDe(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La propriete est TOUJOURS dans la requete")
    class Propriete {

        /**
         * LE TEST LE PLUS IMPORTANT DE CE FICHIER.
         *
         * <p>Si l'une des trois méthodes interrogeait le dépôt par le seul
         * identifiant de projet, n'importe quel porteur lirait, modifierait ou
         * supprimerait le projet d'un autre en devinant un UUID. Ce test nomme
         * l'invariant pour qu'un refactoring ne puisse pas le perdre en
         * silence.
         */
        @Test
        @DisplayName("lecture, mise a jour et suppression filtrent sur le porteur")
        void lesTroisFiltrentSurLePorteur() {
            when(depot.findByIdAndUserId(any(), any())).thenReturn(Mono.empty());

            refusDe(() -> service.findProjectById(PROJET, PORTEUR).block());
            refusDe(() -> service.updateProject(PROJET, PORTEUR, requete).block());
            refusDe(() -> service.deleteProject(PROJET, PORTEUR).block());

            verify(depot, Mockito.times(3)).findByIdAndUserId(PROJET, PORTEUR);
            verify(depot, never()).findById(any(UUID.class));
        }

        /**
         * Les deux cas sont INDISTINGUABLES.
         *
         * <p>{@code findByIdAndUserId} ne rend rien aussi bien pour un projet
         * inexistant que pour le projet de quelqu'un d'autre, et les deux
         * donnent le même refus. Répondre différemment permettrait d'énumérer
         * les projets des autres en lisant le seul code de statut.
         */
        @Test
        @DisplayName("« inexistant » et « appartient a autrui » donnent le MEME refus")
        void refusIndistinguables() {
            // Le projet existe, mais pour AUTRUI : la requete du porteur ne rend
            // donc rien, exactement comme pour un identifiant inexistant.
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.empty());
            when(depot.findByIdAndUserId(PROJET, AUTRUI)).thenReturn(Mono.just(projet(AUTRUI)));

            Throwable refus = refusDe(() -> service.findProjectById(PROJET, PORTEUR).block());

            assertThat(refus)
                    .as("un projet appartenant a autrui doit donner le meme refus "
                            + "qu'un projet inexistant")
                    .isInstanceOf(RessourceIntrouvableException.class);
            assertThat(refus.getMessage())
                    .as("le refus nomme le proprietaire : l'appelant apprend que le "
                            + "projet existe")
                    .doesNotContain(AUTRUI.toString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un projet introuvable rend 404, et non 500")
    class Introuvable {

        @Test
        @DisplayName("la lecture leve RessourceIntrouvableException")
        void lecture() {
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.empty());

            StepVerifier.create(service.findProjectById(PROJET, PORTEUR))
                    .expectError(RessourceIntrouvableException.class)
                    .verify();
        }

        @Test
        @DisplayName("la mise a jour leve RessourceIntrouvableException")
        void miseAJour() {
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.empty());

            StepVerifier.create(service.updateProject(PROJET, PORTEUR, requete))
                    .expectError(RessourceIntrouvableException.class)
                    .verify();
        }

        @Test
        @DisplayName("la suppression leve RessourceIntrouvableException")
        void suppression() {
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteProject(PROJET, PORTEUR))
                    .expectError(RessourceIntrouvableException.class)
                    .verify();
        }

        /**
         * Le type exact compte, et pas seulement « une erreur ».
         *
         * <p>Avant correction c'était {@code RuntimeException}, que le conseil
         * global rend en 500. Vérifier le type précis est ce qui distingue le
         * correctif de l'état antérieur : un test qui se contenterait de
         * {@code expectError()} passait déjà avant.
         */
        @Test
        @DisplayName("ce n'est PAS une RuntimeException nue")
        void pasUneRuntimeExceptionNue() {
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.empty());

            StepVerifier.create(service.findProjectById(PROJET, PORTEUR))
                    .expectErrorSatisfies(erreur -> assertThat(erreur)
                            .as("une RuntimeException nue sort en 500 : le client "
                                    + "conclut a une panne et reessaie une requete qui "
                                    + "ne reussira jamais")
                            .isNotExactlyInstanceOf(RuntimeException.class)
                            .isInstanceOf(BusinessException.class))
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Creation : le quota decide AVANT tout travail")
    class Creation {

        @Test
        @DisplayName("quota refuse : ProjectLimitExceededException")
        void quotaRefuse() {
            when(quotas.canCreateProject(PORTEUR)).thenReturn(Mono.just(false));

            StepVerifier.create(service.createProject(requete, PORTEUR))
                    .expectError(ProjectLimitExceededException.class)
                    .verify();
        }

        /**
         * Le chemin de rejet ne touche NI la base NI le service utilisateur.
         *
         * <p>C'est ce que garantissent les {@code Mono.defer} : sans eux,
         * {@code .then(x)} évalue {@code x} à l'assemblage, et le contrôle
         * d'unicité du nom comme la lecture de l'utilisateur partaient même
         * quand le quota refusait.
         */
        @Test
        @DisplayName("quota refuse : aucun appel inutile a la base")
        void quotaRefuseNAppellePersonne() {
            when(quotas.canCreateProject(PORTEUR)).thenReturn(Mono.just(false));

            refusDe(() -> service.createProject(requete, PORTEUR).block());

            verify(depot, never()).existsByNameAndUserId(anyString(), any());
            verify(serviceUtilisateur, never()).findById(any());
            verify(depot, never()).save(any());
        }

        @Test
        @DisplayName("nom deja pris : BusinessException PROJECT_NAME_EXISTS")
        void nomDejaPris() {
            when(quotas.canCreateProject(PORTEUR)).thenReturn(Mono.just(true));
            when(depot.existsByNameAndUserId("mon-projet", PORTEUR)).thenReturn(Mono.just(true));

            StepVerifier.create(service.createProject(requete, PORTEUR))
                    .expectErrorSatisfies(erreur -> assertThat(
                            ((BusinessException) erreur).getErrorCode())
                            .isEqualTo("PROJECT_NAME_EXISTS"))
                    .verify();

            verify(depot, never()).save(any());
        }

        @Test
        @DisplayName("succes : le projet est enregistre et la metrique incrementee")
        void succes() {
            Project enregistre = projet(PORTEUR);
            ProjectResponse reponse = reponseProjet();

            when(quotas.canCreateProject(PORTEUR)).thenReturn(Mono.just(true));
            when(depot.existsByNameAndUserId("mon-projet", PORTEUR)).thenReturn(Mono.just(false));
            when(serviceUtilisateur.findById(PORTEUR)).thenReturn(Mono.just(utilisateur()));
            when(mappeur.toEntity(requete, PORTEUR)).thenReturn(projet(PORTEUR));
            when(depot.save(any())).thenReturn(Mono.just(enregistre));
            when(mappeur.toResponse(enregistre)).thenReturn(reponse);

            StepVerifier.create(service.createProject(requete, PORTEUR))
                    .expectNext(reponse)
                    .verifyComplete();

            // Le compteur etait declare et jamais incremente.
            verify(metriques).incrementProjectCreated(PORTEUR.toString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Suppression : logique, et le quota est restitue")
    class Suppression {

        @Test
        @DisplayName("la suppression est LOGIQUE, pas une ligne retiree")
        void suppressionLogique() {
            Project existant = projet(PORTEUR);
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.just(existant));
            when(depot.save(any())).thenReturn(Mono.just(existant));

            StepVerifier.create(service.deleteProject(PROJET, PORTEUR)).verifyComplete();

            // Les quatre requetes du depot filtrent is_active = true : marquer
            // suffit a faire disparaitre le projet des lectures, et le nom
            // redevient disponible.
            assertThat(existant.getIsActive())
                    .as("le projet reste actif : il continuera d'apparaitre dans les "
                            + "listes et son nom restera pris")
                    .isFalse();
            verify(depot, never()).delete(any());
            verify(depot, never()).deleteById(any(UUID.class));
            verify(metriques).incrementProjectDeleted(PORTEUR.toString());
        }

        /**
         * L'événement porte le {@code userId} : sans lui, quotas-service ne
         * sait pas à qui restituer le quota libéré.
         */
        @Test
        @DisplayName("l'evenement de suppression est publie")
        void evenementPublie() {
            Project existant = projet(PORTEUR);
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.just(existant));
            when(depot.save(any())).thenReturn(Mono.just(existant));

            service.deleteProject(PROJET, PORTEUR).block();

            // timeout : la publication part sur boundedElastic, detachee de la
            // requete, donc elle peut aboutir apres le retour du bloc.
            verify(kafka, timeout(2000)).send(anyString(), eq(PROJET.toString()), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un envoi Kafka perdu devient COMPTABLE")
    class EnvoiKafka {

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void poserUnEnvoiQuiEchoue() {
            CompletableFuture echoue = new CompletableFuture();
            echoue.completeExceptionally(new IllegalStateException("courtier injoignable"));
            when(kafka.send(anyString(), anyString(), any())).thenReturn(echoue);
        }

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>{@code send()} rend un {@code CompletableFuture} et ne lève rien
         * si le courtier refuse. Le résultat était ignoré : l'échec arrivait
         * après le retour de la méthode, donc rien ne le voyait et
         * {@code interface.kafka.event.failed.total} restait à zéro.
         */
        @Test
        @DisplayName("un envoi qui echoue incremente le compteur d'echec")
        void echecCompte() {
            poserUnEnvoiQuiEchoue();

            Project existant = projet(PORTEUR);
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.just(existant));
            when(depot.save(any())).thenReturn(Mono.just(existant));

            service.deleteProject(PROJET, PORTEUR).block();

            verify(metriques, timeout(2000))
                    .incrementKafkaEventFailed("ProjectDeleted", "IllegalStateException");
        }

        /**
         * Et la requête aboutit quand même.
         *
         * <p>Choix assumé : faire échouer la suppression parce que Kafka est
         * indisponible serait pire. Le compteur rend la divergence visible, il
         * ne la supprime pas.
         */
        @Test
        @DisplayName("un envoi qui echoue ne fait PAS echouer la requete")
        void echecNeCassePasLaRequete() {
            poserUnEnvoiQuiEchoue();

            Project existant = projet(PORTEUR);
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.just(existant));
            when(depot.save(any())).thenReturn(Mono.just(existant));

            StepVerifier.create(service.deleteProject(PROJET, PORTEUR)).verifyComplete();
        }

        @Test
        @DisplayName("un envoi qui aboutit incremente le compteur d'envoi")
        void succesCompte() {
            Project existant = projet(PORTEUR);
            when(depot.findByIdAndUserId(PROJET, PORTEUR)).thenReturn(Mono.just(existant));
            when(depot.save(any())).thenReturn(Mono.just(existant));

            service.deleteProject(PROJET, PORTEUR).block();

            verify(metriques, timeout(2000)).incrementKafkaEventSent("ProjectDeleted");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Lectures et delegation")
    class Lectures {

        @Test
        @DisplayName("la liste ne rend que les projets du porteur")
        void listeDuPorteur() {
            ProjectResponse reponse = reponseProjet();
            when(depot.findByUserId(PORTEUR)).thenReturn(Flux.just(projet(PORTEUR)));
            when(mappeur.toResponse(any())).thenReturn(reponse);

            StepVerifier.create(service.findUserProjects(PORTEUR))
                    .expectNext(reponse)
                    .verifyComplete();

            verify(depot).findByUserId(PORTEUR);
        }

        /**
         * Le droit de créer est décidé par quotas-service, pas ici.
         *
         * <p>L'ancienne implémentation comptait les projets en base et comparait
         * à {@code MAX_PROJECTS_PER_USER = 10}, identique pour tous quel que
         * soit le palier commercial.
         */
        @Test
        @DisplayName("le droit de creer est delegue a quotas-service")
        void droitDelegue() {
            when(quotas.canCreateProject(PORTEUR)).thenReturn(Mono.just(true));

            StepVerifier.create(service.canCreateProject(PORTEUR))
                    .expectNext(true)
                    .verifyComplete();

            verify(quotas).canCreateProject(PORTEUR);
            verify(depot, never()).countByUserId(any());
        }
    }
}
