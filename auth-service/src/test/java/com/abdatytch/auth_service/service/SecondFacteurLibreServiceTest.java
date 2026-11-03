package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl;
import com.abdatytch.commons.totp.TotpService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashSet;
import java.util.Set;

import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le second facteur qu'un client active lui-meme, en deux etapes.
 *
 * <h2>Ce qui manquait</h2>
 *
 * {@code /auth/login} verifiait <b>deja</b> le TOTP pour tout le monde, et
 * laissait passer sans code si le compte n'en avait pas — le second facteur
 * etait donc bien facultatif. Mais seul {@code /auth/admin/totp/enroll}
 * existait : un client ne pouvait <b>jamais</b> l'activer. La verification
 * etait en place et inatteignable pour lui.
 *
 * <p>{@code enroler()} ne pouvait pas servir : il <b>reprend</b> un secret deja
 * pose et refuse volontairement d'en fabriquer un, pour qu'une anomalie du
 * semis des comptes d'administration reste visible. Un client n'a pas de secret
 * au depart.
 *
 * <h2>Pourquoi deux etapes, et non une</h2>
 *
 * C'est le compromis <b>inverse</b> de celui retenu pour les administrateurs,
 * ou {@code activerEtRemettre} active aussitot. Ce choix se justifie la-bas :
 * une fenetre sans second facteur sur un compte d'administration coute plus
 * qu'un enrolement rate, dont la sortie existe.
 *
 * <p>Ici, non. Un client n'avait aucun second facteur avant l'appel — il n'y a
 * pas de fenetre a refermer. Et la sortie d'un enrolement rate est
 * {@code retirer()}, qui demande un {@code SUPER_ADMIN} : tenable pour une
 * poignee d'administrateurs, intenable pour chaque client enferme hors de son
 * compte par un telephone a l'heure decalee.
 */
@DisplayName("Second facteur en libre-service")
class SecondFacteurLibreServiceTest {

    private static final String SECRET_POSE = "SECRETDEJAPOSE234567";
    private static final String SECRET_NEUF = "SECRETTOUTNEUF765432";
    private static final String CODE_VALIDE = "123456";
    private static final String CODE_FAUX = "000000";

    private UserRepository userRepository;
    private TotpService totpService;
    private SecondFacteurServiceImpl secondFacteur;
    private User compte;
    private Set<String> secretsProduits;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        totpService = mock(TotpService.class);
        secretsProduits = new HashSet<>();

        compte = new User();
        compte.setId("client-1");
        compte.setEmail("awa@exemple.test");
        compte.setPhoneNumber("+22374427759");
        compte.setTotpEnabled(false);
        compte.setTotpSecret(null);
        compte.setFailedLoginAttempts(0);
        compte.setIsLocked(false);

