package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.TemplateType;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.VerificationCodeRepository;
import com.abdatytch.auth_service.service.impl.VerificationCodeServiceImpl;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    @Mock
    private ISmsService smsService;
    @Mock
    private IEmailService emailService;
    @Mock
    private IMessageTemplateService templateService;
    @Mock
    private AuthMetrics authMetrics;

    private VerificationCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VerificationCodeServiceImpl(
                verificationCodeRepository, smsService, emailService, templateService, authMetrics);

        when(templateService.formatTemplate(any(TemplateType.class), any()))
                .thenReturn(Mono.just("Votre code est 123456"));
        when(smsService.sendSms(anyString(), anyString())).thenReturn(Mono.empty());
        when(emailService.sendVerificationEmail(anyString(), anyString(), any()))
                .thenReturn(Mono.empty());
        when(verificationCodeRepository.deleteByPhoneNumberAndType(anyString(), any()))
                .thenReturn(Mono.empty());
        when(verificationCodeRepository.save(any(VerificationCode.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
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
                .assertNext(saved -> {
                    assertThat(saved.getCode()).hasSize(6).containsOnlyDigits();
                    assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now());
                    assertThat(saved.getIsUsed()).isFalse();
                })
                .verifyComplete();

        verify(smsService).sendSms(eq(PHONE), anyString());
        verify(authMetrics).recordOtpSent("sms");
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
        verify(smsService, never()).sendSms(anyString(), anyString());
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

        verify(smsService, never()).sendSms(anyString(), anyString());
        verify(emailService).sendVerificationEmail(eq(EMAIL), anyString(), eq(VerificationCodeType.REGISTRATION));
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

    @Test
    @DisplayName("l'échec de l'email de courtoisie ne casse pas l'envoi SMS")
    void courtesyEmailFailureDoesNotBreakSmsFlow() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.REGISTRATION))
                .thenReturn(Mono.empty());
        when(emailService.sendVerificationEmail(anyString(), anyString(), any()))
                .thenReturn(Mono.error(new RuntimeException("SMTP indisponible")));

        StepVerifier.create(service.generateAndSendCode(PHONE, EMAIL, VerificationCodeType.REGISTRATION))
                .expectNextCount(1)
                .verifyComplete();

        verify(smsService).sendSms(eq(PHONE), anyString());
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

    @Test
    @DisplayName("le template SMS dépend du type de code")
    void smsTemplateDependsOnCodeType() {
        when(verificationCodeRepository.findByPhoneNumberAndType(PHONE, VerificationCodeType.PASSWORD_RESET))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.generateAndSendCode(PHONE, null, VerificationCodeType.PASSWORD_RESET))
                .expectNextCount(1)
                .verifyComplete();

        verify(templateService).formatTemplate(eq(TemplateType.SMS_PASSWORD_RESET), any(Map.class));
    }
}
