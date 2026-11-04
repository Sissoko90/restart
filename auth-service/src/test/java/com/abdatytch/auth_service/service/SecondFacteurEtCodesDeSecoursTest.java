package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.CodeDeSecours;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.CodeDeSecoursRepository;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.CodeDeSecoursServiceImpl;
import com.abdatytch.auth_service.service.impl.MotDePasseServiceImpl;
import com.abdatytch.auth_service.service.impl.SecondFacteurServiceImpl;
import com.abdatytch.commons.totp.TotpService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La jonction entre le second facteur et les codes de secours.
 *
 * <h2>Le trou que cette jonction comble</h2>
 *
 * L'enrolement du second facteur est devenu accessible en libre-service. Sa
 * seule sortie etait {@code retirer()} par un {@code SUPER_ADMIN} : tenable pour
 * une poignee d'administrateurs, intenable des que des clients l'activent.
 *
 * <p>Les codes de secours ne suffisent pas a combler ce trou s'ils n'ouvrent que
 * la <b>connexion</b> : quelqu'un qui perd son telephone entrerait avec un code,
 * puis se retrouverait coince — {@code desactiver} et la reinitialisation
 * exigent toutes deux un code produit par l'appareil perdu. Un code de secours
 * doit donc valoir preuve partout ou un code TOTP vaut preuve.
 *
 * <h2>Les quatre proprietes mesurees ici</h2>
 *
 * <ol>
 *   <li>un code de secours satisfait le second facteur a la <b>connexion</b> ;</li>
 *   <li>il satisfait aussi une <b>operation sensible</b>, donc la desactivation
 *       du second facteur — c'est la sortie qui n'existait pas ;</li>
 *   <li>il ne satisfait <b>pas</b> la confirmation d'un enrolement, dont l'objet
 *       est justement de prouver que le nouvel appareil fonctionne ;</li>
 *   <li>le lot meurt avec le secret qu'il secourt.</li>
 * </ol>
 */
@DisplayName("Un code de secours vaut preuve la ou un code TOTP vaut preuve")
class SecondFacteurEtCodesDeSecoursTest {

    private static final String SECRET = "SECRETACTUELDUCOMPTE";
    private static final String CODE_TOTP_VALIDE = "123456";
    private static final String CODE_TOTP_FAUX = "000000";
    private static final String IP = "41.223.10.5";

    private UserRepository userRepository;
    private TotpService totpService;
    private List<CodeDeSecours> magasin;
    private CodeDeSecoursServiceImpl codesDeSecours;
    private SecondFacteurServiceImpl secondFacteur;
    private User compte;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        totpService = mock(TotpService.class);
        magasin = new ArrayList<>();

        compte = new User();
        compte.setId("client-1");
        compte.setEmail("awa@exemple.test");
        compte.setTotpSecret(SECRET);
        compte.setTotpEnabled(true);
        compte.setFailedLoginAttempts(0);
        compte.setIsLocked(false);