        when(userRepository.save(any(User.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Mono.just(compte));
        when(totpService.generateTotpSecret()).thenAnswer(appel -> {
            // Un secret different a chaque appel : c'est ce qui permet de
            // verifier qu'un secret n'est pas reservi.
            String neuf = SECRET_NEUF + secretsProduits.size();
            secretsProduits.add(neuf);
            return neuf;
        });
        when(totpService.generateQrCodeUrl(anyString(), anyString(), anyString()))
                .thenReturn("otpauth://totp/exemple");
        when(totpService.validateTotpCode(anyString(), anyString()))
                .thenAnswer(appel -> Mono.just(CODE_VALIDE.equals(appel.getArgument(1))));

        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(
                userRepository, metriques(), revocationQuiNeFaitRien());
        ReflectionTestUtils.setField(verrouillage, "maxAttempts", 5);
        ReflectionTestUtils.setField(verrouillage, "lockoutDurationMinutes", 30);

        secondFacteur = new SecondFacteurServiceImpl(
                userRepository, totpService, verrouillage,
                com.abdatytch.auth_service.testutil.CodesDeSecoursDEssai
                        .aucunCodeDeSecours(), metriques(), "AbdaTech");
    }

    private static AuthMetrics metriques() {
        return new AuthMetrics(new SimpleMeterRegistry());
    }

    // ==================================================================
    @Nested
    @DisplayName("Etape 1 : le secret est pose, rien n'est actif")
    class EtapeUn {

        /**
         * Le test central de cette suite.
         *
         * <p>Activer des l'etape 1 enfermerait la personne hors de son compte
         * au moindre ecart entre les deux etapes, et la seule sortie serait un
         * retrait par un {@code SUPER_ADMIN}.
         */
        @Test
        @DisplayName("demarrer l'enrolement ne rend PAS le second facteur actif")
        void demarrerNActivePas() {
            StepVerifier.create(secondFacteur.demarrerEnrolement(compte))
                    .assertNext(reponse -> {
                        assertThat(reponse.getSecret()).isNotBlank();
                        assertThat(reponse.getQrCodeUrl()).startsWith("otpauth://");
                    })
                    .verifyComplete();

            assertThat(compte.getTotpEnabled())
                    .withFailMessage("""
                            Le second facteur est actif des l'etape 1.

                            La personne n'a pas encore prouve qu'elle sait produire \
                            un code : un telephone a l'heure decalee ou un secret \
                            mal recopie l'enferme hors de son compte, et la seule \
                            sortie est un retrait par un SUPER_ADMIN.""")
                    .isFalse();
            assertThat(compte.getTotpSecret()).isNotBlank();
        }

        @Test
        @DisplayName("la connexion continue de passer sans code entre les deux etapes")
        void connexionPasseEntreLesDeuxEtapes() {
            // Consequence directe de l'etape 1 qui n'active pas : verifier()
            // laisse passer tant que totpEnabled est faux. C'est voulu — sinon
            // la personne serait bloquee a mi-parcours.
            secondFacteur.demarrerEnrolement(compte).block();

            StepVerifier.create(secondFacteur.verifier(compte, null, null))
                    .expectNext(compte)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un second facteur deja actif refuse un nouveau demarrage")
        void dejaActifRefuse() {
            // Rendre un secret neuf ici remplacerait en silence un second
            // facteur en service : l'appareil de la personne cesserait de
            // fonctionner sans qu'elle l'ait demande.
            compte.setTotpEnabled(true);
            compte.setTotpSecret(SECRET_POSE);

            StepVerifier.create(secondFacteur.demarrerEnrolement(compte))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.TWO_FA_ALREADY_ENABLED));
        }

        @Test
        @DisplayName("un secret abandonne n'est jamais reservi")
        void secretJamaisReservi() {
            // Reprendre un secret pose lors d'un demarrage abandonne le rendrait
            // une seconde fois : il aurait circule deux fois, et la premiere
            // reponse — restee dans un historique ou un journal client —
            // suffirait a produire des codes valides.
            String premier = secondFacteur.demarrerEnrolement(compte).block().getSecret();
            String second = secondFacteur.demarrerEnrolement(compte).block().getSecret();

            assertThat(second)
                    .withFailMessage("le meme secret a ete rendu deux fois")
                    .isNotEqualTo(premier);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Etape 2 : la preuve avant l'activation")
    class EtapeDeux {

        @Test
        @DisplayName("un code valide active le second facteur")
        void codeValideActive() {
            secondFacteur.demarrerEnrolement(compte).block();

            StepVerifier.create(secondFacteur.confirmerEnrolement(compte, CODE_VALIDE))
                    .verifyComplete();

            assertThat(compte.getTotpEnabled()).isTrue();
        }

        @Test
        @DisplayName("un code faux n'active rien, et compte comme un echec")
        void codeFauxNActivePas() {
            // Le comptage n'est pas optionnel : six chiffres font un million de
            // possibilites, epuisables par qui detient deja une session.
            secondFacteur.demarrerEnrolement(compte).block();

            StepVerifier.create(secondFacteur.confirmerEnrolement(compte, CODE_FAUX))
                    .verifyError(AuthException.class);

            assertThat(compte.getTotpEnabled()).isFalse();
            assertThat(compte.getFailedLoginAttempts())
                    .withFailMessage("un code faux n'a pas ete compte : la force "
                            + "brute sur six chiffres n'est alors pas freinee")
                    .isPositive();
        }

        @Test
        @DisplayName("confirmer sans avoir demarre est refuse")
        void confirmerSansDemarrer() {
            // Repondre « confirme » laisserait croire le compte protege alors
            // qu'aucun secret n'existe.
            compte.setTotpSecret(null);

            StepVerifier.create(secondFacteur.confirmerEnrolement(compte, CODE_VALIDE))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.TWO_FA_NOT_ENABLED));
        }

        @Test
        @DisplayName("un code absent est refuse avec une erreur nommee")
        void codeAbsentRefuse() {
            secondFacteur.demarrerEnrolement(compte).block();

            for (String absent : new String[]{null, "", "  "}) {
                StepVerifier.create(secondFacteur.confirmerEnrolement(compte, absent))
                        .verifyErrorSatisfies(erreur -> assertThat(
                                ((AuthException) erreur).getErrorCode())
                                .isEqualTo(ErrorCode.TWO_FACTOR_REQUIRED));
            }
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Desactivation : un code valide est exige")
    class Desactivation {

        /**
         * Pourquoi l'authentification ne suffit pas.
         *
         * <p>L'appelant est deja authentifie. S'en contenter viderait le second
         * facteur de son sens : qui vole un jeton d'acces pourrait retirer la
         * protection meme qui existe pour que le vol d'un jeton ne suffise pas.
         */
        @Test
        @DisplayName("sans code valide, la desactivation est refusee")
        void sansCodeValideRefuse() {
            compte.setTotpEnabled(true);
            compte.setTotpSecret(SECRET_POSE);

            StepVerifier.create(secondFacteur.desactiver(compte, CODE_FAUX, null))
                    .verifyError(AuthException.class);

            assertThat(compte.getTotpEnabled())
                    .withFailMessage("""
                            Le second facteur a ete desactive sans code valide.

                            Un jeton d'acces vole suffit alors a retirer la \
                            protection qui existe precisement pour qu'un jeton \
                            vole ne suffise pas.""")
                    .isTrue();
        }

        @Test
        @DisplayName("avec un code valide, la desactivation aboutit")
        void avecCodeValideAboutit() {
            compte.setTotpEnabled(true);
            compte.setTotpSecret(SECRET_POSE);

            StepVerifier.create(secondFacteur.desactiver(compte, CODE_VALIDE, null))
                    .verifyComplete();

            assertThat(compte.getTotpEnabled()).isFalse();
        }

        @Test
        @DisplayName("la desactivation pose un secret NEUF")
        void desactivationPoseUnSecretNeuf() {
            // Sans cela, un re-enrolement ulterieur remettrait en service le
            // secret que l'ancien appareil porte encore.
            compte.setTotpEnabled(true);
            compte.setTotpSecret(SECRET_POSE);

            secondFacteur.desactiver(compte, CODE_VALIDE, null).block();

            assertThat(compte.getTotpSecret())
                    .withFailMessage("l'ancien secret est conserve : un appareil "
                            + "retire continuerait de produire des codes valides "
                            + "apres un re-enrolement")
                    .isNotEqualTo(SECRET_POSE);
        }

        @Test
        @DisplayName("desactiver ce qui n'est pas actif est refuse")
        void desactiverCeQuiNEstPasActif() {
            // Un succes ferait croire qu'une protection vient d'etre retiree,
            // donc qu'elle existait.
            compte.setTotpEnabled(false);

            StepVerifier.create(secondFacteur.desactiver(compte, CODE_VALIDE, null))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.TWO_FA_NOT_ENABLED));
        }
    }
}
