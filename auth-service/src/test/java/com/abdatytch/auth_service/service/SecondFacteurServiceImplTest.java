package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl;
import com.abdatytch.commons.totp.TotpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;

/**
 * Le detenteur unique de la regle du second facteur.
 *
 * <h2>Ce que cette suite protege</h2>
 *
 * La regle etait ecrite dans la connexion administrateur, et nulle part
 * ailleurs. Deux chemins l'utilisent desormais ; une regle recopiee est une
 * regle qui divergera, et ce depot l'a deja vu sur le verrouillage de compte.
 *
 * <p>Les proprietes verrouillees ici sont celles dont la violation ne produit
 * aucune erreur visible : un second facteur qui laisse passer sans code, une
 * reinitialisation qui conserve l'ancien secret, un retrait qui remet en
 * service l'appareil perdu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SecondFacteurServiceImpl - la regle du second facteur")
class SecondFacteurServiceImplTest {

    private static final String SECRET_ACTUEL = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String SECRET_NEUF = "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U";
    private static final String CODE_VALIDE = "123456";
    private static final String CODE_FAUX = "000000";

    @Mock private UserRepository userRepository;
    @Mock private TotpService totpService;

    private SecondFacteurServiceImpl service;
    private User admin;

    @BeforeEach
    void preparer() {
        service = new SecondFacteurServiceImpl(
                userRepository, totpService, serviceDeVerrouillage(),
                com.abdatytch.auth_service.testutil.CodesDeSecoursDEssai
                        .aucunCodeDeSecours(), metriques(), "Restart Test");

        admin = new User();
        admin.setId("admin-1");
        admin.setEmail("admin@restart.test");
        admin.setTotpSecret(SECRET_ACTUEL);
        admin.setTotpEnabled(true);

        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(totpService.validateTotpCode(eq(SECRET_ACTUEL), eq(CODE_VALIDE)))
                .thenReturn(Mono.just(true));
        when(totpService.validateTotpCode(anyString(), eq(CODE_FAUX)))
                .thenReturn(Mono.just(false));
        when(totpService.generateTotpSecret()).thenReturn(SECRET_NEUF);
        when(totpService.generateQrCodeUrl(any(), any(), any()))
                .thenAnswer(inv -> "otpauth://totp/x?secret=" + inv.getArgument(1));
    }

    /**
     * Le vrai service de verrouillage, sur le depot simule.
     *
     * <p>Un bouchon ne compterait rien, et c'est precisement le comptage des
     * codes errones qui empeche d'epuiser un million de possibilites.
     */
    private AccountLockoutServiceImpl serviceDeVerrouillage() {
        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(userRepository, metriques(), revocationQuiNeFaitRien());
        ReflectionTestUtils.setField(verrouillage, "maxAttempts", 5);
        ReflectionTestUtils.setField(verrouillage, "lockoutDurationMinutes", 30);
        return verrouillage;
    }

    // ==================================================================
    @Nested
    @DisplayName("Verification a la connexion")
    class Verification {

        @Test
        @DisplayName("un code valide passe")
        void codeValidePasse() {
            StepVerifier.create(service.verifier(admin, CODE_VALIDE, null))
                    .expectNext(admin)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un code absent est refuse quand le second facteur est enrole")
        void codeAbsentRefuse() {
            StepVerifier.create(service.verifier(admin, null, null))
                    .verifyErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.TWO_FACTOR_REQUIRED));
        }

        @Test
        @DisplayName("un code vide vaut un code absent")
        void codeVideRefuse() {
            // Un client qui envoie une chaine vide plutot que d'omettre le champ
            // ne doit pas obtenir un traitement different : sinon la regle
            // depend de la facon dont le client serialise l'absence.
            StepVerifier.create(service.verifier(admin, "   ", null))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("un code faux est refuse ET compte comme un echec")
        void codeFauxCompte() {
            admin.setFailedLoginAttempts(0);

            StepVerifier.create(service.verifier(admin, CODE_FAUX, null))
                    .verifyErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(AuthException.class)
                            // Refus UNIFORME ici, et non « code invalide » : sur
                            // la connexion, la reponse ne doit pas dire lequel
                            // des controles a echoue.
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.INVALID_CREDENTIALS));

            // Six chiffres font un million de possibilites, epuisables en
            // quelques heures par qui detient deja le mot de passe. Sans
            // comptage, le second facteur ne ralentirait rien.
            assertThat(admin.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("sans second facteur enrole, la verification laisse passer")
        void nonEnroleLaissePasser() {
            // Sinon l'administrateur serait dans une impasse : il lui faudrait un
            // code pour se connecter, et une connexion pour obtenir le code.
            admin.setTotpEnabled(false);

            StepVerifier.create(service.verifier(admin, null, null))
                    .expectNext(admin)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Enrolement")
    class Enrolement {

        @Test
        @DisplayName("le secret est remis une fois et le second facteur devient actif")
        void enrolementRemetLeSecret() {
            admin.setTotpEnabled(false);

            StepVerifier.create(service.enroler(admin))
                    .assertNext(reponse -> {
                        assertThat(reponse.getSecret()).isEqualTo(SECRET_ACTUEL);
                        assertThat(reponse.getQrCodeUrl()).contains(SECRET_ACTUEL);
                        assertThat(reponse.getMessage()).isNotBlank();
                    })
                    .verifyComplete();

            assertThat(admin.getTotpEnabled()).isTrue();
        }

        @Test
        @DisplayName("un enrolement sur un compte deja actif est refuse, de facon NOMMEE")
        void deuxiemeEnrolementRefuse() {
            StepVerifier.create(service.enroler(admin))
                    .verifyErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(AuthException.class)
                            // 2FA_9002 et non AUTH_1001 : pour arriver ici,
                            // l'appelant a deja prouve le mot de passe, donc
                            // « deja actif » ne lui apprend rien. Le refus
                            // uniforme avait envoye deux personnes chercher du
                            // cote du mot de passe.
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.TWO_FA_ALREADY_ENABLED));
        }

        @Test
        @DisplayName("un compte sans secret est refuse et AUCUN secret n'est fabrique")
        void secretAbsentRefuse() {
            // Le secret est pose a la creation du compte. S'il manque, c'est une
            // anomalie du semis : la fabriquer ici la rendrait invisible.
            admin.setTotpEnabled(false);
            admin.setTotpSecret(null);

            StepVerifier.create(service.enroler(admin))
                    .verifyError(AuthException.class);

            assertThat(admin.getTotpSecret()).isNull();
            assertThat(admin.getTotpEnabled()).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Reinitialisation par l'administrateur lui-meme")
    class Reinitialisation {

        @Test
        @DisplayName("le secret est REMPLACE, pas reservi")
        void secretRemplace() {
            StepVerifier.create(service.reinitialiser(admin))
                    .assertNext(reponse -> {
                        assertThat(reponse.getSecret()).isEqualTo(SECRET_NEUF);
                        assertThat(reponse.getSecret())
                                .withFailMessage("l'ancien secret est reservi : "
                                        + "l'appareil que l'administrateur voulait "
                                        + "remplacer continue de produire des codes "
                                        + "valides, donc la reinitialisation n'a aucun "
                                        + "effet")
                                .isNotEqualTo(SECRET_ACTUEL);
                    })
                    .verifyComplete();

            assertThat(admin.getTotpSecret()).isEqualTo(SECRET_NEUF);
            assertThat(admin.getTotpEnabled()).isTrue();
        }

        @Test
        @DisplayName("le second facteur reste ACTIF apres remplacement")
        void resteActif() {
            // Le laisser inactif jusqu'a la premiere connexion reussie ouvrirait
            // une fenetre ou `verifier` laisse passer sans code. Une fenetre sans
            // second facteur sur un compte d'administration coute plus cher qu'un
            // enrolement rate, dont la sortie existe (retrait par un SUPER_ADMIN).
            StepVerifier.create(service.reinitialiser(admin)).expectNextCount(1).verifyComplete();

            StepVerifier.create(service.verifier(admin, null, null))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("reinitialiser sans second facteur actif est refuse")
        void sansSecondFacteurRefuse() {
            // Repondre « reinitialise » a qui n'a pas de second facteur lui
            // ferait croire que son compte est protege alors qu'il ne l'est pas.
            admin.setTotpEnabled(false);

            StepVerifier.create(service.reinitialiser(admin))
                    .verifyErrorSatisfies(e -> assertThat(e)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.TWO_FA_NOT_ENABLED));

            assertThat(admin.getTotpSecret()).isEqualTo(SECRET_ACTUEL);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Retrait par un super administrateur")
    class Retrait {

        @Test
        @DisplayName("le second facteur est desactive")
        void desactive() {
            StepVerifier.create(service.retirer(admin)).verifyComplete();

            assertThat(admin.getTotpEnabled()).isFalse();
        }

        @Test
        @DisplayName("un secret NEUF est pose au passage")
        void secretNeufPose() {
            // Sans cela, l'administrateur re-enrolerait l'ANCIEN secret — celui
            // que porte encore l'appareil perdu, peut-etre vole, qui est
            // precisement la raison du retrait. Le retrait annulerait son propre
            // motif.
            StepVerifier.create(service.retirer(admin)).verifyComplete();

            assertThat(admin.getTotpSecret())
                    .withFailMessage("l'ancien secret est conserve : l'appareil perdu "
                            + "redeviendra valide des le re-enrolement")
                    .isEqualTo(SECRET_NEUF);
        }

        @Test
        @DisplayName("le retrait ne rend AUCUN secret")
        void aucunSecretRendu() {
            // Signature a Mono<Void> : le super administrateur peut retirer le
            // second facteur d'un autre administrateur, non s'y substituer.
            // Verrouille par le type, et rappele ici pour que la raison survive
            // a un changement de signature.
            StepVerifier.create(service.retirer(admin))
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
    private static com.abdatytch.auth_service.metrics.AuthMetrics metriques() {
        return new com.abdatytch.auth_service.metrics.AuthMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
}
