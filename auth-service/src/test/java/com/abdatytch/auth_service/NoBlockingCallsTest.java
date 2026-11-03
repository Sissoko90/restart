package com.abdatytch.auth_service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.AuthResponseMapper;
import com.abdatytch.auth_service.mappers.VerificationResponseMapper;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.*;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl;
import com.abdatytch.auth_service.service.impl.AuthServiceImpl;
import com.abdatytch.auth_service.client.GatewayGeoLocationClient;
import com.abdatytch.auth_service.dto.request.LoginRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.NonBlocking;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;
import static com.abdatytch.auth_service.testutil.ConfianceDEssai.aucunAppareilDeConfiance;

/**
 * Vérifie qu'aucun appel bloquant ne s'exécute sur un thread non bloquant.
 *
 * <p>BlockHound instrumente la JVM : tout appel bloquant (I/O, {@code Thread.sleep},
 * {@code Mono.block()}, verrou...) effectué depuis un thread réactif lève une
 * {@link BlockingOperationError}. C'est ce qui rend la propriété « service non
 * bloquant » <b>vérifiable</b> plutôt qu'affirmée.
 *
 * <p>Le chemin testé ici est celui de la connexion — le plus sollicité, et celui où
 * un blocage coûte le plus cher : l'EventLoop Netty est partagé par toutes les
 * requêtes en vol.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Absence d'appels bloquants (BlockHound)")
class NoBlockingCallsTest {

    private static final String PHONE = "+22300000000";
    private static final String PASSWORD = "SecurePass123!";
    private static final String HASH = "$2a$10$hash";

    /**
     * Remet les ordonnanceurs à neuf, <b>puis</b> installe BlockHound.
     *
     * <h3>Le défaut que ces deux lignes ferment</h3>
     *
     * Ce contrôle négatif échouait de façon <b>intermittente</b> : deux
     * exécutions de la suite complète sur trois, {@code Thread.sleep} sur
     * {@code Schedulers.parallel()} n'était pas détecté — et les deux vrais
     * tests de cette classe passaient alors <b>pour la mauvaise raison</b>, en
     * ne vérifiant plus rien. Lancée seule, la classe passait toujours.
     *
     * <p>Reactor met l'ordonnanceur {@code parallel} en cache pour toute la
     * JVM, à sa première utilisation. {@code StepVerifier.withVirtualTime}
     * remplace la fabrique globale d'ordonnanceurs pendant la durée d'une
     * vérification — et ce module s'en sert à huit endroits. Si la première
     * utilisation de {@code parallel()} tombe dans l'une de ces fenêtres, le
     * cache retient un ordonnanceur dont les fils sont de simples
     * {@code Thread}, sans le marqueur {@code NonBlocking} sur lequel repose
     * toute la détection. Le cache survit ensuite à la remise en place de la
     * fabrique.
     *
     * <p>C'est une course, d'où l'intermittence : elle dépend du moment où un
     * abonnement d'un autre test touche {@code parallel()} pour la première
     * fois.
     *
     * <p>{@code shutdownNow()} vide ce cache. Les ordonnanceurs suivants sont
     * donc créés par la vraie fabrique, quelle que soit l'histoire de la JVM
     * avant cette classe. Les classes précédentes ont fini — les tests
     * s'exécutent en séquence — donc rien d'utile n'est interrompu.
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

    @Mock private UserRepository userRepository;
    @Mock private IVerificationCodeService verificationCodeService;
    @Mock private IJwtService jwtService;
    @Mock private IRefreshTokenService refreshTokenService;
    @Mock private IAuthenticationLogService authenticationLogService;
    @Mock private AuthResponseMapper authResponseMapper;
    @Mock private VerificationResponseMapper verificationResponseMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private IEventPublisher eventPublisher;
    @Mock private GatewayGeoLocationClient geoLocationClient;
    @Mock private AuthMetrics authMetrics;

    @Test
    @DisplayName("BlockHound est bien actif : un Thread.sleep sur un thread réactif est détecté")
    void blockHoundDetectsBlockingCalls() {
        // Contrôle négatif : sans lui, un test « aucun blocage détecté » ne prouverait
        // rien — il pourrait simplement signifier que BlockHound n'est pas installé.
        //
        // La condition PREALABLE est vérifiée à part : BlockHound ne regarde que
        // les fils marqués `NonBlocking`, et un ordonnanceur mis en cache
        // pendant une fenêtre `withVirtualTime` n'en produit pas. Sans cette
        // assertion, ce cas se présentait comme « BlockHound n'est pas actif »,
        // ce qui envoie chercher le défaut du mauvais côté — l'instrumentation
        // marchait parfaitement, c'est le fil qui n'était pas réactif.
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

        Mono<Long> blocking = Mono.fromCallable(() -> {
            Thread.sleep(10);
            return 1L;
        }).subscribeOn(Schedulers.parallel());

        assertThatThrownBy(() -> blocking.block(Duration.ofSeconds(5)))
                .hasRootCauseInstanceOf(BlockingOperationError.class);
    }

    @Test
    @DisplayName("le chemin de connexion ne bloque aucun thread réactif")
    void loginPathDoesNotBlock() throws Exception {
        User user = User.builder()
                .phoneNumber(PHONE)
                .passwordHash(HASH)
                .role(UserRole.USER)
                .isPhoneVerified(true)
                .isActive(true)
                .isLocked(false)
                .failedLoginAttempts(0)
                .build();
        user.setId("user-1");

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(geoLocationClient.getGeoLocation(anyString())).thenReturn(Mono.empty());
        when(authenticationLogService.logAuthenticationEvent(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(jwtService.generateToken(any(User.class))).thenReturn(Mono.just("token"));
        when(refreshTokenService.createRefreshToken(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new com.abdatytch.auth_service.entities.RefreshToken()));
        when(authResponseMapper.toAuthResponse(anyString(),
                any(com.abdatytch.auth_service.entities.RefreshToken.class), any(Long.class), any(User.class)))
                .thenReturn(new com.abdatytch.auth_service.dto.response.AuthResponse());

        AuthServiceImpl authService = new AuthServiceImpl(
                userRepository, verificationCodeService, jwtService, refreshTokenService,
                authenticationLogService, authResponseMapper, verificationResponseMapper,
                motDePasseReel(), eventPublisher, geoLocationClient, authMetrics,
                serviceDeVerrouillage(), smsServiceSimule(), secondFacteurReel(),
                aucunAppareilDeConfiance(),
                com.abdatytch.auth_service.testutil.AlertesDEssai.aucuneAlerte());

        LoginRequest request = new LoginRequest();
        request.setPhoneNumber(PHONE);
        request.setPassword(PASSWORD);

        // Exécuté sur le scheduler `parallel` : c'est celui que BlockHound surveille,
        // au même titre que l'EventLoop Netty en production.
        CompletableFuture<Object> result = new CompletableFuture<>();
        authService.login(request, "41.223.10.5", "Mozilla/5.0")
                .subscribeOn(Schedulers.parallel())
                .subscribe(result::complete, result::completeExceptionally, () -> result.complete(null));

        // Une BlockingOperationError remonterait ici comme cause de l'échec
        result.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("un échec de connexion ne bloque pas non plus (chemin d'erreur)")
    void failedLoginPathDoesNotBlock() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.empty());
        when(authenticationLogService.logAuthenticationEvent(
                any(), any(), any(), any(), any(), any(), anyString())).thenReturn(Mono.empty());

        AuthServiceImpl authService = new AuthServiceImpl(
                userRepository, verificationCodeService, jwtService, refreshTokenService,
                authenticationLogService, authResponseMapper, verificationResponseMapper,
                motDePasseReel(), eventPublisher, geoLocationClient, authMetrics,
                serviceDeVerrouillage(), smsServiceSimule(), secondFacteurReel(),
                aucunAppareilDeConfiance(),
                com.abdatytch.auth_service.testutil.AlertesDEssai.aucuneAlerte());

        LoginRequest request = new LoginRequest();
        request.setPhoneNumber(PHONE);
        request.setPassword(PASSWORD);

        assertThatThrownBy(() -> authService.login(request, "41.223.10.5", "Mozilla/5.0")
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(AuthException.class)
                .satisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    /**
     * Le VRAI service de verrouillage, sur le meme depot simule.
     *
     * <p>Un bouchon masquerait precisement ce qu'il faut verifier : la regle de
     * verrouillage a desormais un seul detenteur, et les chemins de connexion y
     * deleguent. Avec un mock, le comptage des echecs et le refus d'un compte
     * verrouille ne seraient plus exerces du tout — le defaut corrige etait
     * justement deux implementations qui ne se rencontraient jamais.
     *
     * <p>Les seuils sont poses par reflexion : ils viennent de {@code @Value},
     * qu'aucune construction manuelle ne renseigne.
     */
    /**
     * Le VRAI service de second facteur, et non une simulation.
     *
     * <p>C'est tout l'objet de cette suite : BlockHound ne detecte un appel
     * bloquant que dans du code reellement execute. Un bouchon rendrait le test
     * vert quelle que soit l'implementation.
     *
     * <p>{@code TotpService} est en revanche simule : les comptes de ce test
     * n'ont pas de second facteur enrole, donc il n'est jamais appele. S'il
     * l'etait, l'absence de stub le ferait echouer de façon visible plutot que
     * de passer en silence.
     */
    private SecondFacteurServiceImpl secondFacteurReel() {
        return new SecondFacteurServiceImpl(
                userRepository,
                org.mockito.Mockito.mock(com.abdatytch.commons.totp.TotpService.class),
                serviceDeVerrouillage(),
                com.abdatytch.auth_service.testutil.CodesDeSecoursDEssai
                        .aucunCodeDeSecours(),
                metriques(), "AbdaTech Test");
    }

    private AccountLockoutServiceImpl serviceDeVerrouillage() {
        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(userRepository, metriques(), revocationQuiNeFaitRien());
        org.springframework.test.util.ReflectionTestUtils.setField(
                verrouillage, "maxAttempts", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(
                verrouillage, "lockoutDurationMinutes", 30);
        return verrouillage;
    }

    /** Service SMS simule : l'envoi est ACTIF, formulation nominale. */
    private com.abdatytch.auth_service.service.ISmsService smsServiceSimule() {
        return new com.abdatytch.auth_service.service.ISmsService() {
            @Override
            public reactor.core.publisher.Mono<Void> sendSms(String numero, String message) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public boolean envoiActif() {
                return true;
            }
        };
    }

    /**
     * Un VRAI {@code AuthMetrics} sur un registre en memoire.
     *
     * <p>Pas un bouchon : les compteurs de verrouillage et de second facteur
     * sont poses chez les detenteurs de ces regles, et un mock ferait passer
     * pour instrumentes des chemins qui ne le sont pas. Avec un registre reel,
     * un test peut en outre LIRE la valeur produite.
     */
    private static com.abdatytch.auth_service.metrics.AuthMetrics metriques() {
        return new com.abdatytch.auth_service.metrics.AuthMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /**
     * Le VRAI service de mot de passe, enveloppant le {@code PasswordEncoder}
     * simule.
     *
     * <p>Pas un bouchon de {@code IMotDePasseService} : les stubs
     * {@code when(passwordEncoder.matches(...))} de ces suites continuent de
     * valoir, et le portage sur {@code boundedElastic} est reellement exerce.
     * Un bouchon rendrait le resultat sur le thread appelant et masquerait
     * tout defaut de changement de contexte.
     */
    private com.abdatytch.auth_service.service.IMotDePasseService motDePasseReel() {
        return new com.abdatytch.auth_service.service.impl.MotDePasseServiceImpl(passwordEncoder);
    }
}
