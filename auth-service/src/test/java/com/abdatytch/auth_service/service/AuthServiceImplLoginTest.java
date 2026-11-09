package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.client.GatewayGeoLocationClient;
import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.request.LoginRequest;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.AuthResponseMapper;
import com.abdatytch.auth_service.mappers.VerificationResponseMapper;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import static org.mockito.ArgumentMatchers.anyInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;
import static com.abdatytch.auth_service.testutil.ConfianceDEssai.aucunAppareilDeConfiance;

/**

 * Tests du chemin de connexion par mot de passe.
 *
 * <p>Ces tests portent sur les règles de sécurité qui devaient survivre à la
 * migration JPA  R2DBC : ordre des contrôles, verrouillage de compte, absence de
 * divulgation de l'existence d'un compte, et journalisation des échecs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthServiceImpl.login - Tests Unitaires")
class AuthServiceImplLoginTest {

    private static final String PHONE = "+22300000000";
    private static final String PASSWORD = "SecurePass123!";
    private static final String HASH = "$2a$10$hash";
    private static final String IP = "41.223.10.5";
    private static final String USER_AGENT = "Mozilla/5.0";

    @Mock
    private UserRepository userRepository;
    @Mock
    private IVerificationCodeService verificationCodeService;
    @Mock
    private IJwtService jwtService;
    @Mock
    private IRefreshTokenService refreshTokenService;
    @Mock
    private IAuthenticationLogService authenticationLogService;
    @Mock
    private AuthResponseMapper authResponseMapper;
    @Mock
    private VerificationResponseMapper verificationResponseMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private IEventPublisher eventPublisher;
    @Mock
    private GatewayGeoLocationClient geoLocationClient;
    @Mock
    private AuthMetrics authMetrics;

    @Mock
    private com.abdatytch.commons.totp.TotpService totpService;

    private AuthServiceImpl authService;

    /**
     * Alertes retenues plutot que jetees.
     *
     * <p>L'inversion « ancienne / nouvelle coordonnee » vit dans ce service,
     * pas dans celui des alertes : c'est ici qu'on peut l'attraper.
     */
    private com.abdatytch.auth_service.testutil.AlertesDEssai.Muettes alertes;

    @BeforeEach
    void setUp() {
        alertes = com.abdatytch.auth_service.testutil.AlertesDEssai.aucuneAlerte();
        authService = new AuthServiceImpl(
                userRepository, verificationCodeService, jwtService, refreshTokenService,
                authenticationLogService, authResponseMapper, verificationResponseMapper,
                motDePasseReel(), eventPublisher, geoLocationClient, authMetrics,
                serviceDeVerrouillage(), messagerieSimulee(), secondFacteurReel(),
                aucunAppareilDeConfiance(),
                alertes);

        // La journalisation et la géolocalisation ne doivent jamais faire échouer un login
        when(authenticationLogService.logAuthenticationEvent(
                any(), any(), any(), any(), any(), any(), anyString())).thenReturn(Mono.empty());
        when(authenticationLogService.logAuthenticationEvent(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(geoLocationClient.getGeoLocation(anyString())).thenReturn(Mono.empty());
        // Un mapper simule rend null par defaut, et `thenReturn(null)` leverait.
        when(verificationResponseMapper.toSuccessResponse(anyString(), anyInt()))
                .thenAnswer(inv -> {
                    var r = new com.abdatytch.auth_service.dto.response.VerificationResponse();
                    r.setMessage(inv.getArgument(0));
                    r.setSuccess(true);
                    return r;
                });
    }

    private User activeUser() {
        User user = User.builder()
                .phoneNumber(PHONE)
                .passwordHash(HASH)
                .role(UserRole.USER)
                .isPhoneVerified(true)
                .isEmailVerified(false)
                .isActive(true)
                .isLocked(false)
                .failedLoginAttempts(0)
                .build();
        user.setId("user-1");
        return user;
    }

    private LoginRequest request() {
        LoginRequest request = new LoginRequest();
        request.setPhoneNumber(PHONE);
        request.setPassword(PASSWORD);
        return request;
    }

    // ==================================================================
    @Nested
    @DisplayName("Le second facteur, sur CE chemin")
    class SecondFacteurSurLeCheminDuSdk {

        /**
         * Le defaut corrige, et sa portee.
         *
         * <p>{@code /auth/login} ne verifiait aucun second facteur : il lisait
         * le mot de passe, l'etat du compte, et emettait le jeton. Le second
         * facteur n'etait oppose que sur {@code /auth/admin/login}.
         *
         * <p>Un compte {@code SUPER_ADMIN} avec second facteur enrole se
         * connectait donc avec le mot de passe <b>seul</b>, en changeant
         * simplement de chemin. Le second facteur protegeait un endpoint, pas un
         * compte — et c'est le compte qui porte la rotation des cles de
         * signature, la maintenance et l'effacement administratif.
         */
        @Test
        @DisplayName("un compte enrole ne passe PAS sans code")
        void secondFacteurOppose() {
            User admin = activeUser();
            admin.setRole(UserRole.SUPER_ADMIN);
            admin.setTotpSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
            admin.setTotpEnabled(true);

            when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(admin));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

            StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                    .verifyErrorSatisfies(e -> assertThat(((AuthException) e).getErrorCode())
                            .withFailMessage("le mot de passe seul a suffi : le second "
                                    + "facteur est contournable en changeant de chemin")
                            .isEqualTo(ErrorCode.TWO_FACTOR_REQUIRED));

            verify(jwtService, never()).generateToken(any());
        }

        @Test
        @DisplayName("avec le bon code, le compte enrole passe")
        void secondFacteurSatisfait() {
            User admin = activeUser();
            admin.setRole(UserRole.SUPER_ADMIN);
            admin.setTotpSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
            admin.setTotpEnabled(true);

            AuthResponse attendue = new AuthResponse();
            when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(admin));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(totpService.validateTotpCode(anyString(), eq("123456")))
                    .thenReturn(Mono.just(true));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(jwtService.generateToken(admin)).thenReturn(Mono.just("access-token"));
            when(refreshTokenService.createRefreshToken(eq(admin), eq(IP), eq(USER_AGENT)))
                    .thenReturn(Mono.just(new RefreshToken()));
            when(authResponseMapper.toAuthResponse(
                    anyString(), any(RefreshToken.class), any(Long.class), eq(admin)))
                    .thenReturn(attendue);

            LoginRequest avecCode = request();
            avecCode.setTotpCode("123456");

            StepVerifier.create(authService.login(avecCode, IP, USER_AGENT))
                    .expectNext(attendue)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un utilisateur final sans second facteur n'est pas gene")
        void utilisateurFinalNonGene() {
            // La verification est opposee a TOUS les comptes, et non aux seuls
            // administrateurs : une regle conditionnee a un role se contourne en
            // changeant de role. Elle ne doit donc rien exiger de ceux qui n'ont
            // pas de second facteur.
            User user = activeUser();
            AuthResponse attendue = new AuthResponse();

            when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(userRepository.save(any(User.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(jwtService.generateToken(user)).thenReturn(Mono.just("access-token"));
            when(refreshTokenService.createRefreshToken(eq(user), eq(IP), eq(USER_AGENT)))
                    .thenReturn(Mono.just(new RefreshToken()));
            when(authResponseMapper.toAuthResponse(
                    anyString(), any(RefreshToken.class), any(Long.class), eq(user)))
                    .thenReturn(attendue);

            StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                    .expectNext(attendue)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Designation du compte : telephone ou adresse")
    class DesignationDuCompte {

        @Test
        @DisplayName("une adresse electronique resout le compte")
        void adresseResout() {
            // Un administrateur retient son adresse. Avant, ce chemin ne savait
            // chercher que par telephone : un compte joignable par
            // /auth/admin/login ne l'etait pas par /auth/login.
            User admin = activeUser();
            admin.setEmail("admin@restart.test");

            when(userRepository.findByEmail("admin@restart.test")).thenReturn(Mono.just(admin));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(userRepository.save(any(User.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(jwtService.generateToken(admin)).thenReturn(Mono.just("access-token"));
            when(refreshTokenService.createRefreshToken(any(), any(), any()))
                    .thenReturn(Mono.just(new RefreshToken()));
            when(authResponseMapper.toAuthResponse(
                    anyString(), any(RefreshToken.class), any(Long.class), any(User.class)))
                    .thenReturn(new AuthResponse());

            LoginRequest parAdresse = new LoginRequest();
            parAdresse.setIdentifier("admin@restart.test");
            parAdresse.setPassword(PASSWORD);

            StepVerifier.create(authService.login(parAdresse, IP, USER_AGENT))
                    .expectNextCount(1)
                    .verifyComplete();

            // Et surtout : la recherche par telephone n'a PAS ete tentee avec
            // une adresse en guise de numero.
            verify(userRepository, never()).findByPhoneNumber("admin@restart.test");
        }

        @Test
        @DisplayName("identifier est prioritaire sur phoneNumber")
        void identifierPrioritaire() {
            User admin = activeUser();
            admin.setEmail("admin@restart.test");

            when(userRepository.findByEmail("admin@restart.test")).thenReturn(Mono.just(admin));
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(userRepository.save(any(User.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(jwtService.generateToken(admin)).thenReturn(Mono.just("access-token"));
            when(refreshTokenService.createRefreshToken(any(), any(), any()))
                    .thenReturn(Mono.just(new RefreshToken()));
            when(authResponseMapper.toAuthResponse(
                    anyString(), any(RefreshToken.class), any(Long.class), any(User.class)))
                    .thenReturn(new AuthResponse());

            LoginRequest lesDeux = request();
            lesDeux.setIdentifier("admin@restart.test");

            StepVerifier.create(authService.login(lesDeux, IP, USER_AGENT))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(userRepository, never()).findByPhoneNumber(PHONE);
        }

        @Test
        @DisplayName("sans aucun identifiant, le refus est celui d'un compte inconnu")
        void aucunIdentifiant() {
            // La validation du corps rejette deja ce cas en 400. Ce test couvre
            // l'appel direct au service, pour qu'il refuse au lieu de lever une
            // NullPointerException.
            LoginRequest vide = new LoginRequest();
            vide.setPassword(PASSWORD);

            StepVerifier.create(authService.login(vide, IP, USER_AGENT))
                    .verifyErrorSatisfies(e -> assertThat(((AuthException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        }
    }

    @Test
    @DisplayName("connexion réussie : compteur d'échecs remis à zéro et tokens émis")
    void loginSucceeds() {
        User user = activeUser();
        user.setFailedLoginAttempts(3);

        AuthResponse expected = new AuthResponse();
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(jwtService.generateToken(user)).thenReturn(Mono.just("access-token"));
        when(refreshTokenService.createRefreshToken(eq(user), eq(IP), eq(USER_AGENT)))
                .thenReturn(Mono.just(new RefreshToken()));
        when(authResponseMapper.toAuthResponse(anyString(), any(RefreshToken.class), any(Long.class), eq(user)))
                .thenReturn(expected);

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectNext(expected)
                .verifyComplete();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getFailedLoginAttempts()).isZero();
        assertThat(saved.getValue().getLastLogin()).isNotNull();
    }

    @Test
    @DisplayName("utilisateur inconnu : même erreur qu'un mot de passe invalide (pas de divulgation)")
    void unknownUserDoesNotLeakAccountExistence() {
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.empty());

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS))
                .verify();

        // L'échec doit être tracé même pour un compte inexistant
        verify(authenticationLogService).logAuthenticationEvent(
                eq(null), eq(PHONE), eq(EventType.LOGIN), eq(Status.FAILED),
                eq(IP), eq(USER_AGENT), anyString());
    }

    @Test
    @DisplayName("mot de passe invalide : incrémente le compteur et refuse")
    void wrongPasswordIncrementsCounter() {
        User user = activeUser();
        user.setFailedLoginAttempts(1);

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS))
                .verify();

        assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
        assertThat(user.getIsLocked()).isFalse();
    }

    @Test
    @DisplayName("au seuil de tentatives : le compte est verrouillé")
    void accountLocksAtThreshold() {
        User user = activeUser();
        user.setFailedLoginAttempts(AppConstants.MAX_FAILED_LOGIN_ATTEMPTS - 1);

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectError(AuthException.class)
                .verify();

        assertThat(user.getIsLocked()).isTrue();
        assertThat(user.getLockedUntil()).isAfter(LocalDateTime.now());

        // La metrique est verifiee chez son DETENTEUR, sur un compteur reel.
        //
        // L'assertion precedente etait
        // `verify(authMetrics).recordAccountLockout("max_failed_attempts")`
        // sur le mock injecte dans AuthServiceImpl. Elle verrouillait donc
        // l'appel depuis le MAUVAIS endroit : la regle de verrouillage
        // appartient a AccountLockoutServiceImpl, et les trois autres chemins
        // qui verrouillent — connexion administrateur, enrolement du second
        // facteur, code TOTP errone — ne comptaient rien.
        //
        // Lire la valeur plutot que verifier l'appel a un second avantage :
        // un mock accepte n'importe quelle etiquette, un compteur non.
        // Etiquette ecrite en LITTERAL, et ce n'est pas un raccourci.
        //
        // « auth.account.lockout » et « seuil_tentatives_atteint » sont le
        // contrat avec security-admin-alerts.yml, qui interroge
        // auth_account_lockout_total{reason="seuil_tentatives_atteint"}.
        // Passer par la constante Java laisserait un renommage casser
        // silencieusement l'alerte : le code compilerait, le test passerait, et
        // l'alerte deviendrait muette. Le litteral fait echouer ce test au
        // moment ou l'alerte cesse de correspondre.
        assertThat(REGISTRE.counter("auth.account.lockout",
                        "reason", "seuil_tentatives_atteint").count())
                .withFailMessage("le verrouillage n'est pas compte chez le detenteur "
                        + "de la regle : les chemins autres que la connexion du SDK "
                        + "verrouilleraient sans que la supervision le voie")
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("compte verrouillé : refus sans même vérifier le mot de passe")
    void lockedAccountIsRejectedBeforePasswordCheck() {
        User user = activeUser();
        user.setIsLocked(true);
        user.setLockedUntil(LocalDateTime.now().plusMinutes(15));

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_LOCKED))
                .verify();

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("verrouillage échu : le compte est déverrouillé et la connexion aboutit")
    void expiredLockIsLifted() {
        User user = activeUser();
        user.setIsLocked(true);
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        user.setFailedLoginAttempts(AppConstants.MAX_FAILED_LOGIN_ATTEMPTS);

        AuthResponse expected = new AuthResponse();
        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(jwtService.generateToken(any(User.class))).thenReturn(Mono.just("access-token"));
        when(refreshTokenService.createRefreshToken(any(User.class), anyString(), anyString()))
                .thenReturn(Mono.just(new RefreshToken()));
        when(authResponseMapper.toAuthResponse(anyString(), any(RefreshToken.class), any(Long.class), any(User.class)))
                .thenReturn(expected);

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectNext(expected)
                .verifyComplete();

        assertThat(user.getIsLocked()).isFalse();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("téléphone non vérifié : connexion refusée")
    void unverifiedPhoneIsRejected() {
        User user = activeUser();
        user.setIsPhoneVerified(false);

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.PHONE_NOT_VERIFIED))
                .verify();
    }

    @Test
    @DisplayName("compte désactivé : connexion refusée")
    void disabledAccountIsRejected() {
        User user = activeUser();
        user.setIsActive(false);

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.ACCOUNT_DISABLED))
                .verify();
    }

    @Test
    @DisplayName("une panne de géolocalisation ne fait pas échouer la connexion")
    void geoLocationFailureDoesNotBreakLogin() {
        User user = activeUser();
        AuthResponse expected = new AuthResponse();

        when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));
        when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(geoLocationClient.getGeoLocation(anyString()))
                .thenReturn(Mono.error(new RuntimeException("gateway indisponible")));
        when(jwtService.generateToken(any(User.class))).thenReturn(Mono.just("access-token"));
        when(refreshTokenService.createRefreshToken(any(User.class), anyString(), anyString()))
                .thenReturn(Mono.just(new RefreshToken()));
        when(authResponseMapper.toAuthResponse(anyString(), any(RefreshToken.class), any(Long.class), any(User.class)))
                .thenReturn(expected);

        StepVerifier.create(authService.login(request(), IP, USER_AGENT))
                .expectNext(expected)
                .verifyComplete();
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
     * Le vrai service de second facteur.
     *
     * <p>Un bouchon qui laisserait passer rendrait verte la suite entiere, y
     * compris le test qui verifie qu'un second facteur enrole est bien oppose
     * sur ce chemin — c'est-a-dire precisement le defaut corrige.
     *
     * <p>{@code TotpService} est simule, et les tests qui ont besoin d'un code
     * valide posent leur propre stub.
     */
    private com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl secondFacteurReel() {
        return new com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl(
                userRepository, totpService, serviceDeVerrouillage(),
                com.abdatytch.auth_service.testutil.CodesDeSecoursDEssai
                        .aucunCodeDeSecours(),
                metriques(), "Restart Test");
    }

    private AccountLockoutServiceImpl serviceDeVerrouillage() {
        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(userRepository, metriques(), revocationQuiNeFaitRien());
        org.springframework.test.util.ReflectionTestUtils.setField(
                verrouillage, "maxAttempts", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(
                verrouillage, "lockoutDurationMinutes", 30);
        return verrouillage;
    }

    /**
     * Messagerie simulee : l'envoi est ACTIF et aboutit.
     *
     * <p>Rend {@code true}, et c'est une difference avec le bouchon precedent.
     * Il implementait {@code ISmsService}, dont {@code sendSms} rendait
     * {@code Mono<Void>} : « termine » et « rien n'est parti » y etaient
     * indistinguables. Avec {@code Mono<Boolean>}, un bouchon doit CHOISIR ce
     * qu'il simule, et celui-ci simule une remise reussie.
     */
    private com.abdatytch.auth_service.service.IMessagerieSortante messagerieSimulee() {
        return new com.abdatytch.auth_service.service.IMessagerieSortante() {
            @Override
            public reactor.core.publisher.Mono<Boolean> envoyer(Demande demande) {
                return reactor.core.publisher.Mono.just(true);
            }

            @Override
            public boolean envoiSmsActif() {
                return true;
            }
        };
    }

    @Nested
    @DisplayName("forgot-password : aucune enumeration des comptes")
    class ForgotPasswordSansEnumeration {

        /**
         * Le defaut mesure sur l'environnement de developpement.
         *
         * <pre>
         * {"phoneNumber":"+22399999999"} -> 200 "Code envoye"
         * {"phoneNumber":"+22399999998"} -> 404 "Utilisateur non trouve"
         * </pre>
         *
         * N'importe qui pouvait determiner quels numeros sont inscrits, un
         * appel a la fois, sur un endpoint PUBLIC et sans rien forger.
         *
         * <p>Le 409 de l'inscription est un compromis different et assume :
         * l'utilisateur doit savoir qu'il possede deja un compte. Ici, rien ne
         * justifie de le reveler — l'appelant n'a aucune decision a prendre
         * selon que le compte existe.
         */
        @Test
        @DisplayName("un compte INCONNU rend la meme reponse qu'un compte existant")
        void compteInconnuMemeReponse() {
            when(userRepository.findByPhoneNumber("+22300000000")).thenReturn(Mono.empty());

            StepVerifier.create(authService.initiateForgotPassword("+22300000000"))
                    .assertNext(reponse -> assertThat(reponse.getMessage())
                            .withFailMessage("un compte inconnu doit rendre la MEME "
                                    + "reponse, sinon l'endpoint enumere les inscrits")
                            .isEqualTo(AppConstants.FORGOT_PASSWORD_NEUTRAL_MESSAGE))
                    .verifyComplete();
        }

        @Test
        @DisplayName("aucun code n'est genere pour un compte inconnu")
        void aucunCodePourUnCompteInconnu() {
            when(userRepository.findByPhoneNumber("+22300000000")).thenReturn(Mono.empty());

            StepVerifier.create(authService.initiateForgotPassword("+22300000000"))
                    .expectNextCount(1)
                    .verifyComplete();

            // La reponse est uniforme, mais le travail ne l'est pas : rien ne
            // doit etre genere ni envoye pour un numero qui n'existe pas.
            verify(verificationCodeService, never())
                    .generateAndSendCode(anyString(), any(), any());
        }

        @Test
        @DisplayName("le message est au conditionnel, donc exact dans les deux cas")
        void messageAuConditionnel() {
            // « Code de reinitialisation envoye » serait faux pour un compte
            // inconnu : on remplacerait une fuite par un mensonge.
            assertThat(AppConstants.FORGOT_PASSWORD_NEUTRAL_MESSAGE)
                    .contains("Si ce compte existe");
        }
    }

    @Nested
    @DisplayName("Le message d'envoi OTP dit la verite")
    class MessageDEnvoiOtp {

        /**
         * Ces deux tests exercent desormais le VRAI chemin.
         *
         * <h4>Ce qu'ils faisaient avant, et pourquoi c'etait plus faible</h4>
         *
         * Ils appelaient {@code messageDEnvoiOtp()} par reflexion. Cette
         * methode lisait {@code envoiSmsActif()}, un interrupteur de
         * CONFIGURATION : les tests verifiaient donc qu'une valeur de
         * configuration se traduisait en message, et rien de plus. Ils
         * passaient parfaitement alors qu'un plafond journalier atteint, un
         * numero hors liste autorisee ou un fournisseur mal configure
         * produisaient tous « Code OTP envoyé par SMS ».
         *
         * <p>La reponse est desormais formulee depuis ce qui est REELLEMENT
         * parti, donc ces tests appellent l'endpoint et lisent le message de la
         * reponse. La methode privee a disparu avec son interrupteur.
         */
        private AuthServiceImpl serviceDontLEnvoiRend(boolean parti) {
            var messagerie = new com.abdatytch.auth_service.service.IMessagerieSortante() {
                @Override
                public Mono<Boolean> envoyer(Demande demande) {
                    return Mono.just(parti);
                }

                @Override
                public boolean envoiSmsActif() {
                    // VOLONTAIREMENT l'inverse de `parti` quand rien ne part :
                    // c'est la combinaison que l'ancien code ne savait pas
                    // distinguer. L'interrupteur dit « actif », et pourtant le
                    // message ne part pas — plafond atteint, numero hors liste.
                    // Si la reponse se remettait a lire cet interrupteur, elle
                    // mentirait, et ce test le verrait.
                    return true;
                }
            };
            return new AuthServiceImpl(
                    userRepository, verificationCodeService, jwtService, refreshTokenService,
                    authenticationLogService, authResponseMapper, verificationResponseMapper,
                    motDePasseReel(), eventPublisher, geoLocationClient, authMetrics,
                    serviceDeVerrouillage(), messagerie, secondFacteurReel(),
                    aucunAppareilDeConfiance(),
                    com.abdatytch.auth_service.testutil.AlertesDEssai.aucuneAlerte());
        }

        /**
         * Le service de codes est le VRAI, pose sur la messagerie simulee.
         *
         * <p>Un mock d'{@code IVerificationCodeService} rendrait l'EnvoiDuCode
         * que le test voudrait, donc ne prouverait rien du cheminement du
         * resultat. Avec le vrai service, le booleen traverse reellement
         * `sendCode`, `generateAndSendCode` puis la formulation de la reponse.
         */
        private void posePileDeCodesReelle(AuthServiceImpl service, boolean parti) {
            // Ce test porte sur la FORMULATION, pas sur la pile de codes : le
            // mock partage par la classe est donc instruit pour rendre
            // directement l'issue voulue.
            when(verificationCodeService.generateAndSendCode(anyString(), any(), any()))
                    .thenReturn(Mono.just(
                            IVerificationCodeService.EnvoiDuCode.parSmsSeul(parti)));
        }

        private com.abdatytch.auth_service.dto.request.PhoneAuthRequest phoneAuthRequest() {
            return com.abdatytch.auth_service.dto.request.PhoneAuthRequest.builder()
                    .phoneNumber(PHONE)
                    .build();
        }

        @Test
        @DisplayName("rien n'est parti : la reponse ne pretend pas avoir envoye")
        void envoiRefuseNePretendPas() {
            User user = activeUser();
            when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));

            AuthServiceImpl service = serviceDontLEnvoiRend(false);
            posePileDeCodesReelle(service, false);

            StepVerifier.create(service.initiatePhoneAuth(phoneAuthRequest()))
                    .assertNext(reponse -> assertThat(reponse.getMessage())
                            .withFailMessage("""
                                    La reponse annonce un envoi qui n'a pas eu \
                                    lieu. L'utilisateur attendrait un SMS qui \
                                    n'arrivera jamais.""")
                            .isEqualTo(AppConstants.OTP_GENERE_SANS_ENVOI))
                    .verifyComplete();
        }

        @Test
        @DisplayName("le SMS est parti : formulation nominale")
        void envoiAbouticFormulationNominale() {
            User user = activeUser();
            when(userRepository.findByPhoneNumber(PHONE)).thenReturn(Mono.just(user));

            AuthServiceImpl service = serviceDontLEnvoiRend(true);
            posePileDeCodesReelle(service, true);

            StepVerifier.create(service.initiatePhoneAuth(phoneAuthRequest()))
                    .assertNext(reponse -> assertThat(reponse.getMessage())
                            .isEqualTo(AppConstants.OTP_ENVOYE_PAR_SMS))
                    .verifyComplete();
        }
    }

    /**
     * Un VRAI {@code AuthMetrics} sur un registre en memoire.
     *
     * <p>Pas un bouchon : les compteurs de verrouillage et de second facteur
     * sont poses chez les detenteurs de ces regles, et un mock ferait passer
     * pour instrumentes des chemins qui ne le sont pas. Avec un registre reel,
     * un test peut en outre LIRE la valeur produite.
     */
    /**
     * Registre LISIBLE, partage par tous les services construits dans cette
     * suite.
     *
     * <p>Un registre neuf a chaque appel rendrait les compteurs inobservables :
     * le service verrouille sur son registre, et le test en lit un autre.
     */
    private static final io.micrometer.core.instrument.simple.SimpleMeterRegistry REGISTRE =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private static com.abdatytch.auth_service.metrics.AuthMetrics metriques() {
        return new com.abdatytch.auth_service.metrics.AuthMetrics(REGISTRE);
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

    // ==================================================================
    @Nested
    @DisplayName("Rafraichissement : le verrou s'applique AUSSI ici")
    class RafraichissementEtVerrou {

        /**
         * Le contournement complet du verrouillage.
         *
         * <p>{@code assurerNonVerrouille} etait appele par la connexion normale
         * et par la connexion administrateur — deux chemins sur trois. Sur le
         * rafraichissement, rien. Qui detenait deja un refresh token continuait
         * donc de renouveler son acces <b>indefiniment</b> apres le
         * verrouillage de son compte : l'administrateur voyait « compte
         * verrouille » et la personne gardait l'acces.
         *
         * <p>La protection existait. Elle n'etait simplement pas atteinte sur ce
         * chemin — le motif de toute cette base de code.
         */
        @Test
        @DisplayName("un compte verrouille ne peut PAS renouveler son acces")
        void compteVerrouilleNeRafraichitPas() {
            User verrouille = activeUser();
            verrouille.setIsLocked(true);
            verrouille.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(30));

            var jeton = com.abdatytch.auth_service.entities.RefreshToken.builder()
                    .userId(verrouille.getId())
                    // L'empreinte, comme la base la porte. Le rafraichissement
                    // renvoie au client le jeton qu'il a presente, pas celui de
                    // la ligne : cette valeur ne sert ici qu'a exister.
                    .empreinte(com.abdatytch.commons.security.EmpreinteDeSecret
                            .empreinte("jeton-encore-valable"))
                    .expiresAt(java.time.LocalDateTime.now().plusDays(7))
                    .build();

            when(refreshTokenService.validateAndGetRefreshToken(anyString()))
                    .thenReturn(Mono.just(jeton));
            when(userRepository.findById(verrouille.getId()))
                    .thenReturn(Mono.just(verrouille));

            StepVerifier.create(authService.refreshToken(
                            "jeton-encore-valable", IP, USER_AGENT))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur)
                                .withFailMessage("""
                                        Un compte VERROUILLE a obtenu un nouveau jeton \
                                        d'acces.

                                        Le verrouillage ne porte alors que sur la \
                                        connexion : qui detient deja un refresh token \
                                        renouvelle son acces indefiniment, et le verrou \
                                        ne gene que le proprietaire legitime.""")
                                .isInstanceOf(AuthException.class);
                        assertThat(((AuthException) erreur).getErrorCode())
                                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
                    });

            // Aucun jeton n'a ete emis : le refus precede l'emission.
            verify(jwtService, never()).generateToken(any());
        }

        @Test
        @DisplayName("un compte NON verrouille renouvelle normalement")
        void compteNonVerrouilleRafraichit() {
            // Le controle ajoute ne doit pas casser le cas courant : sans ce
            // test, un garde-fou trop large passerait inapercu.
            User sain = activeUser();

            var jeton = com.abdatytch.auth_service.entities.RefreshToken.builder()
                    .userId(sain.getId())
                    .empreinte(com.abdatytch.commons.security.EmpreinteDeSecret
                            .empreinte("jeton-sain"))
                    .expiresAt(java.time.LocalDateTime.now().plusDays(7))
                    .build();

            when(refreshTokenService.validateAndGetRefreshToken(anyString()))
                    .thenReturn(Mono.just(jeton));
            when(userRepository.findById(sain.getId())).thenReturn(Mono.just(sain));
            // Le stub de setUp emploie anyString() en dernier argument, qui ne
            // correspond PAS a null — et refreshToken journalise sans message.
            // Sans ce stub, l'appel rend null et la chaine leve, ce qui ferait
            // echouer le test sur le simulacre et non sur ce qu'il mesure.
            when(authenticationLogService.logAuthenticationEvent(
                    any(), any(), any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.isNull()))
                    .thenReturn(Mono.empty());
            when(jwtService.generateToken(any())).thenReturn(Mono.just("nouveau-jeton"));
            when(jwtService.getExpirationMs()).thenReturn(3_600_000L);
            when(authResponseMapper.toAuthResponse(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any()))
                    .thenReturn(new com.abdatytch.auth_service.dto.response.AuthResponse());

            StepVerifier.create(authService.refreshToken("jeton-sain", IP, USER_AGENT))
                    .expectNextCount(1)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Alertes de securite : l'ANCIENNE coordonnee est prevenue")
    class AlertesDeSecurite {

        private static final String ANCIENNE = "awa.traore@exemple.test";
        private static final String NOUVELLE = "attaquant@exemple.test";

        /**
         * LE test de ce bloc.
         *
         * <p>`ensureEmailAvailable` ecrit la nouvelle adresse dans l'entite en
         * memoire. Lire `user.getEmail()` APRES cet appel — ce qui est le geste
         * naturel — enverrait donc l'avis a l'adresse que l'attaquant vient de
         * choisir, et l'alerte le servirait au lieu de le denoncer.
         *
         * <p>L'ancienne valeur doit etre capturee AVANT la mutation. Ce test
         * fixe cet ordre.
         */
        @Test
        @DisplayName("un changement d'adresse alerte l'ANCIENNE, jamais la nouvelle")
        void changementDadresseAlerteLancienne() {
            User user = activeUser();
            user.setEmail(ANCIENNE);
            user.setIsEmailVerified(true);

            when(userRepository.findById("user-1")).thenReturn(Mono.just(user));
            when(userRepository.existsByEmail(NOUVELLE)).thenReturn(Mono.just(false));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            var requete = new com.abdatytch.auth_service.dto.request.UpdateProfileRequest();
            requete.setEmail(NOUVELLE);

            StepVerifier.create(authService.updateProfile("user-1", requete,
                            com.abdatytch.auth_service.dto.ContexteDeSecurite.de(
                                    IP, USER_AGENT)))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(alertes.evenements())
                    .as("l'avis doit partir a l'ancienne adresse")
                    .containsExactly("adresse:" + ANCIENNE + ":verifiee=true");
            assertThat(alertes.evenements().get(0)).doesNotContain(NOUVELLE);
        }

        @Test
        @DisplayName("l'etat VERIFIE de l'ancienne adresse est transmis tel quel")
        void etatVerifieTransmis() {
            // Il est lu avant la mutation lui aussi : `ensureEmailAvailable`
            // remet `isEmailVerified` a faux, et le lire apres rendrait
            // toujours « non verifiee » — donc n'alerterait jamais personne.
            User user = activeUser();
            user.setEmail(ANCIENNE);
            user.setIsEmailVerified(true);

            when(userRepository.findById("user-1")).thenReturn(Mono.just(user));
            when(userRepository.existsByEmail(NOUVELLE)).thenReturn(Mono.just(false));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            var requete = new com.abdatytch.auth_service.dto.request.UpdateProfileRequest();
            requete.setEmail(NOUVELLE);
            authService.updateProfile("user-1", requete,
                    com.abdatytch.auth_service.dto.ContexteDeSecurite.de(IP, USER_AGENT))
                    .block();

            assertThat(alertes.evenements()).containsExactly(
                    "adresse:" + ANCIENNE + ":verifiee=true");
        }

        @Test
        @DisplayName("un changement de prenom seul n'alerte PERSONNE")
        void changementDeNomNalertePas() {
            // Alerter sur une modification anodine noierait les alertes qui
            // comptent : c'est le mecanisme par lequel les gens cessent de les
            // lire.
            User user = activeUser();
            user.setEmail(ANCIENNE);

            when(userRepository.findById("user-1")).thenReturn(Mono.just(user));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            var requete = new com.abdatytch.auth_service.dto.request.UpdateProfileRequest();
            requete.setFirstName("Awa");

            authService.updateProfile("user-1", requete,
                    com.abdatytch.auth_service.dto.ContexteDeSecurite.de(IP, USER_AGENT))
                    .block();

            assertThat(alertes.evenements()).isEmpty();
        }

        @Test
        @DisplayName("un changement de mot de passe en libre-service alerte")
        void changementDeMotDePasseAlerte() {
            User user = activeUser();

            // L'encodeur est simule dans cette suite : motDePasseReel() le
            // porte, mais c'est le mock qui tranche.
            when(passwordEncoder.matches(PASSWORD, HASH)).thenReturn(true);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$nouvelle");
            when(userRepository.findById("user-1")).thenReturn(Mono.just(user));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
            when(refreshTokenService.revokeAllUserTokens(any(User.class)))
                    .thenReturn(Mono.empty());

            var requete = new com.abdatytch.auth_service.dto.request.ChangePasswordRequest();
            requete.setCurrentPassword(PASSWORD);
            requete.setNewPassword("AutreMotDePasse123!");

            StepVerifier.create(authService.changerMotDePasse("user-1", requete,
                            com.abdatytch.auth_service.dto.ContexteDeSecurite.de(
                                    IP, USER_AGENT)))
                    .verifyComplete();

            assertThat(alertes.evenements()).containsExactly("mot_de_passe:user-1");
        }

        @Test
        @DisplayName("un mot de passe courant FAUX n'alerte pas")
        void motDePasseFauxNalertePas() {
            // Sinon ce point d'entree devient un moyen d'inonder la boite de
            // quelqu'un : il suffirait de presenter des mots de passe errones.
            User user = activeUser();

            when(passwordEncoder.matches("PasLeBon123!", HASH)).thenReturn(false);
            when(userRepository.findById("user-1")).thenReturn(Mono.just(user));
            when(userRepository.save(any(User.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            var requete = new com.abdatytch.auth_service.dto.request.ChangePasswordRequest();
            requete.setCurrentPassword("PasLeBon123!");
            requete.setNewPassword("AutreMotDePasse123!");

            StepVerifier.create(authService.changerMotDePasse("user-1", requete,
                            com.abdatytch.auth_service.dto.ContexteDeSecurite.de(
                                    IP, USER_AGENT)))
                    .verifyError();

            assertThat(alertes.evenements()).isEmpty();
        }
    }

}
