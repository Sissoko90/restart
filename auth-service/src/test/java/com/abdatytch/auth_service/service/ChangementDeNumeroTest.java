package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.mappers.VerificationResponseMapper;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import com.abdatytch.auth_service.service.impl.ConfirmationDeContactServiceImpl;
import com.abdatytch.auth_service.service.impl.MotDePasseServiceImpl;
import com.abdatytch.auth_service.testutil.AlertesDEssai;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.abdatytch.auth_service.testutil.RevocationDEssai.revocationQuiNeFaitRien;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le remplacement du numéro de téléphone.
 *
 * <h2>Pourquoi cette opération est la plus sensible du libre-service</h2>
 *
 * Le numéro n'est pas seulement un identifiant de connexion : c'est le canal de
 * `/auth/forgot-password`. Qui le remplace prend le contrôle de la
 * réinitialisation du mot de passe, donc du compte entier — <b>sans jamais
 * connaître le mot de passe actuel</b>.
 *
 * <h2>Les quatre propriétés mesurées ici</h2>
 *
 * <ol>
 *   <li>le code part au <b>NOUVEAU</b> numéro : lui seul prouve qu'on le
 *       détient. L'envoyer à l'ancien laisserait passer une faute de frappe, et
 *       enfermerait la personne hors de la récupération par SMS ;</li>
 *   <li>le compte <b>garde</b> son numéro jusqu'à confirmation : l'opération
 *       s'annule par l'inaction ;</li>
 *   <li>le mot de passe actuel est exigé, et son échec est <b>compté</b> —
 *       sinon ce point d'entrée devient un oracle depuis une session volée ;</li>
 *   <li>l'<b>ANCIEN</b> numéro est prévenu, par SMS : c'est son titulaire qui
 *       vient d'être dépossédé.</li>
 * </ol>
 */
@DisplayName("Remplacement du numero : le code part au NOUVEAU, l'ANCIEN est prevenu")
class ChangementDeNumeroTest {

    private static final String ID = "client-1";
    private static final String ANCIEN_NUMERO = "+22374427759";
    private static final String NOUVEAU_NUMERO = "+22376543210";
    private static final String INDICATIF = "+223";
    private static final String MOT_DE_PASSE = "SecurePass123!";
    private static final String CODE = "123456";
    private static final String IP = "41.223.10.5";

    /** Les codes demandes : (numero, type). */
    private record Demande(String destinataire, VerificationCodeType type) {
    }

    private List<Demande> demandes;
    private UserRepository userRepository;
    private AlertesDEssai.Muettes alertes;
    private ConfirmationDeContactServiceImpl service;
    private User compte;

    @BeforeEach
    void setUp() {
        demandes = new ArrayList<>();
        userRepository = mock(UserRepository.class);
        alertes = AlertesDEssai.aucuneAlerte();

        compte = new User();
        compte.setId(ID);
        compte.setPhoneNumber(ANCIEN_NUMERO);
        compte.setCountryCode(INDICATIF);
        compte.setEmail("awa@exemple.test");
        compte.setIsPhoneVerified(true);
        compte.setIsEmailVerified(true);
        compte.setIsActive(true);
        compte.setIsLocked(false);
        compte.setFailedLoginAttempts(0);
        compte.setPasswordHash(new BCryptPasswordEncoder(4).encode(MOT_DE_PASSE));

        when(userRepository.findById(ID)).thenReturn(Mono.just(compte));
        when(userRepository.save(any(User.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));
        when(userRepository.existsByPhoneNumber(anyString())).thenReturn(Mono.just(false));

        IVerificationCodeService codes = mock(IVerificationCodeService.class);
        when(codes.generateAndSendCode(anyString(), any(), any()))
                .thenAnswer(appel -> {
                    demandes.add(new Demande(appel.getArgument(0), appel.getArgument(2)));
                    return Mono.just(new VerificationCode());
                });
        when(codes.validateCode(anyString(), anyString(), any()))
                .thenAnswer(appel -> CODE.equals(appel.getArgument(1))
                        ? Mono.empty()
                        : Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_INVALID)));

        AccountLockoutServiceImpl verrouillage = new AccountLockoutServiceImpl(
                userRepository, new AuthMetrics(new SimpleMeterRegistry()),
                revocationQuiNeFaitRien());
        ReflectionTestUtils.setField(verrouillage, "maxAttempts", 5);
        ReflectionTestUtils.setField(verrouillage, "lockoutDurationMinutes", 30);

