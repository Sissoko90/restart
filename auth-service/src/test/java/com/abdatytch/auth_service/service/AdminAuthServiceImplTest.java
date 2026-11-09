package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.AdminLoginRequest;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.AuthResponseMapper;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.AdminAuthServiceImpl;
import com.abdatytch.commons.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import com.abdatytch.auth_service.constants.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;
import static com.abdatytch.auth_service.testutil.ConfianceDEssai.aucunAppareilDeConfiance;

/**
 * Connexion des administrateurs de la plateforme.
 *
 * <p>Ce chemin est <b>public</b>, parce qu'un administrateur n'a aucune
 * application devant lui pour presenter un jeton. C'est donc le seul endpoint
 * d'auth-service ou un inconnu peut frapper directement, et le seul qui delivre un
 * jeton portant {@code SUPER_ADMIN} — role qui permet la maintenance, la rotation
 * des cles de signature et l'effacement administratif.
 *
 * <p>Trois proprietes valent d'etre verrouillees : le role est exige, le second
 * facteur est exige des qu'il est enrole, et aucun refus ne dit lequel des
 * controles a echoue.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AdminAuthServiceImpl - connexion des administrateurs")
class AdminAuthServiceImplTest {

    private static final String EMAIL = "tech@restart.test";
    private static final String TELEPHONE = "+22300000009";
    private static final String MOT_DE_PASSE = "MotDePasseTechnique123!";
    private static final String SECRET_TOTP = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String CODE_VALIDE = "123456";
    private static final String SECRET_NEUF = "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TotpService totpService;
    @Mock private IJwtService jwtService;
    @Mock private IRefreshTokenService refreshTokenService;
    @Mock private IAuthenticationLogService authenticationLogService;
    @Mock private AuthResponseMapper authResponseMapper;
    @Mock private com.abdatytch.auth_service.service.IVerificationCodeService verificationCodeService;
    @Mock private com.abdatytch.auth_service.mappers.VerificationResponseMapper verificationResponseMapper;
    @Mock private IEventPublisher eventPublisher;
    @Mock private com.abdatytch.auth_service.client.GatewayGeoLocationClient geoLocationClient;
    @Mock private com.abdatytch.auth_service.metrics.AuthMetrics authMetrics;
    @Mock private IMessagerieSortante messagerie;

    private AdminAuthServiceImpl service;
    private User superAdmin;

    @BeforeEach
    void setUp() {
        // Le VRAI service de connexion derriere la delegation, et non un mock.
        //
        // AdminAuthServiceImpl ne porte plus sa propre connexion : elle delegue
        // a AuthServiceImpl.login. Avec un IAuthService simule, tout ce que
        // verifie cette suite — verrouillage, comptage des echecs, second
        // facteur, refus uniforme — ne verifierait plus que le mock.
        //
        // Or c'est exactement la propriete en jeu : la delegation doit appliquer
        // ces regles, et le defaut corrige etait deux implementations dont une
        // en oubliait.
        com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl secondFacteur =
                new com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl(
                        userRepository, totpService, serviceDeVerrouillage(),
                        com.abdatytch.auth_service.testutil.CodesDeSecoursDEssai
                                .aucunCodeDeSecours(),
                        metriques(), "Restart Test");

        com.abdatytch.auth_service.service.impl.AuthServiceImpl serviceDeConnexion =
                new com.abdatytch.auth_service.service.impl.AuthServiceImpl(
                        userRepository, verificationCodeService, jwtService, refreshTokenService,
                        authenticationLogService, authResponseMapper, verificationResponseMapper,
                        motDePasseReel(), eventPublisher, geoLocationClient, authMetrics,
                        serviceDeVerrouillage(), messagerie, secondFacteur,
                        aucunAppareilDeConfiance(),
                com.abdatytch.auth_service.testutil.AlertesDEssai.aucuneAlerte());

        service = new AdminAuthServiceImpl(userRepository, motDePasseReel(),
                serviceDeConnexion, secondFacteur, authenticationLogService,
                serviceDeVerrouillage());

        superAdmin = new User();
        superAdmin.setId("super-admin-1");
        superAdmin.setEmail(EMAIL);
        superAdmin.setPhoneNumber(TELEPHONE);
        superAdmin.setPasswordHash("chiffre");
        superAdmin.setRole(UserRole.SUPER_ADMIN);
        superAdmin.setTotpSecret(SECRET_TOTP);
        superAdmin.setTotpEnabled(true);
        // Controles que l'ancienne connexion administrateur ne faisait PAS, et
        // qui s'appliquent maintenant qu'elle delegue : un administrateur
        // desactive se connectait par ce chemin, et par celui-la seulement.
        superAdmin.setIsPhoneVerified(true);
        superAdmin.setIsActive(true);

        when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(superAdmin));
        when(userRepository.findByPhoneNumber(TELEPHONE)).thenReturn(Mono.just(superAdmin));
        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(passwordEncoder.matches(eq(MOT_DE_PASSE), anyString())).thenReturn(true);
        when(totpService.validateTotpCode(anyString(), eq(CODE_VALIDE))).thenReturn(Mono.just(true));
        when(totpService.validateTotpCode(anyString(), eq("000000"))).thenReturn(Mono.just(false));
        when(totpService.generateQrCodeUrl(any(), any(), any())).thenReturn("otpauth://totp/x");
        when(jwtService.generateToken(any())).thenReturn(Mono.just("jeton-acces"));
        when(jwtService.getExpirationMs()).thenReturn(3_600_000L);
        RefreshToken refreshToken = new RefreshToken();
        // valeurEnClair et non plus token : la connexion administrateur passe
        // par AuthResponseMapper, qui lit valeurAEnvoyerAuClient(). Poser
        // l'empreinte ici ne suffirait pas — le mapper leverait, parce qu'une
        // entite sans secret en clair ne vient pas d'etre creee.
        refreshToken.setValeurEnClair("jeton-rafraichissement");
        when(refreshTokenService.createRefreshToken(any(), any(), any()))
                .thenReturn(Mono.just(refreshToken));
        when(authenticationLogService.logAuthenticationEvent(
                any(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(authenticationLogService.logAuthenticationEvent(
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        // Sans ce stub, le mock rendrait null et le `onErrorResume` pose dessus
        // leverait une NullPointerException : l'echec ressemblerait a un defaut
        // de la connexion.
        when(geoLocationClient.getGeoLocation(any())).thenReturn(Mono.empty());
        when(totpService.generateTotpSecret()).thenReturn(SECRET_NEUF);
        when(authResponseMapper.toAuthResponse(
                anyString(), any(RefreshToken.class), anyLong(), any(User.class)))
                .thenReturn(new AuthResponse());
    }

    private AdminLoginRequest requete(String identifiant, String motDePasse, String code) {
        AdminLoginRequest r = new AdminLoginRequest();
        r.setIdentifier(identifiant);
        r.setPassword(motDePasse);
        r.setTotpCode(code);
        return r;
    }

    private void verifierRefus(AdminLoginRequest requete) {
        StepVerifier.create(service.login(requete, "10.0.0.1", "test"))
                .verifyError(AuthException.class);
    }

    // ==================================================================
    @Nested
    @DisplayName("Connexion reussie")
    class ConnexionReussie {

        @Test
        @DisplayName("identifiant, mot de passe et code valides donnent un jeton")
        void connexionParEmail() {
            StepVerifier.create(service.login(
                            requete(EMAIL, MOT_DE_PASSE, CODE_VALIDE), "10.0.0.1", "test"))
                    .expectNextCount(1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("le telephone est accepte comme identifiant")
        void connexionParTelephone() {
            // Un administrateur retient plus facilement son adresse, mais le
            // compte seme au deploiement porte les deux.
            StepVerifier.create(service.login(
                            requete(TELEPHONE, MOT_DE_PASSE, CODE_VALIDE), "10.0.0.1", "test"))
                    .expectNextCount(1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("la connexion remet le compteur d'echecs a zero")
        void compteurRemisAZero() {
            superAdmin.setFailedLoginAttempts(3);

            StepVerifier.create(service.login(
                            requete(EMAIL, MOT_DE_PASSE, CODE_VALIDE), "10.0.0.1", "test"))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(capture.capture());
            assertThat(capture.getValue().getFailedLoginAttempts()).isZero();
            assertThat(capture.getValue().getLastLogin()).isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le role d'administration est exige")
    class RoleExige {

        @ParameterizedTest(name = "{0} est refuse")
        @EnumSource(value = UserRole.class, names = {"USER", "DEVELOPER", "MERCHANT", "DRIVER"})
        @DisplayName("un compte sans role d'administration est refuse")
        void roleNonAdministratifRefuse(UserRole role) {
            // Sans ce controle, cet endpoint public serait un second point
            // d'entree pour les utilisateurs finaux, contournant le modele du SDK
            // ou c'est l'application du developpeur qui les authentifie.
            superAdmin.setRole(role);

            verifierRefus(requete(EMAIL, MOT_DE_PASSE, CODE_VALIDE));
            verify(jwtService, never()).generateToken(any());
        }

        @ParameterizedTest(name = "{0} est accepte")
        @EnumSource(value = UserRole.class, names = {"ADMIN", "SUPER_ADMIN"})
        @DisplayName("les deux roles d'administration sont acceptes")
        void rolesAdministratifsAcceptes(UserRole role) {
            superAdmin.setRole(role);

            StepVerifier.create(service.login(
                            requete(EMAIL, MOT_DE_PASSE, CODE_VALIDE), "10.0.0.1", "test"))
                    .expectNextCount(1)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le second facteur")
    class SecondFacteur {

        @Test
        @DisplayName("un code absent est refuse quand le second facteur est enrole")
        void codeAbsentRefuse() {
            verifierRefus(requete(EMAIL, MOT_DE_PASSE, null));
            verify(jwtService, never()).generateToken(any());
        }

        @Test
        @DisplayName("un code faux est refuse")
        void codeFauxRefuse() {
            verifierRefus(requete(EMAIL, MOT_DE_PASSE, "000000"));
            verify(jwtService, never()).generateToken(any());
        }

        @Test
        @DisplayName("sans second facteur enrole, la connexion passe pour permettre l'enrolement")
        void connexionSansSecondFacteurEnrole() {
            // Impasse a eviter : exiger un code que l'administrateur ne peut pas
            // obtenir sans etre connecte. L'enrolement exige le mot de passe et ne
            // remet le secret qu'une fois.
            superAdmin.setTotpEnabled(false);

            StepVerifier.create(service.login(
                            requete(EMAIL, MOT_DE_PASSE, null), "10.0.0.1", "test"))
                    .expectNextCount(1)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Aucune divulgation")
    class AucuneDivulgation {

        @Test
        @DisplayName("un compte inexistant est refuse")
        void compteInexistant() {
            when(userRepository.findByEmail("inconnu@test.local")).thenReturn(Mono.empty());

            verifierRefus(requete("inconnu@test.local", MOT_DE_PASSE, CODE_VALIDE));
        }

        @Test
        @DisplayName("un mot de passe faux est refuse")
        void motDePasseFaux() {
            when(passwordEncoder.matches(eq("faux"), anyString())).thenReturn(false);

            verifierRefus(requete(EMAIL, "faux", CODE_VALIDE));
        }

        @Test
        @DisplayName("compte inexistant et mot de passe faux donnent la MEME erreur")
        void memeErreur() {
            // Distinguer les deux permettrait d'enumerer les comptes, et surtout
            // d'identifier lesquels sont administrateurs — c'est-a-dire de
            // designer la cible.
            when(userRepository.findByEmail("inconnu@test.local")).thenReturn(Mono.empty());
            when(passwordEncoder.matches(eq("faux"), anyString())).thenReturn(false);

            Throwable inexistant = capturerErreur(requete("inconnu@test.local", MOT_DE_PASSE, CODE_VALIDE));
            Throwable motDePasse = capturerErreur(requete(EMAIL, "faux", CODE_VALIDE));

            assertThat(inexistant.getMessage())
                    .withFailMessage("les deux refus se distinguent : les comptes "
                            + "administrateurs peuvent etre enumeres")
                    .isEqualTo(motDePasse.getMessage());
        }

        @Test
        @DisplayName("chaque refus est journalise")
        void refusJournalise() {
            // C'est le signal qui permet de reperer une attaque sur un compte
            // d'administration. Le refus reste muet pour l'appelant, pas pour
            // l'exploitant.
            when(userRepository.findByEmail("inconnu@test.local")).thenReturn(Mono.empty());

            verifierRefus(requete("inconnu@test.local", MOT_DE_PASSE, CODE_VALIDE));

            verify(authenticationLogService).logAuthenticationEvent(
                    any(), any(), any(), any(), any(), any(), any());
        }

        private Throwable capturerErreur(AdminLoginRequest requete) {
            try {
                service.login(requete, "10.0.0.1", "test").block();
                throw new AssertionError("aucune erreur emise");
            } catch (Throwable e) {
                return e;
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Enrolement du second facteur")
    class Enrolement {

        @Test
        @DisplayName("le secret est remis puis le second facteur devient actif")
        void enrolementReussi() {
            superAdmin.setTotpEnabled(false);

            StepVerifier.create(service.enrollTotp(requete(EMAIL, MOT_DE_PASSE, null)))
                    .assertNext(reponse -> {
                        assertThat(reponse.getSecret()).isEqualTo(SECRET_TOTP);
                        assertThat(reponse.getQrCodeUrl()).isNotBlank();
                    })
                    .verifyComplete();

            ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(capture.capture());
            assertThat(capture.getValue().getTotpEnabled()).isTrue();
        }

        @Test
        @DisplayName("un second enrolement est REFUSE")
        void secondEnrolementRefuse() {
            // Le reservir rouvrirait la faille corrigee dans interface-service, ou
            // le secret du second facteur etait recuperable a volonte.
            superAdmin.setTotpEnabled(true);

            StepVerifier.create(service.enrollTotp(requete(EMAIL, MOT_DE_PASSE, null)))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("l'enrolement exige le mot de passe, et l'echec est COMPTE")
        void enrolementExigeMotDePasse() {
            superAdmin.setTotpEnabled(false);
            superAdmin.setFailedLoginAttempts(0);
            when(passwordEncoder.matches(eq("faux"), anyString())).thenReturn(false);

            StepVerifier.create(service.enrollTotp(requete(EMAIL, "faux", null)))
                    .verifyError(AuthException.class);

            // Le second facteur ne devient pas actif, et aucun secret ne sort.
            assertThat(superAdmin.getTotpEnabled()).isFalse();

            // Le compteur monte, et c'est le point.
            //
            // Cet endpoint etait un ORACLE DE MOT DE PASSE sans aucun frein :
            // il repondait AUTH_1001 pour un mot de passe faux et 2FA_9002
            // quand le second facteur etait deja actif. La reponse disait donc
            // si le mot de passe etait bon, sans qu'aucun second facteur n'ait
            // a etre presente et sans que rien ne soit compte.
            //
            // L'assertion precedente etait `verify(userRepository,
            // never()).save(any())` — elle VERROUILLAIT l'absence de comptage.
            assertThat(superAdmin.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("un compte sans role d'administration ne peut pas enroler")
        void enrolementRoleExige() {
            superAdmin.setRole(UserRole.USER);
            superAdmin.setTotpEnabled(false);

            StepVerifier.create(service.enrollTotp(requete(EMAIL, MOT_DE_PASSE, null)))
                    .verifyError(AuthException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Reinitialisation du second facteur")
    class Reinitialisation {

        private com.abdatytch.auth_service.dto.request.TotpResetRequest demande(
                String motDePasse, String code) {
            var d = new com.abdatytch.auth_service.dto.request.TotpResetRequest();
            d.setIdentifier(EMAIL);
            d.setPassword(motDePasse);
            d.setTotpCode(code);
            return d;
        }

        @Test
        @DisplayName("mot de passe et code courant donnent un secret NEUF")
        void reinitialisationReussie() {
            StepVerifier.create(service.reinitialiserTotp(demande(MOT_DE_PASSE, CODE_VALIDE)))
                    .assertNext(reponse -> {
                        assertThat(reponse.getSecret()).isEqualTo(SECRET_NEUF);
                        assertThat(reponse.getSecret()).isNotEqualTo(SECRET_TOTP);
                        assertThat(reponse.getQrCodeUrl()).isNotBlank();
                    })
                    .verifyComplete();
        }

        /**
         * La propriete qui fait tout l'interet de cet endpoint.
         *
         * <p>Sans le code courant, un mot de passe seul suffirait a substituer
         * son propre appareil a celui de l'administrateur : le second facteur
         * serait contournable par le premier, c'est-a-dire inutile.
         */
        @Test
        @DisplayName("sans le code courant, c'est REFUSE")
        void codeCourantExige() {
            StepVerifier.create(service.reinitialiserTotp(demande(MOT_DE_PASSE, null)))
                    .verifyError(AuthException.class);

            assertThat(superAdmin.getTotpSecret())
                    .withFailMessage("le secret a ete remplace sans que le code courant "
                            + "soit presente : un mot de passe vole suffit a prendre la "
                            + "main sur le second facteur")
                    .isEqualTo(SECRET_TOTP);
        }

        @Test
        @DisplayName("un code faux est refuse et compte comme un echec")
        void codeFauxRefuse() {
            superAdmin.setFailedLoginAttempts(0);

            StepVerifier.create(service.reinitialiserTotp(demande(MOT_DE_PASSE, "000000")))
                    .verifyError(AuthException.class);

            assertThat(superAdmin.getTotpSecret()).isEqualTo(SECRET_TOTP);
            assertThat(superAdmin.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("un mot de passe faux est refuse")
        void motDePasseFauxRefuse() {
            when(passwordEncoder.matches(eq("faux"), anyString())).thenReturn(false);

            StepVerifier.create(service.reinitialiserTotp(demande("faux", CODE_VALIDE)))
                    .verifyError(AuthException.class);

            assertThat(superAdmin.getTotpSecret()).isEqualTo(SECRET_TOTP);
        }

        @Test
        @DisplayName("un compte sans role d'administration ne peut pas reinitialiser")
        void roleExige() {
            superAdmin.setRole(UserRole.USER);

            StepVerifier.create(service.reinitialiserTotp(demande(MOT_DE_PASSE, CODE_VALIDE)))
                    .verifyError(AuthException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Retrait force par un super administrateur")
    class RetraitForce {

        @Test
        @DisplayName("le second facteur est retire et le secret renouvele")
        void retraitEffectif() {
            StepVerifier.create(service.retirerSecondFacteur(EMAIL, "super-admin-2"))
                    .verifyComplete();

            assertThat(superAdmin.getTotpEnabled()).isFalse();
            assertThat(superAdmin.getTotpSecret()).isEqualTo(SECRET_NEUF);
        }

        @Test
        @DisplayName("l'operation est journalisee avec l'acteur")
        void journalise() {
            // Retirer le second facteur d'un autre administrateur est une
            // operation qui doit laisser une trace nominative : c'est le seul
            // garde-fou contre son usage abusif.
            StepVerifier.create(service.retirerSecondFacteur(EMAIL, "super-admin-2"))
                    .verifyComplete();

            org.mockito.ArgumentCaptor<String> motif =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            verify(authenticationLogService).logAuthenticationEvent(
                    any(), any(), any(), any(), any(), any(), motif.capture());
            assertThat(motif.getValue()).contains("super-admin-2");
        }

        @Test
        @DisplayName("un compte non administrateur rend un Mono VIDE, pas une confirmation")
        void compteNonAdministrateur() {
            // Le controleur traduit la vacuite en 404. Rendre une confirmation
            // laisserait enumerer les comptes administrateurs.
            superAdmin.setRole(UserRole.USER);

            StepVerifier.create(service.retirerSecondFacteur(EMAIL, "super-admin-2"))
                    .verifyComplete();

            assertThat(superAdmin.getTotpEnabled()).isTrue();
        }

        @Test
        @DisplayName("un compte inexistant rend un Mono vide")
        void compteInexistant() {
            when(userRepository.findByEmail("inconnu@test.local")).thenReturn(Mono.empty());

            StepVerifier.create(service.retirerSecondFacteur("inconnu@test.local", "super-admin-2"))
                    .verifyComplete();
        }
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
    private AccountLockoutServiceImpl serviceDeVerrouillage() {
        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(userRepository, metriques(), revocationQuiNeFaitRien());
        org.springframework.test.util.ReflectionTestUtils.setField(
                verrouillage, "maxAttempts", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(
                verrouillage, "lockoutDurationMinutes", 30);
        return verrouillage;
    }

    @Nested
    @DisplayName("Verrouillage de compte : la surface qui n'en avait aucun")
    class VerrouillageDeCompte {

        private com.abdatytch.auth_service.dto.request.AdminLoginRequest demande(
                String motDePasse, String codeTotp) {
            var d = new com.abdatytch.auth_service.dto.request.AdminLoginRequest();
            d.setIdentifier(TELEPHONE);
            d.setPassword(motDePasse);
            d.setTotpCode(codeTotp);
            return d;
        }

        /**
         * Le defaut mesure sur l'environnement de developpement.
         *
         * <pre>
         * POST /admin/accounts/{id}/lock            -> 200, cle Redis posee
         * GET  /admin/accounts/{id}/lockout-status  -> isLocked: true
         * POST /auth/admin/login                    -> 200 + jeton valide
         * </pre>
         *
         * Un administrateur verrouillait un compte, l'interface le confirmait
         * verrouille, et le compte se connectait quand meme : les endpoints
         * d'administration ecrivaient dans Redis, et la connexion
         * administrateur ne consultait RIEN.
         */
        @Test
        @DisplayName("un compte verrouille ne peut pas se connecter")
        void compteVerrouilleRefuse() {
            superAdmin.setIsLocked(true);
            superAdmin.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(30));

            StepVerifier.create(service.login(demande(MOT_DE_PASSE, CODE_VALIDE),
                            "41.223.10.5", "Mozilla/5.0"))
                    .verifyErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED));
        }

        @Test
        @DisplayName("un verrou echu laisse passer, et se libere en base")
        void verrouEchuLibere() {
            superAdmin.setIsLocked(true);
            superAdmin.setLockedUntil(java.time.LocalDateTime.now().minusMinutes(1));
            superAdmin.setFailedLoginAttempts(5);

            StepVerifier.create(service.login(demande(MOT_DE_PASSE, CODE_VALIDE),
                            "41.223.10.5", "Mozilla/5.0"))
                    .expectNextCount(1)
                    .verifyComplete();

            // Le deverrouillage automatique doit etre PERSISTE : sinon les
            // colonnes resteraient indefiniment a « verrouille » et tout
            // diagnostic en base serait trompeur.
            assertThat(superAdmin.getIsLocked()).isFalse();
            assertThat(superAdmin.getLockedUntil()).isNull();
        }

        @Test
        @DisplayName("un mot de passe faux incremente le compteur")
        void motDePasseFauxCompte() {
            superAdmin.setFailedLoginAttempts(0);

            StepVerifier.create(service.login(demande("mauvais", CODE_VALIDE),
                            "41.223.10.5", "Mozilla/5.0"))
                    .verifyError(AuthException.class);

            // Le `filter` d'origine rendait un Mono vide et PERDAIT l'entite :
            // il etait impossible d'incrementer quoi que ce soit. La force
            // brute sur le mot de passe administrateur etait donc illimitee.
            assertThat(superAdmin.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("au cinquieme echec, le compte se verrouille")
        void cinquiemeEchecVerrouille() {
            superAdmin.setFailedLoginAttempts(4);

            StepVerifier.create(service.login(demande("mauvais", CODE_VALIDE),
                            "41.223.10.5", "Mozilla/5.0"))
                    .verifyError(AuthException.class);

            assertThat(superAdmin.getFailedLoginAttempts()).isEqualTo(5);
            assertThat(superAdmin.getIsLocked()).isTrue();
            assertThat(superAdmin.getLockedUntil()).isNotNull();
        }

        @Test
        @DisplayName("un code TOTP errone compte aussi comme un echec")
        void codeTotpErroneCompte() {
            superAdmin.setFailedLoginAttempts(0);

            StepVerifier.create(service.login(demande(MOT_DE_PASSE, "000000"),
                            "41.223.10.5", "Mozilla/5.0"))
                    .verifyError(AuthException.class);

            // Sans cela, six chiffres — un million de possibilites — seraient
            // attaquables sans limite par qui detient deja le mot de passe.
            assertThat(superAdmin.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("une connexion reussie remet le compteur a zero")
        void connexionReussieRemetAZero() {
            superAdmin.setFailedLoginAttempts(3);

            StepVerifier.create(service.login(demande(MOT_DE_PASSE, CODE_VALIDE),
                            "41.223.10.5", "Mozilla/5.0"))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(superAdmin.getFailedLoginAttempts()).isZero();
        }

        @Test
        @DisplayName("le refus d'un compte verrouille ne precise pas le mot de passe")
        void refusNePreciseRien() {
            // Le verrou est verifie AVANT le mot de passe : le refus est le
            // meme avec un mot de passe juste ou faux, donc il ne sert pas
            // d'oracle.
            superAdmin.setIsLocked(true);
            superAdmin.setLockedUntil(java.time.LocalDateTime.now().plusMinutes(30));
            superAdmin.setFailedLoginAttempts(2);

            for (String motDePasse : new String[] {MOT_DE_PASSE, "mauvais"}) {
                StepVerifier.create(service.login(demande(motDePasse, CODE_VALIDE),
                                "41.223.10.5", "Mozilla/5.0"))
                        .verifyErrorSatisfies(e -> assertThat(e)
                                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_LOCKED));
            }
            // Et le compteur ne monte pas davantage sur un compte deja
            // verrouille : le verrou est verifie avant tout comptage, donc deux
            // tentatives de plus ne changent rien.
            assertThat(superAdmin.getFailedLoginAttempts()).isEqualTo(2);
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
