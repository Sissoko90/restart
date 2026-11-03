package com.abdatytch.user_service;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.client.ClientIdentiteAuth;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.mapper.UserMapper;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.impl.UserServiceReactiveImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.NonBlocking;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Vérifie qu'aucun appel bloquant ne s'exécute sur un thread non bloquant.
 *
 * <h2>La garde était affirmée, elle n'existait pas</h2>
 *
 * {@code UserMapper} et {@code JaugesDesComptes} déclarent tous deux en javadoc
 * que la politique non bloquante du projet est « vérifiée par BlockHound ». Les
 * deux seules occurrences du mot dans ce module étaient <b>ces phrases</b> :
 * quatre autres modules avaient un tel test, pas celui-ci — alors que
 * {@code blockhound} y figure en portée test depuis l'origine. Une dépendance
 * déclarée, jamais utilisée, et une propriété annoncée que rien ne mesurait.
 *
 * <p>Et c'est le module où le blocage a réellement eu lieu : {@code UserMapper}
 * appelait Feign <b>en bloquant depuis un {@code .map()}</b>, donc sur le fil de
 * l'event loop, sur le chemin de chaque réponse utilisateur. Rien ne l'a
 * signalé, parce que les tests du service <b>simulent</b> le mapper — le seul
 * endroit où l'appel avait lieu n'était jamais exécuté sous surveillance.
 *
 * <h2>Ce que ce test prouve, et ce qu'il ne prouve pas</h2>
 *
 * Il ne prouve pas que la migration vers {@code WebClient} était nécessaire :
 * l'étape intermédiaire — Feign isolé sur {@code boundedElastic} — aurait passé
 * ce test, {@code boundedElastic} étant précisément l'ordonnanceur où il est
 * <b>permis</b> de bloquer.
 *
 * <p>Ce qu'il prouve est plus utile à long terme : qu'aucun {@code .block()},
 * aucune lecture de fichier, aucun verrou et aucun appel JDBC ne revienne sur le
 * chemin de requête. C'est la régression que personne ne voit à la relecture.
 *
 * <h2>Pourquoi les collaborateurs sont réels</h2>
 *
 * {@code UserMapper}, {@code CacheDesComptes}, {@code ClientIdentiteAuth} et
 * {@code UserMetrics} sont de vraies instances. BlockHound ne détecte un appel
 * bloquant que dans du code réellement exécuté : avec des bouchons, ce test
 * resterait vert quelle que soit l'implémentation — c'est exactement le défaut
 * qui a permis au blocage de vivre.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final String AUTH_USER_ID = "auth-123";

    private static final String IDENTITE = """
            {"id":"auth-123",
             "email":"awa.diallo@example.ml",
             "phoneNumber":"+22370000000",
             "countryCode":"ML",
             "firstName":"Awa",
             "lastName":"Diallo",
             "role":"USER",
             "isActive":true,
             "isPhoneVerified":true,
             "isEmailVerified":false,
             "lastLogin":"2026-10-30T14:05:00",
             "createdAt":"2026-01-02T09:00:00"}
            """;

    /**
     * Remet les ordonnanceurs à neuf, <b>puis</b> installe BlockHound.
     *
     * <h3>Le défaut que ces deux lignes ferment</h3>
     *
     * Reactor met l'ordonnanceur {@code parallel} en cache pour toute la JVM, à
     * sa première utilisation. {@code StepVerifier.withVirtualTime} remplace la
     * fabrique globale d'ordonnanceurs pendant la durée d'une vérification. Si
     * la première utilisation de {@code parallel()} tombe dans l'une de ces
     * fenêtres, le cache retient un ordonnanceur dont les fils sont de simples
     * {@code Thread}, sans le marqueur {@code NonBlocking} sur lequel repose
     * toute la détection — et le cache survit ensuite à la remise en place de la
     * fabrique.
     *
     * <p>Le contrôle négatif échouait alors de façon <b>intermittente</b> dans
     * les autres modules, et les vrais tests passaient <b>pour la mauvaise
     * raison</b>, en ne vérifiant plus rien. {@code shutdownNow()} vide ce
     * cache ; les ordonnanceurs suivants sont créés par la vraie fabrique,
     * quelle que soit l'histoire de la JVM avant cette classe. Les classes
     * précédentes ont fini — les tests s'exécutent en séquence — donc rien
     * d'utile n'est interrompu.
     */
    @BeforeAll
    static void installerBlockHound() {
        Schedulers.shutdownNow();
        BlockHound.install();
    }

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private ReactiveRedisTemplate<String, Object> redis;
    @Mock
    private ReactiveValueOperations<String, Object> valeurs;

    private UserServiceReactiveImpl service;
    private UUID compteId;

    @BeforeEach
    void setUp() {
        compteId = UUID.randomUUID();

        User compte = new User();
        compte.setId(compteId);
        compte.setAuthUserId(AUTH_USER_ID);
        compte.setStatus(UserStatus.ACTIVE);
        compte.setDateOfBirth(LocalDate.of(1995, 3, 12));

        UserProfile profil = new UserProfile();
        profil.setUserId(compteId);
        profil.setBio("Commercante a Bamako");
        profil.setAddress("Rue 12, Badalabougou");
        profil.setPreferences(Map.of("langue", "fr"));

        when(userRepository.findByAuthUserId(AUTH_USER_ID)).thenReturn(Mono.just(compte));
        when(userRepository.findAllActive(anyInt(), anyInt())).thenReturn(Flux.just(compte));
        when(userProfileRepository.findByUserId(compteId)).thenReturn(Mono.just(profil));
        when(userProfileRepository.findAllByUserIdIn(any())).thenReturn(Flux.just(profil));

        // Cache vide : le chemin complet est parcouru, y compris l'appel
        // d'identite. Un cache qui repondrait court-circuiterait justement ce
        // qu'il faut surveiller.
        when(redis.opsForValue()).thenReturn(valeurs);
        when(valeurs.get(anyString())).thenReturn(Mono.empty());
        when(valeurs.set(anyString(), any(), any(Duration.class))).thenReturn(Mono.just(true));

        UserMetrics metriques = new UserMetrics(new SimpleMeterRegistry());
        CacheDesComptes cache = new CacheDesComptes(redis, metriques);
        UserMapper mapper = new UserMapper(clientIdentite(), userProfileRepository);

        service = new UserServiceReactiveImpl(
                userRepository, userProfileRepository, mapper, cache, metriques);

        rechauffer();
    }

    /**
     * Charge les classes des deux chemins <b>avant</b> la mesure.
     *
     * <h3>Pourquoi c'est indispensable, et pas une complaisance</h3>
     *
     * Sans cela, BlockHound signale un {@code RandomAccessFile#readBytes} levé
     * pendant une <b>initialisation de classe</b> : le chargeur de classes lit
     * un jar sur le disque, ce qui est bel et bien un appel bloquant. Mais c'est
     * un artefact du démarrage de la JVM, et non un défaut du code mesuré — en
     * production il a lieu une fois, à la première requête, et aucune écriture
     * de notre part ne peut l'éviter.
     *
     * <p>Pire que de faire échouer le test, il le rendait <b>dépendant de
     * l'ordre d'exécution</b> : lancé seul il échouait, lancé après les autres
     * classes de la suite il passait, les classes étant déjà chargées. C'est la
     * même famille de piège que l'ordonnanceur mis en cache traité dans
     * {@link #installerBlockHound()} — un test qui change de verdict selon ce
     * qui l'a précédé ne mesure rien de fiable.
     *
     * <p>L'appel se fait sur le fil appelant, qui ne porte pas le marqueur
     * {@code NonBlocking} : BlockHound le laisse donc passer, et les classes
     * sont initialisées pour de bon. Une classe dont l'initialisation échoue
     * reste définitivement cassée pour la JVM entière — raison supplémentaire de
     * la charger ici plutôt que sous surveillance.
     */
    private void rechauffer() {
        service.getUserByAuthUserId(AUTH_USER_ID).block(Duration.ofSeconds(10));
        service.getAllUsers(20, 0).collectList().block(Duration.ofSeconds(10));
    }

    /**
     * Un vrai {@code ClientIdentiteAuth}, servi par un échange en mémoire.
     *
     * <p>Pas un bouchon : c'est la classe qui portait l'appel bloquant, donc
     * celle qu'il faut faire exécuter. L'échange est simulé pour ne pas ouvrir
     * de port, mais tout le reste — en-têtes, URI, décodage, mise en cache — est
     * le code de production.
     */
    private ClientIdentiteAuth clientIdentite() {
        return new ClientIdentiteAuth(
                WebClient.builder().exchangeFunction(requete ->
                        Mono.just(ClientResponse.create(HttpStatus.OK)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(IDENTITE)
                                .build())),
                redis, "secret-de-test", 3000, 5000);
    }

    @Test
    @DisplayName("BlockHound est bien actif : un Thread.sleep sur un thread reactif est detecte")
    void blockHoundEstActif() {
        // Controle negatif : sans lui, un test « aucun blocage detecte » ne
        // prouverait rien — il pourrait simplement signifier que BlockHound
        // n'est pas installe.
        //
        // La condition PREALABLE est verifiee a part : BlockHound ne regarde que
        // les fils marques `NonBlocking`, et un ordonnanceur mis en cache
        // pendant une fenetre `withVirtualTime` n'en produit pas. Sans cette
        // assertion, ce cas se presenterait comme « BlockHound n'est pas actif »,
        // ce qui envoie chercher le defaut du mauvais cote : l'instrumentation
        // marche, c'est le fil qui n'est pas reactif.
        Mono<Boolean> filReactif = Mono
                .fromCallable(() -> Thread.currentThread() instanceof NonBlocking)
                .subscribeOn(Schedulers.parallel());

        assertThat(filReactif.block(Duration.ofSeconds(5)))
                .withFailMessage("les fils de Schedulers.parallel() ne portent pas le "
                        + "marqueur NonBlocking : BlockHound ne peut RIEN detecter sur "
                        + "cet ordonnanceur, et les tests de non-blocage de cette classe "
                        + "passeraient sans rien verifier. Cause connue : l'ordonnanceur "
                        + "a ete mis en cache pendant une fenetre withVirtualTime, qui "
                        + "remplace la fabrique globale.")
                .isTrue();

        Mono<Long> bloquant = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1L;
        }).subscribeOn(Schedulers.parallel());

        assertThatThrownBy(() -> bloquant.block(Duration.ofSeconds(5)))
                .hasRootCauseInstanceOf(BlockingOperationError.class);
    }

    /**
     * Le chemin de {@code GET /users/me}.
     *
     * <p>Le plus sollicité du service, et celui où le blocage vivait : lecture
     * du cache, lecture en base, appel d'identité, chargement du profil,
     * écriture du cache.
     */
    @Test
    @DisplayName("le chemin de GET /users/me ne bloque aucun thread reactif")
    void cheminDuProfilPersonnelNeBloquePas() throws Exception {
        CompletableFuture<Object> resultat = new CompletableFuture<>();

        // Execute sur `parallel` : c'est l'ordonnanceur que BlockHound
        // surveille, au meme titre que l'EventLoop Netty en production.
        service.getUserByAuthUserId(AUTH_USER_ID)
                .subscribeOn(Schedulers.parallel())
                .subscribe(resultat::complete, resultat::completeExceptionally,
                        () -> resultat.complete(null));

        // Une BlockingOperationError remonterait ici comme cause de l'echec.
        assertThat(resultat.get(10, TimeUnit.SECONDS)).isNotNull();
    }

    /**
     * Le chemin d'une liste paginée.
     *
     * <p>Distinct du précédent, et c'est pourquoi il est testé à part : c'est là
     * que {@code toDetailDTOs} charge les profils par lot et mène plusieurs
     * appels d'identité de front. Un blocage y coûte autant de fils qu'il y a
     * d'enrichissements simultanés, et non un seul.
     */
    @Test
    @DisplayName("le chemin d'une liste paginee ne bloque aucun thread reactif")
    void cheminDeListeNeBloquePas() throws Exception {
        CompletableFuture<Object> resultat = new CompletableFuture<>();

        service.getAllUsers(20, 0)
                .collectList()
                .subscribeOn(Schedulers.parallel())
                .subscribe(resultat::complete, resultat::completeExceptionally,
                        () -> resultat.complete(null));

        assertThat(resultat.get(10, TimeUnit.SECONDS)).isNotNull();
    }
}
