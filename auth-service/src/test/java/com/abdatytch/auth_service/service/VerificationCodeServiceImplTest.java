package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.VerificationCodeRepository;
import com.abdatytch.auth_service.service.impl.VerificationCodeServiceImpl;
import com.abdatytch.commons.notification.CanalDeNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.abdatytch.commons.notification.IMessagerieSortante;

/**
 * Tests des codes de vérification à usage unique (OTP).
 *
 * <p>Couvre les garde-fous de sécurité : anti-spam, usage unique, expiration,
 * plafond de tentatives, et acheminement SMS/email selon le parcours.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VerificationCodeServiceImpl - Tests Unitaires")
class VerificationCodeServiceImplTest {

    private static final String PHONE = "+22300000000";
    private static final String EMAIL = "dev@example.com";
    private static final String CODE = "123456";

    @Mock
    private VerificationCodeRepository verificationCodeRepository;
    /**
     * UNE dependance d'envoi, et il y en avait trois.
     *
     * <p>{@code ISmsService}, {@code IEmailService} et
     * {@code IMessageTemplateService} ont ete remplaces par
     * {@link IMessagerieSortante}. Les tests y gagnent une verification qu'ils
     * ne pouvaient pas faire : le MODELE demande, pour les deux canaux. L'ancien
     * test ne contrôlait le modele que du cote SMS — par
     * {@code formatTemplate(SMS_PASSWORD_RESET, ...)} — et jamais celui du
     * courriel, qui etait choisi dans un second switch, ailleurs.
     */
    @Mock
    private IMessagerieSortante messagerie;
    @Mock
    private AuthMetrics authMetrics;

    private VerificationCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VerificationCodeServiceImpl(
                verificationCodeRepository, messagerie, authMetrics);

        // Remise reussie par defaut. Un bouchon doit desormais CHOISIR : avec
        // l'ancien Mono<Void>, « termine » et « rien n'est parti » etaient
        // indistinguables, et un test ne pouvait pas exprimer l'un sans l'autre.
        when(messagerie.envoyer(any())).thenReturn(Mono.just(true));
        when(verificationCodeRepository.deleteByPhoneNumberAndType(anyString(), any()))
                .thenReturn(Mono.empty());
        when(verificationCodeRepository.save(any(VerificationCode.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    }

    /**
     * Les messages reellement demandes a la messagerie.
     *
     * <p>Un capteur plutot que des {@code verify} par appel : il permet
     * d'affirmer le CANAL, le DESTINATAIRE et le MODELE d'un seul tenant, et de
     * constater l'ABSENCE d'un canal sans dependre de la signature d'une
     * methode disparue.
     */
    private List<IMessagerieSortante.Demande> messagesDemandes() {
        ArgumentCaptor<IMessagerieSortante.Demande> capteur =
                ArgumentCaptor.forClass(IMessagerieSortante.Demande.class);
        verify(messagerie, atLeast(0)).envoyer(capteur.capture());
        return capteur.getAllValues();
    }

    /**
     * L'entite reellement PERSISTEE.
     *
     * <p>Lue par capteur sur {@code save}, et non dans la valeur de retour : le
     * service rend desormais ce qui est PARTI, pas ce qui est enregistre. Et
     * c'est le bon endroit pour ces assertions — ce qui compte d'un code est ce
     * que la validation relira en base, pas ce qu'une methode a renvoye.
     */
    private VerificationCode codeEnregistre() {
        ArgumentCaptor<VerificationCode> capteur =
                ArgumentCaptor.forClass(VerificationCode.class);
        verify(verificationCodeRepository, atLeast(1)).save(capteur.capture());
        return capteur.getValue();
    }

    private VerificationCode validCode() {
        return VerificationCode.builder()
                .phoneNumber(PHONE)
                .code(CODE)
                .type(VerificationCodeType.REGISTRATION)
                .isUsed(false)
                .attempts(0)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    // ==========================================================
    // Génération
    // ==========================================================

    @Test
    @DisplayName("génère un code à 6 chiffres et l'envoie par SMS")
    void generatesSixDigitCodeAndSendsSms() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.generateAndSendCode(PHONE, null, VerificationCodeType.REGISTRATION))
                // Le resultat porte desormais CE QUI EST PARTI, et non l'entite
                // enregistree — qu'aucun des huit appelants n'utilisait.
                .assertNext(envoi -> {
                    assertThat(envoi.smsAccepte()).isTrue();
                    // null et non false : aucun courriel n'a ete TENTE, ce qui
                    // n'est pas un echec. Les confondre ferait croire a une
                    // panne de courriel sur chaque inscription par telephone.
                    assertThat(envoi.parCourriel()).isNull();
                })
                .verifyComplete();

        // Les proprietes du code sont verifiees sur ce qui est PERSISTE, par
        // capteur sur `save`. C'est plus juste que de les lire dans la valeur
        // de retour : ce qui compte est ce que la validation relira en base.
        VerificationCode enregistre = codeEnregistre();
        assertThat(enregistre.getCode()).hasSize(6).containsOnlyDigits();
        assertThat(enregistre.getExpiresAt()).isAfter(LocalDateTime.now());
        assertThat(enregistre.getIsUsed()).isFalse();

        assertThat(messagesDemandes())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.canal()).isEqualTo(CanalDeNotification.SMS);
                    assertThat(message.destinataire()).isEqualTo(PHONE);
                    // Le MODELE, que l'ancien test ne pouvait pas voir ici.
                    assertThat(message.modele())
                            .isEqualTo(AppConstants.MODELE_CODE_DE_VERIFICATION);
                });
        verify(authMetrics).recordOtpSent("sms");
    }

    /**
     * Un envoi REFUSE ne compte pas comme envoye.
     *
     * <h4>Le defaut que ce test ferme, et pourquoi il manquait</h4>
     *
     * La metrique etait posee dans un {@code doOnSuccess} sur un
     * {@code Mono<Void>} : elle comptait un envoi par APPEL, quoi qu'il arrive.
     * Un plafond journalier atteint ou un numero hors liste autorisee
     * produisait le meme increment qu'un envoi reussi, et le panneau Grafana
     * {@code sum(auth_otp_sent_total) by (channel)} affichait des envois qui
     * n'avaient pas eu lieu.
     *
     * <p>Le test du cas heureux ne pouvait pas l'attraper : il verifiait que la
     * metrique EST posee quand tout va bien. C'est la moitie de la regle. Une
     * mutation l'a montre — rendre le comptage inconditionnel laissait les
     * quatorze tests verts.
     *
     * <p>Etiquette en LITTERAL, comme ailleurs dans ce depot : « sms » est le
     * contrat avec le panneau Grafana, et une constante Java laisserait un
     * renommage couper la serie sans qu'aucun test ne bronche.
     */
    @Test
    @DisplayName("envoi refusé : la métrique ne compte RIEN")
    void envoiRefuseNeComptePas() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());
        when(messagerie.envoyer(any())).thenReturn(Mono.just(false));

        StepVerifier.create(service.generateAndSendCode(PHONE, null, VerificationCodeType.REGISTRATION))
                .assertNext(envoi -> assertThat(envoi.smsAccepte()).isFalse())
                .verifyComplete();

        verify(authMetrics, never()).recordOtpSent("sms");
        verify(authMetrics, never()).recordOtpSent("email");
    }

    /**
     * Un parcours telephone+courriel compte DEUX envois, un par canal.
     *
     * <p>L'ancien code en comptait UN, etiquete depuis
     * {@code email != null ? "email" : "sms"} : le SMS disparaissait purement
     * de la mesure sur le cas le plus courant — le SMS et son doublon de
     * courtoisie par courriel.
     */
    @Test
    @DisplayName("SMS et courriel : la métrique compte les deux canaux")
    void deuxCanauxComptentDeuxFois() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.generateAndSendCode(PHONE, EMAIL, VerificationCodeType.REGISTRATION))
                .assertNext(envoi -> {
                    assertThat(envoi.smsAccepte()).isTrue();
                    assertThat(envoi.courrielAccepte()).isTrue();
                })
                .verifyComplete();

        verify(authMetrics).recordOtpSent("sms");
        verify(authMetrics).recordOtpSent("email");
    }

    @Test
    @DisplayName("anti-spam : refuse un nouveau code tant que le précédent est valide")
    void refusesWhilePreviousCodeStillValid() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.just(validCode()));

        StepVerifier.create(service.generateAndSendCode(PHONE, null, VerificationCodeType.REGISTRATION))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VERIFICATION_CODE_PENDING))
                .verify();

        verify(verificationCodeRepository, never()).save(any());
        assertThat(messagesDemandes())
                .withFailMessage("un message a ete demande alors que le code a "
                        + "ete refuse : l'anti-spam ne protege plus la depense")
                .isEmpty();
    }

    @Test
    @DisplayName("un code expiré n'empêche pas d'en redemander un")
    void expiredCodeAllowsRegeneration() {
        VerificationCode expired = validCode();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.just(expired));

        StepVerifier.create(service.generateAndSendCode(PHONE, null, VerificationCodeType.REGISTRATION))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    @DisplayName("inscription par email seul : aucun SMS, uniquement l'email")
    void emailOnlyFlowSkipsSms() {
        String tempPhone = "temp_12345";
        when(verificationCodeRepository.findByPhoneNumberAndType(tempPhone, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.generateAndSendCode(tempPhone, EMAIL, VerificationCodeType.REGISTRATION))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(messagesDemandes())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.canal()).isEqualTo(CanalDeNotification.COURRIEL);
                    assertThat(message.destinataire()).isEqualTo(EMAIL);
                    assertThat(message.modele())
                            .isEqualTo(AppConstants.MODELE_CODE_DE_VERIFICATION);
                });
    }

    @Test
    @DisplayName("inscription par email seul sans email : erreur explicite")
    void emailOnlyFlowWithoutEmailFails() {
        String tempPhone = "temp_12345";
        when(verificationCodeRepository.findByPhoneNumberAndType(tempPhone, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.generateAndSendCode(tempPhone, null, VerificationCodeType.REGISTRATION))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.MISSING_REQUIRED_FIELD))
                .verify();
    }

    /**
     * Un courriel de courtoisie REFUSE ne casse rien.
     *
     * <p>C'est le cas nominal du nouveau contrat : la messagerie rend
     * {@code false}, sans lever.
     */
    @Test
    @DisplayName("un courriel de courtoisie refusé ne casse pas l'envoi SMS")
    void courtesyEmailRefusedDoesNotBreakSmsFlow() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());
        when(messagerie.envoyer(any())).thenAnswer(appel -> {
            IMessagerieSortante.Demande message = appel.getArgument(0);
            return Mono.just(message.canal() == CanalDeNotification.SMS);
        });

        StepVerifier.create(service.generateAndSendCode(PHONE, EMAIL, VerificationCodeType.REGISTRATION))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(messagesDemandes())
                .extracting(IMessagerieSortante.Demande::canal)
                .containsExactly(CanalDeNotification.SMS, CanalDeNotification.COURRIEL);
    }

    /**
     * Et un courriel de courtoisie qui LEVE ne casse rien non plus.
     *
     * <p>{@code IMessagerieSortante} promet de ne jamais lever. Ce test simule
     * la violation de cette promesse, parce que la consequence serait grave :
     * l'inscription echouerait APRES l'enregistrement du code, donc le code
     * serait en base et perdu pour l'utilisateur.
     *
     * <p>C'est ce qui rend le {@code onErrorResume} de {@code sendCode} un
     * garde-fou atteint, et non du code defensif que personne n'exerce — la
     * distinction qui a fait retirer, dans la meme passe, une garde
     * « messageCourt == null » qu'aucun chemin ne pouvait declencher.
     */
    @Test
    @DisplayName("un courriel de courtoisie qui lève ne fait pas échouer l'inscription")
    void courtesyEmailThrowingDoesNotBreakSmsFlow() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());
        when(messagerie.envoyer(any())).thenAnswer(appel -> {
            IMessagerieSortante.Demande message = appel.getArgument(0);
            return message.canal() == CanalDeNotification.SMS
                    ? Mono.just(true)
                    : Mono.error(new RuntimeException("contrat viole"));
        });

        StepVerifier.create(service.generateAndSendCode(PHONE, EMAIL, VerificationCodeType.REGISTRATION))
                .expectNextCount(1)
                .verifyComplete();
    }

    // ==========================================================
    // Validation
    // ==========================================================

    @Test
    @DisplayName("code correct : marqué comme utilisé")
    void validCodeIsMarkedUsed() {
        when(verificationCodeRepository.findByPhoneNumberAndCodeAndType(
                PHONE, CODE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.just(validCode()));

        StepVerifier.create(service.validateCode(PHONE, CODE, VerificationCodeType.REGISTRATION))
                .verifyComplete();

        ArgumentCaptor<VerificationCode> saved = ArgumentCaptor.forClass(VerificationCode.class);
        verify(verificationCodeRepository).save(saved.capture());
        assertThat(saved.getValue().getIsUsed()).isTrue();
        assertThat(saved.getValue().getUsedAt()).isNotNull();
        assertThat(saved.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("code inexistant : refusé")
    void unknownCodeIsRejected() {
        when(verificationCodeRepository.findByPhoneNumberAndCodeAndType(anyString(), anyString(), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.validateCode(PHONE, "000000", VerificationCodeType.REGISTRATION))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID))
                .verify();
    }

    @Test
    @DisplayName("usage unique : un code déjà consommé est refusé")
    void alreadyUsedCodeIsRejected() {
        VerificationCode used = validCode();
        used.setIsUsed(true);
        when(verificationCodeRepository.findByPhoneNumberAndCodeAndType(
                PHONE, CODE, VerificationCodeType.REGISTRATION)).thenReturn(Mono.just(used));

        StepVerifier.create(service.validateCode(PHONE, CODE, VerificationCodeType.REGISTRATION))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VERIFICATION_CODE_USED))
                .verify();

        verify(verificationCodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("code expiré : refusé")
    void expiredCodeIsRejected() {
        VerificationCode expired = validCode();
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(verificationCodeRepository.findByPhoneNumberAndCodeAndType(
                PHONE, CODE, VerificationCodeType.REGISTRATION)).thenReturn(Mono.just(expired));

        StepVerifier.create(service.validateCode(PHONE, CODE, VerificationCodeType.REGISTRATION))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.VERIFICATION_CODE_EXPIRED))
                .verify();
    }

    @Test
    @DisplayName("plafond de tentatives atteint : refusé (anti-force brute)")
    void tooManyAttemptsIsRejected() {
        VerificationCode exhausted = validCode();
        exhausted.setAttempts(AppConstants.MAX_VERIFICATION_ATTEMPTS);
        when(verificationCodeRepository.findByPhoneNumberAndCodeAndType(
                PHONE, CODE, VerificationCodeType.REGISTRATION)).thenReturn(Mono.just(exhausted));

        StepVerifier.create(service.validateCode(PHONE, CODE, VerificationCodeType.REGISTRATION))
                .expectErrorSatisfies(error -> assertThat(((AuthException) error).getErrorCode())
                        .isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS))
                .verify();

        verify(verificationCodeRepository, never()).save(any());
    }

    @Test
    @DisplayName("validation par email : même règles que par téléphone")
    void emailValidationFollowsSameRules() {
        VerificationCode code = validCode();
        code.setEmail(EMAIL);
        when(verificationCodeRepository.findByEmailAndCodeAndType(
                EMAIL, CODE, VerificationCodeType.REGISTRATION)).thenReturn(Mono.just(code));

        StepVerifier.create(service.validateCodeByEmail(EMAIL, CODE, VerificationCodeType.REGISTRATION))
                .verifyComplete();

        verify(verificationCodeRepository).save(any(VerificationCode.class));
    }

    /**
     * Le modele depend du type de code — et le MEME modele sert aux deux canaux.
     *
     * <p>C'est tout l'apport du couple (modele, canal). L'ancien code portait
     * DEUX tables de correspondance — {@code getSmsTemplateType} ici et
     * {@code getTemplateTypeForVerification} dans {@code EmailServiceImpl} — a
     * tenir d'accord pour le meme message. Ce test ne pouvait en verifier
     * qu'une.
     */
    @Test
    @DisplayName("le modèle dépend du type de code, et vaut pour les deux canaux")
    void modeleDependsOnCodeType() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.PASSWORD_RESET))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.generateAndSendCode(PHONE, EMAIL, VerificationCodeType.PASSWORD_RESET))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(messagesDemandes())
                .hasSize(2)
                .allSatisfy(message -> assertThat(message.modele())
                        .withFailMessage("le canal %s n'emploie pas le modele de "
                                + "reinitialisation : les deux canaux doivent "
                                + "nommer le MEME objet", message.canal())
                        .isEqualTo(AppConstants.MODELE_REINITIALISATION))
                .extracting(IMessagerieSortante.Demande::canal)
                .containsExactly(CanalDeNotification.SMS, CanalDeNotification.COURRIEL);
    }
}