        service = new ConfirmationDeContactServiceImpl(
                userRepository, codes, new VerificationResponseMapper(),
                smsInerte(),
                new MotDePasseServiceImpl(new BCryptPasswordEncoder(4)),
                verrouillage, alertes);
    }

    /**
     * Messagerie qui n'envoie rien, mais dont l'interrupteur est ACTIF.
     *
     * <p>La combinaison est volontaire, et c'est elle que l'ancien bouchon ne
     * pouvait pas exprimer : son {@code sendSms} rendait un {@code Mono<Void>}
     * vide, indistinguable d'un envoi reussi, tandis que son
     * {@code envoiActif()} rendait {@code true}. Ces tests ne portent pas sur
     * l'envoi — ils portent sur le changement de numero — d'ou un bouchon qui
     * laisse la formulation des reponses inchangee tout en ne remettant rien.
     *
     * <p>{@code false} au retour, donc : le bouchon dit desormais ce qu'il fait,
     * au lieu de rendre la valeur que rendait aussi un succes.
     */
    private static IMessagerieSortante smsInerte() {
        return new IMessagerieSortante() {
            @Override
            public Mono<Boolean> envoyer(Demande demande) {
                return Mono.just(false);
            }

            @Override
            public boolean envoiSmsActif() {
                return true;
            }
        };
    }

    private Mono<?> demander(String numero, String motDePasse) {
        return service.demanderChangementDeTelephone(ID, numero, INDICATIF, motDePasse);
    }

    // ==================================================================
    @Nested
    @DisplayName("Etape 1 : la demande")
    class Demande1 {

        @Test
        @DisplayName("le code part au NOUVEAU numero, jamais a l'ancien")
        void codeAuNouveauNumero() {
            // LE test de cette suite.
            //
            // Seul un code reçu sur le nouveau numero prouve qu'on le detient.
            // L'envoyer a l'ancien ne prouverait rien du nouveau, et une faute
            // de frappe passerait — enfermant la personne hors de la
            // recuperation par SMS, ce a quoi sert precisement un numero.
            StepVerifier.create(demander(NOUVEAU_NUMERO, MOT_DE_PASSE))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(demandes).hasSize(1);
            assertThat(demandes.get(0).destinataire()).isEqualTo(NOUVEAU_NUMERO);
            assertThat(demandes.get(0).destinataire()).isNotEqualTo(ANCIEN_NUMERO);
        }

        @Test
        @DisplayName("le type de code est PHONE_CHANGE, distinct de la confirmation")
        void typeDistinct() {
            // Reutiliser PHONE_CONFIRMATION permettrait de confirmer un
            // changement avec un code demande pour une simple verification.
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();

            assertThat(demandes.get(0).type())
                    .isEqualTo(VerificationCodeType.PHONE_CHANGE);
        }

        @Test
        @DisplayName("le compte GARDE son numero actuel")
        void numeroInchangeApresLaDemande() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();

            // L'operation s'annule par l'inaction : rien n'est perdu si la
            // personne ne confirme jamais.
            assertThat(compte.getPhoneNumber()).isEqualTo(ANCIEN_NUMERO);
            assertThat(compte.getNumeroEnAttente()).isEqualTo(NOUVEAU_NUMERO);
            assertThat(compte.getNumeroEnAttenteJusquA()).isNotNull();
        }

        @Test
        @DisplayName("un mot de passe FAUX est refuse, et l'echec est COMPTE")
        void motDePasseFauxCompte() {
            StepVerifier.create(demander(NOUVEAU_NUMERO, "PasLeBon123!"))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.INVALID_CREDENTIALS));

            // Sans frein, qui detient un jeton vole eprouve les mots de passe
            // sans limite, et ce point d'entree devient un oracle.
            assertThat(compte.getFailedLoginAttempts()).isEqualTo(1);
            assertThat(compte.getNumeroEnAttente()).isNull();
            assertThat(demandes).isEmpty();
        }

        @Test
        @DisplayName("un compte SANS mot de passe est refuse en VAL_6009")
        void compteSansMotDePasse() {
            // Compte cree par OAuth2. Laisser passer sur la seule session
            // serait pire que refuser : le numero etant le canal de la
            // reinitialisation, un jeton d'acces vole suffirait a prendre le
            // controle definitif du compte.
            compte.setPasswordHash(null);

            StepVerifier.create(demander(NOUVEAU_NUMERO, MOT_DE_PASSE))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.MOT_DE_PASSE_ABSENT_DU_COMPTE));

            assertThat(demandes).isEmpty();
        }

        @Test
        @DisplayName("un numero deja pris est refuse")
        void numeroDejaPris() {
            when(userRepository.existsByPhoneNumber(NOUVEAU_NUMERO))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(demander(NOUVEAU_NUMERO, MOT_DE_PASSE))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.PHONE_ALREADY_EXISTS));

            assertThat(demandes).isEmpty();
        }

        @Test
        @DisplayName("demander son NUMERO ACTUEL est refuse")
        void memeNumeroRefuse() {
            // Repondre « code envoye » enverrait un SMS payant pour rien, et
            // laisserait croire a une operation en cours.
            StepVerifier.create(demander(ANCIEN_NUMERO, MOT_DE_PASSE))
                    .verifyError(AuthException.class);

            assertThat(demandes).isEmpty();
        }

        @Test
        @DisplayName("un compte VERROUILLE ne peut pas changer de numero")
        void compteVerrouille() {
            // Sans ce controle, un compte verrouille s'echapperait par la
            // reinitialisation : il lui suffirait de pointer le canal de
            // recuperation vers un numero qu'il controle.
            compte.setIsLocked(true);
            compte.setLockedUntil(LocalDateTime.now().plusMinutes(30));

            StepVerifier.create(demander(NOUVEAU_NUMERO, MOT_DE_PASSE))
                    .verifyError(AuthException.class);

            assertThat(demandes).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Etape 2 : la confirmation")
    class Confirmation {

        private Mono<Void> confirmer(String code) {
            return service.confirmerChangementDeTelephone(
                    ID, code, ContexteDeSecurite.de(IP, "Mozilla/5.0"));
        }

        @Test
        @DisplayName("le numero est remplace, et marque VERIFIE")
        void numeroRemplace() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();

            StepVerifier.create(confirmer(CODE)).verifyComplete();

            assertThat(compte.getPhoneNumber()).isEqualTo(NOUVEAU_NUMERO);
            assertThat(compte.getCountryCode()).isEqualTo(INDICATIF);
            // La personne vient de prouver qu'elle detient ce numero en
            // recevant le code dessus : exiger une confirmation de plus ne
            // prouverait rien de plus.
            assertThat(compte.getIsPhoneVerified()).isTrue();
        }

        @Test
        @DisplayName("l'ANCIEN numero est prevenu, jamais le nouveau")
        void ancienNumeroPrevenu() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();
            confirmer(CODE).block();

            // L'entite porte deja le NOUVEAU numero a l'instant de l'alerte :
            // la relire enverrait l'avis a celui qui vient de le choisir.
            assertThat(alertes.evenements())
                    .containsExactly("numero:" + ANCIEN_NUMERO + ":verifie=true");
            assertThat(alertes.evenements().get(0)).doesNotContain(NOUVEAU_NUMERO);
        }

        @Test
        @DisplayName("la demande est CONSOMMEE : le meme code ne resservira pas")
        void demandeConsommee() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();
            confirmer(CODE).block();

            assertThat(compte.getNumeroEnAttente()).isNull();
            assertThat(compte.getNumeroEnAttenteJusquA()).isNull();

            // Sans cette remise a zero, le meme code resservirait tant que
            // l'echeance tient.
            StepVerifier.create(confirmer(CODE)).verifyError(AuthException.class);
        }

        @Test
        @DisplayName("sans demande en cours, la confirmation est refusee")
        void sansDemande() {
            StepVerifier.create(confirmer(CODE))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.VALIDATION_ERROR));

            assertThat(compte.getPhoneNumber()).isEqualTo(ANCIEN_NUMERO);
            assertThat(alertes.evenements()).isEmpty();
        }

        @Test
        @DisplayName("une demande EXPIREE est refusee")
        void demandeExpiree() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();
            // Une attente sans fin se ferait activer par un code intercepte
            // bien plus tard.
            compte.setNumeroEnAttenteJusquA(LocalDateTime.now().minusMinutes(1));

            StepVerifier.create(confirmer(CODE)).verifyError(AuthException.class);

            assertThat(compte.getPhoneNumber()).isEqualTo(ANCIEN_NUMERO);
        }

        @Test
        @DisplayName("un code FAUX ne remplace rien")
        void codeFaux() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();

            StepVerifier.create(confirmer("000000")).verifyError(AuthException.class);

            assertThat(compte.getPhoneNumber()).isEqualTo(ANCIEN_NUMERO);
            assertThat(alertes.evenements()).isEmpty();
        }

        @Test
        @DisplayName("un numero PRIS entre la demande et la confirmation est refuse")
        void prisEntreTemps() {
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();
            // Un autre compte a revendique ce numero depuis : la contrainte ne
            // se verifie utilement qu'a l'instant de l'ecriture.
            when(userRepository.existsByPhoneNumber(NOUVEAU_NUMERO))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(confirmer(CODE))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .isInstanceOf(AuthException.class)
                            .hasFieldOrPropertyWithValue("errorCode",
                                    ErrorCode.PHONE_ALREADY_EXISTS));

            assertThat(compte.getPhoneNumber()).isEqualTo(ANCIEN_NUMERO);
        }

        @Test
        @DisplayName("un ancien numero NON verifie n'empeche pas le remplacement")
        void ancienNonVerifie() {
            // L'alerte le signale par « verifie=false » : le service d'alerte
            // sautera alors le SMS et basculera sur le courriel. Mais le
            // remplacement lui-meme aboutit.
            compte.setIsPhoneVerified(false);
            demander(NOUVEAU_NUMERO, MOT_DE_PASSE).block();

            StepVerifier.create(confirmer(CODE)).verifyComplete();

            assertThat(compte.getPhoneNumber()).isEqualTo(NOUVEAU_NUMERO);
            assertThat(alertes.evenements())
                    .containsExactly("numero:" + ANCIEN_NUMERO + ":verifie=false");
        }
    }
}