        when(userRepository.save(any(User.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
        when(totpService.generateTotpSecret()).thenReturn("SECRETTOUTNEUF765432");
        when(totpService.generateQrCodeUrl(anyString(), anyString(), anyString()))
                .thenReturn("otpauth://totp/exemple");
        when(totpService.validateTotpCode(anyString(), anyString()))
                .thenAnswer(appel -> Mono.just(CODE_TOTP_VALIDE.equals(appel.getArgument(1))));

        CodeDeSecoursRepository depot = mock(CodeDeSecoursRepository.class);
        when(depot.save(any(CodeDeSecours.class))).thenAnswer(appel -> {
            CodeDeSecours code = appel.getArgument(0);
            magasin.removeIf(present -> present.getId() != null
                    && present.getId().equals(code.getId()));
            magasin.add(code);
            return Mono.just(code);
        });
        when(depot.findByUserId(anyString()))
                .thenAnswer(appel -> Flux.fromIterable(pourLeCompte(appel.getArgument(0))));
        when(depot.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(anyString()))
                .thenAnswer(appel -> Flux.fromIterable(pourLeCompte(appel.getArgument(0))
                        .stream().filter(CodeDeSecours::estUtilisable).toList()));

        // Le VRAI service des codes, et un vrai BCrypt a force reduite : la
        // jonction mesuree ici est precisement celle que des simulacres
        // feraient disparaitre.
        codesDeSecours = new CodeDeSecoursServiceImpl(
                depot, new MotDePasseServiceImpl(new BCryptPasswordEncoder(4)), 10);

        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(
                userRepository, metriques(), revocationQuiNeFaitRien());
        ReflectionTestUtils.setField(verrouillage, "maxAttempts", 5);
        ReflectionTestUtils.setField(verrouillage, "lockoutDurationMinutes", 30);

        secondFacteur = new SecondFacteurServiceImpl(
                userRepository, totpService, verrouillage, codesDeSecours,
                metriques(), "Restart");
    }

    private List<CodeDeSecours> pourLeCompte(String userId) {
        return magasin.stream().filter(code -> userId.equals(code.getUserId())).toList();
    }

    private static AuthMetrics metriques() {
        return new AuthMetrics(new SimpleMeterRegistry());
    }

    private List<String> unLot() {
        var lot = codesDeSecours.regenerer(compte).block();
        assertThat(lot).isNotNull();
        return lot.getCodes();
    }

    @Nested
    @DisplayName("A la connexion")
    class Connexion {

        @Test
        @DisplayName("un code de secours satisfait le second facteur")
        void codeDeSecoursOuvreLaConnexion() {
            String code = unLot().get(5);

            StepVerifier.create(secondFacteur.verifier(compte, code, IP))
                    .assertNext(entre -> assertThat(entre.getId()).isEqualTo("client-1"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("le code presente est CONSOMME, avec l'adresse de l'appelant")
        void codeConsommeEtTrace() {
            String code = unLot().get(0);
            secondFacteur.verifier(compte, code, IP).block();

            CodeDeSecours ligne = magasin.stream()
                    .filter(present -> code.startsWith(present.getDebut()))
                    .findFirst().orElseThrow();
            assertThat(ligne.getConsommeLe()).isNotNull();
            // L'adresse remonte depuis le chemin de connexion jusqu'a la ligne :
            // c'est ce qui permet a la personne de dire « cet usage n'est pas le
            // mien ».
            assertThat(ligne.getConsommeIp()).isEqualTo(IP);
        }

        @Test
        @DisplayName("le MEME code de secours n'ouvre pas une seconde connexion")
        void usageUnique() {
            String code = unLot().get(0);
            secondFacteur.verifier(compte, code, IP).block();

            StepVerifier.create(secondFacteur.verifier(compte, code, IP))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("un code TOTP valide passe sans consommer aucun code de secours")
        void totpNeConsommeRien() {
            unLot();

            secondFacteur.verifier(compte, CODE_TOTP_VALIDE, IP).block();

            // Le TOTP est essaye d'abord : le cas courant ne paie aucune
            // comparaison BCrypt, et ne brule aucun code.
            assertThat(magasin).allMatch(CodeDeSecours::estUtilisable);
        }

        @Test
        @DisplayName("un code faux des DEUX sortes compte UN SEUL echec")
        void unSeulEchecCompte() {
            unLot();
            compte.setFailedLoginAttempts(0);

            StepVerifier.create(secondFacteur.verifier(compte, CODE_TOTP_FAUX, IP))
                    .verifyError(AuthException.class);

            // Deux comptages pour une tentative feraient arriver le
            // verrouillage a la moitie du nombre d'essais annonce.
            assertThat(compte.getFailedLoginAttempts()).isEqualTo(1);
        }

        @Test
        @DisplayName("un depot de codes illisible laisse le TOTP fonctionner")
        void depotIllisibleNEmpechePasLeTotp() {
            CodeDeSecoursRepository casse = mock(CodeDeSecoursRepository.class);
            when(casse.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(anyString()))
                    .thenReturn(Flux.error(new IllegalStateException("base indisponible")));
            SecondFacteurServiceImpl avecDepotCasse = new SecondFacteurServiceImpl(
                    userRepository, totpService,
                    new AccountLockoutServiceImpl(userRepository, metriques(),
                            revocationQuiNeFaitRien()),
                    new CodeDeSecoursServiceImpl(casse,
                            new MotDePasseServiceImpl(new BCryptPasswordEncoder(4)), 10),
                    metriques(), "Restart");

            // Une panne sur la table des codes ne doit pas fermer la connexion
            // par TOTP : le TOTP est verifie AVANT, et n'en depend pas.
            StepVerifier.create(avecDepotCasse.verifier(compte, CODE_TOTP_VALIDE, IP))
                    .expectNextCount(1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un depot illisible COMPTE quand meme l'echec d'un code faux")
        void depotIllisibleCompteLEchec() {
            CodeDeSecoursRepository casse = mock(CodeDeSecoursRepository.class);
            when(casse.findByUserIdAndConsommeLeIsNullAndInvalideLeIsNull(anyString()))
                    .thenReturn(Flux.error(new IllegalStateException("base indisponible")));
            AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(
                    userRepository, metriques(), revocationQuiNeFaitRien());
            ReflectionTestUtils.setField(verrouillage, "maxAttempts", 5);
            ReflectionTestUtils.setField(verrouillage, "lockoutDurationMinutes", 30);
            SecondFacteurServiceImpl avecDepotCasse = new SecondFacteurServiceImpl(
                    userRepository, totpService, verrouillage,
                    new CodeDeSecoursServiceImpl(casse,
                            new MotDePasseServiceImpl(new BCryptPasswordEncoder(4)), 10),
                    metriques(), "Restart");
            compte.setFailedLoginAttempts(0);

            StepVerifier.create(avecDepotCasse.verifier(compte, CODE_TOTP_FAUX, IP))
                    .verifyError(AuthException.class);

            // LE test de la correction : le comptage a d'abord ete place dans
            // consommer(), qui avale ses erreurs de lecture et rend false sans
            // rien compter. Une panne sur CETTE table rendait alors la force
            // brute sur le TOTP gratuite — le frein disparaissait en silence.
            assertThat(compte.getFailedLoginAttempts())
                    .as("le frein ne depend pas de la lisibilite de la table des codes")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Pour une operation sensible")
    class OperationSensible {

        @Test
        @DisplayName("un code de secours autorise l'operation")
        void codeDeSecoursAutorise() {
            String code = unLot().get(2);

            StepVerifier.create(
                    secondFacteur.confirmerPourUneOperation(compte, code, IP))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un code de secours DESACTIVE le second facteur")
        void desactivationAvecUnCodeDeSecours() {
            // La sortie qui n'existait pas. Sans elle, quelqu'un qui perd son
            // telephone entre avec un code de secours et reste coince : la
            // desactivation exigeait un code produit par l'appareil perdu, et la
            // seule issue etait un retrait par un SUPER_ADMIN — un humain jugeant
            // de l'identite de quelqu'un qui ne peut plus la prouver.
            String code = unLot().get(0);

            StepVerifier.create(secondFacteur.desactiver(compte, code, IP))
                    .verifyComplete();

            assertThat(compte.getTotpEnabled()).isFalse();
        }

        @Test
        @DisplayName("la desactivation INVALIDE le lot restant")
        void desactivationInvalideLeLot() {
            unLot();
            secondFacteur.desactiver(compte, CODE_TOTP_VALIDE, IP).block();

            // Un lot qui survit a la desactivation redeviendrait utilisable au
            // prochain enrolement — qui exige un code du NOUVEL appareil, donc
            // ne le touche pas. Un lot imprime et oublie serait une clef valide
            // des annees plus tard.
            assertThat(magasin).noneMatch(CodeDeSecours::estUtilisable);
            // INVALIDE, pas consomme : la personne n'a rien utilise.
            assertThat(magasin).allSatisfy(ligne ->
                    assertThat(ligne.getInvalideLe()).isNotNull());
        }

        @Test
        @DisplayName("un retrait par un super administrateur invalide aussi le lot")
        void retraitInvalideLeLot() {
            unLot();
            secondFacteur.retirer(compte).block();

            assertThat(magasin).noneMatch(CodeDeSecours::estUtilisable);
        }

        @Test
        @DisplayName("une reinitialisation invalide le lot de l'appareil remplace")
        void reinitialisationInvalideLeLot() {
            unLot();
            secondFacteur.reinitialiser(compte).block();

            // Une reinitialisation sert justement a remplacer un appareil perdu.
            // Garder les codes de l'ancien annulerait son propre motif.
            assertThat(magasin).noneMatch(CodeDeSecours::estUtilisable);
        }

        @Test
        @DisplayName("sans second facteur actif, l'operation est refusee malgre un code")
        void sansSecondFacteurRefuse() {
            String code = unLot().get(0);
            compte.setTotpEnabled(false);

            // Une operation sensible ne peut pas etre autorisee par une preuve
            // qui n'existe pas.
            StepVerifier.create(
                    secondFacteur.confirmerPourUneOperation(compte, code, IP))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.TWO_FA_NOT_ENABLED));
        }
    }

    @Nested
    @DisplayName("A la confirmation d'un enrolement")
    class Enrolement {

        @Test
        @DisplayName("un code de secours NE confirme PAS un enrolement")
        void codeDeSecoursRefuseALEnrolement() {
            // Le test le plus important de cette suite.
            //
            // L'objet de cette etape est de prouver que le NOUVEL appareil
            // produit les bons codes. Un code de secours ne prouve rien de
            // cela : l'enrolement serait declare confirme alors que
            // l'application d'authentification n'a jamais ete verifiee, et la
            // personne se retrouverait avec un second facteur actif qu'elle ne
            // peut pas produire — enfermee par la protection qu'elle venait
            // d'activer.
            String code = unLot().get(0);

            // Le compte repart d'un enrolement en cours : secret pose, pas actif.
            compte.setTotpEnabled(false);

            StepVerifier.create(secondFacteur.confirmerEnrolement(compte, code))
                    .verifyError(AuthException.class);

            assertThat(compte.getTotpEnabled()).isFalse();
            // Et le code n'est pas consomme : il n'a pas servi.
            assertThat(magasin).allMatch(CodeDeSecours::estUtilisable);
        }

        @Test
        @DisplayName("un code TOTP confirme l'enrolement")
        void codeTotpConfirme() {
            compte.setTotpEnabled(false);

            StepVerifier.create(
                    secondFacteur.confirmerEnrolement(compte, CODE_TOTP_VALIDE))
                    .verifyComplete();

            assertThat(compte.getTotpEnabled()).isTrue();
        }
    }
}
