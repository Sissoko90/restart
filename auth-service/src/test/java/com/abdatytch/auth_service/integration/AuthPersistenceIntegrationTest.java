package com.abdatytch.auth_service.integration;

import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.entities.UserRoleAssignment;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.repository.UserRoleAssignmentRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**

 * Premier test d'intégration de la plateforme : PostgreSQL réel, Flyway réel,
 * mapping R2DBC réel.
 *
 * <h2>Ce que ce test couvre et que rien d'autre ne couvrait</h2>
 *
 * Tous les tests existants mockent les repositories et désactivent la base. Ils
 * valident donc la logique métier, mais <b>jamais</b> :
 * <ul>
 * <li>que les migrations Flyway s'appliquent réellement ;</li>
 * <li>que le mapping {@code @Column} des entités correspond au schéma —
 * une colonne renommée d'un côté seulement ne casse aucun test unitaire ;</li>
 * <li>que le SQL natif de {@link UserRoleAssignmentRepository} est valide —
 * une requête syntaxiquement fausse ne se révèle qu'à l'exécution ;</li>
 * <li>le piège {@code Persistable#isNew} de la migration vers R2DBC.</li>
 * </ul>
 *
 * Autrement dit, la persistance n'était vérifiée qu'au premier démarrage en
 * environnement — c'est-à-dire par les utilisateurs.
 *
 * <p>Le test se désactive de lui-même si Docker n'est pas disponible, plutôt
 * que d'échouer sur un poste qui n'en dispose pas.
 */
@DataR2dbcTest
// R2dbcAuditingConfig porte les rappels d'audit et de détection « entité neuve » :
// la tranche @DataR2dbcTest ne les charge pas d'elle-même, or c'est précisément
// ce comportement que ces tests vérifient.
@Import({AuthPersistenceIntegrationTest.CacheStub.class,
         com.abdatytch.auth_service.config.R2dbcAuditingConfig.class})
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Persistance auth-service — schéma, mapping et SQL natif réels")
class AuthPersistenceIntegrationTest {

    /**

     * La classe principale porte {@code @EnableCaching}, mais la tranche
     * {@code @DataR2dbcTest} n'auto-configure aucun {@code CacheManager}. Un cache
     * en mémoire suffit : ce test porte sur la persistance, pas sur la mise en
     * cache.
     */
    @TestConfiguration
    static class CacheStub {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }
    }

    // La version d'API Docker est imposée par `api.version` dans le POM parent,
    // et NON ici.
    //
    // Docker 29 refuse les versions antérieures à 1.44 : il répond HTTP 400 sur
    // /info, et Testcontainers en conclut « Could not find a valid Docker
    // environment » — un diagnostic qui oriente vers une fausse piste, puisque
    // le démon répond et refuse seulement la version négociée.
    //
    // Un bloc statique posait la propriété ici. Il arrivait TROP TARD :
    // l'extension Testcontainers évalue sa condition d'exécution avant
    // l'initialisation statique de la classe, la réflexion sur les champs
    // @Container ne la déclenchant pas. Résultat, cette classe entière était
    // sautée — 7 tests avec elle — et le build restait VERT. La persistance
    // R2DBC n'était donc vérifiée par aucun test en cours d'exécution.

    /**
     * La même version majeure que celle réellement déployée.
     *
     * <p>Le conteneur était en {@code postgres:16-alpine} alors que
     * {@code docker-compose-minimal.yml} déploie {@code postgres:18.6-alpine}.
     * Un test de schéma qui valide contre une autre version majeure que la
     * production peut passer là où la vraie base échoue — et c'est précisément
     * ce que ce test existe pour écarter : il vérifie le schéma, le mapping et
     * du SQL natif, trois choses sensibles à la version du moteur.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.6-alpine");

    /**

     * Flyway applique le schéma en JDBC ; Spring Data lit et écrit en R2DBC. Les
     * deux visent la même base, mais par des pilotes différents — d'où les deux
     * jeux de coordonnées.
     *
     * <p>La migration est jouée par Flyway lui-même, et non par un script
     * d'initialisation du conteneur : c'est le fichier de production qui doit
     * être validé, tel que Flyway l'interprète.
     */
    @BeforeAll
    static void appliquerLesMigrations() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void r2dbcProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName()));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        // La tranche @DataR2dbcTest ne doit dépendre ni du config-server ni de Consul.
        registry.add("spring.cloud.config.enabled", () -> "false");
        registry.add("spring.cloud.consul.enabled", () -> "false");
        registry.add("spring.config.import", () -> "");

        // L'AUTOCONFIGURATION Flyway est coupée ici, et les migrations sont
        // jouées par appliquerLesMigrations() ci-dessus.
        //
        // Ce n'est pas un contournement : `spring.flyway.url` désigne la base de
        // DÉVELOPPEMENT, pas le conteneur. Laissée active, l'autoconfiguration
        // échouait sur « password authentication failed for user postgres » et
        // emportait le contexte entier — sept tests en erreur.
        //
        // Le dispositif est donc partagé, et les deux moitiés sont nécessaires :
        //   - ici, les migrations RÉELLES sont appliquées à un vrai PostgreSQL,
        //     ce qui valide leur contenu ;
        //   - FlywayEstReellementActifTest vérifie que Spring Boot est en mesure
        //     de les jouer au démarrage, ce qu'un test qui désactive
        //     l'autoconfiguration ne peut par construction pas voir. C'est
        //     précisément l'angle mort qui avait laissé auth_service_db vide.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserRoleAssignmentRepository roleAssignmentRepository;

    private String uniqueSuffix;

    @BeforeEach
    void setUp() {
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        // Chaque test repart d'un état vide : `pasDeDoublon` compte les lignes.
        userRepository.deleteAll().then(roleAssignmentRepository.deleteAll()).block();
    }

    private User newUser() {
        User user = new User();
        user.setId(UUID.randomUUID().toString());
        user.setEmail("essai-" + uniqueSuffix + "@restart.test");
        user.setPhoneNumber("+223" + uniqueSuffix.replaceAll("\\D", "0").substring(0, 6) + "00");
        user.setCountryCode("ML");
        user.setFirstName("Awa");
        user.setLastName("Traoré");
        user.setPasswordHash("$2a$10$exemple");
        user.setRole(UserRole.USER);
        user.setCreatedAt(LocalDateTime.now());
        return user;
    }

    // ==================================================================
    @Nested
    @DisplayName("Schéma et mapping")
    class SchemaEtMapping {

        @Test
        @DisplayName("un utilisateur enregistré est relu avec tous ses champs")
        void allerRetourComplet() {
            User user = newUser();

            StepVerifier.create(userRepository.save(user).then(userRepository.findById(user.getId())))
                    .assertNext(relu -> {
                        // Chaque assertion vérifie une correspondance @Column  colonne.
                        // Un décalage de nom ne se voit dans aucun test unitaire.
                        assertThat(relu.getEmail()).isEqualTo(user.getEmail());
                        assertThat(relu.getPhoneNumber()).isEqualTo(user.getPhoneNumber());
                        assertThat(relu.getCountryCode()).isEqualTo("ML");
                        assertThat(relu.getFirstName()).isEqualTo("Awa");
                        assertThat(relu.getLastName()).isEqualTo("Traoré");
                        assertThat(relu.getPasswordHash()).isEqualTo("$2a$10$exemple");
                        assertThat(relu.getRole()).isEqualTo(UserRole.USER);
                        assertThat(relu.getIsActive()).isTrue();
                        assertThat(relu.getIsLocked()).isFalse();
                        assertThat(relu.getFailedLoginAttempts()).isZero();
                        assertThat(relu.getCreatedAt()).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("les accents sont préservés (encodage de la colonne)")
        void encodageUtf8() {
            User user = newUser();
            user.setLastName("Traoré-Diallo");
            user.setFirstName("Aïssata");

            StepVerifier.create(userRepository.save(user).then(userRepository.findById(user.getId())))
                    .assertNext(relu -> {
                        assertThat(relu.getLastName()).isEqualTo("Traoré-Diallo");
                        assertThat(relu.getFirstName()).isEqualTo("Aïssata");
                    })
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Piège Persistable#isNew de la migration R2DBC")
    class PersistableIsNew {

        @Test
        @DisplayName("une seconde sauvegarde MET À JOUR, elle n'insère pas et n'est pas ignorée")
        void miseAJourEffective() {
            User user = newUser();

            // En R2DBC, l'identifiant est fourni par l'application : Spring Data ne
            // peut pas déduire seul si l'entité est nouvelle. Si `isNew()` répond
            // toujours `false`, l'insertion initiale devient un UPDATE sans ligne
            // correspondante — `save()` est alors SILENCIEUSEMENT sans effet.
            // S'il répond toujours `true`, la mise à jour devient une insertion en
            // double et viole la contrainte d'unicité.
            StepVerifier.create(
                            userRepository.save(user)
                                    .flatMap(saved -> {
                                        saved.setFirstName("Fatoumata");
                                        return userRepository.save(saved);
                                    })
                                    .then(userRepository.findById(user.getId())))
                    .assertNext(relu -> assertThat(relu.getFirstName())
                            .withFailMessage("la seconde sauvegarde n'a pas pris effet : "
                    + "isNew() ne bascule pas après la première insertion")
                            .isEqualTo("Fatoumata"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("une mise à jour ne crée pas de doublon")
        void pasDeDoublon() {
            User user = newUser();

            StepVerifier.create(
                            userRepository.save(user)
                                    .flatMap(saved -> {
                                        saved.setLastName("Modifié");
                                        return userRepository.save(saved);
                                    })
                                    .then(userRepository.count()))
                    .assertNext(total -> assertThat(total).isEqualTo(1L))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Requêtes SQL natives")
    class RequetesNatives {

        private UserRoleAssignment assignment(String userId, UserRole role, boolean active) {
            UserRoleAssignment assignment = new UserRoleAssignment();
            assignment.setId(UUID.randomUUID().toString());
            assignment.setUserId(userId);
            assignment.setEmail("essai-" + uniqueSuffix + "-" + role.name() + "@restart.test");
            assignment.setRole(role);
            assignment.setIsActive(active);
            assignment.setCreatedAt(LocalDateTime.now());
            return assignment;
        }

        @Test
        @DisplayName("findActiveRolesByUserId ne retourne que les affectations actives")
        void seulesLesAffectationsActives() {
            String userId = UUID.randomUUID().toString();

            // Le SQL de cette requête a été réécrit lors du passage de JPQL à
            // R2DBC. Une erreur de syntaxe ou de nom de colonne ne se révélerait
            // qu'à l'exécution — et une affectation révoquée qui resterait
            // retournée accorderait un rôle qu'on croit avoir retiré.
            StepVerifier.create(
                            roleAssignmentRepository.save(assignment(userId, UserRole.USER, true))
                                    .then(roleAssignmentRepository.save(assignment(userId, UserRole.ADMIN, false)))
                                    .thenMany(roleAssignmentRepository.findActiveRolesByUserId(userId))
                                    .collectList())
                    .assertNext(actifs -> {
                        assertThat(actifs).hasSize(1);
                        assertThat(actifs.get(0).getRole()).isEqualTo(UserRole.USER);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("un utilisateur sans affectation active donne un résultat vide, pas une erreur")
        void aucuneAffectation() {
            StepVerifier.create(roleAssignmentRepository
                            .findActiveRolesByUserId(UUID.randomUUID().toString()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("les recherches dérivées du nom de méthode s'exécutent réellement")
        void requetesDerivees() {
            User user = newUser();

            StepVerifier.create(userRepository.save(user)
                            .then(userRepository.findByEmail(user.getEmail())))
                    .assertNext(trouve -> assertThat(trouve.getId()).isEqualTo(user.getId()))
                    .verifyComplete();

            StepVerifier.create(userRepository.findByPhoneNumber(user.getPhoneNumber()))
                    .assertNext(trouve -> assertThat(trouve.getId()).isEqualTo(user.getId()))
                    .verifyComplete();
        }
    }
}
