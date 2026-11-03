package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.TemplateType;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.VerificationCodeRepository;
import com.abdatytch.auth_service.service.IEmailService;
import com.abdatytch.auth_service.service.IMessageTemplateService;
import com.abdatytch.auth_service.service.ISmsService;
import com.abdatytch.auth_service.service.IVerificationCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Génération, envoi et validation des codes de vérification (OTP).
 *
 * <p><b>Cache retiré.</b> Spring Cache ne gère pas les types réactifs : les
 * anciennes annotations {@code @Cacheable}/{@code @CacheEvict} auraient mis en cache
 * des {@code Mono} non souscrits. Sur des codes à durée de vie de quelques minutes,
 * lus une fois, le cache n'apportait rien et masquait des invalidations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VerificationCodeServiceImpl implements IVerificationCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Préfixe des numéros factices utilisés lors d'une inscription par email
     * seul. Déplacé dans {@link AppConstants#TEMP_PHONE_PREFIX} : un second
     * service en a besoin, et deux littéraux à maintenir d'accord auraient fini
     * par diverger.
     */
    private static final String TEMP_PHONE_PREFIX = AppConstants.TEMP_PHONE_PREFIX;

    private final VerificationCodeRepository verificationCodeRepository;
    private final ISmsService smsService;
    private final IEmailService emailService;
    private final IMessageTemplateService templateService;
    private final AuthMetrics authMetrics;

    @Override
    @Transactional
    public Mono<VerificationCode> generateAndSendCode(String phoneNumber, String email,
                                                      VerificationCodeType type) {
        return generateAndSendCode(phoneNumber, email, type, null, null, null);
    }

    @Override
    @Transactional
    public Mono<VerificationCode> generateAndSendCode(String phoneNumber, String email,
                                                      VerificationCodeType type,
                                                      String firstName, String lastName,
                                                      String countryCode) {
        log.info(ServiceConstants.LOG_INFO_GENERATING_CODE, phoneNumber, type);

        return verificationCodeRepository.findByPhoneNumberAndType(phoneNumber, type)
                // Un code encore valide interdit d'en redemander un : anti-spam.
                .flatMap(existing -> !existing.isExpired() && !Boolean.TRUE.equals(existing.getIsUsed())
                        ? Mono.<VerificationCode>error(rejectPendingCode(phoneNumber))
                        : Mono.empty())
                .then(verificationCodeRepository.deleteByPhoneNumberAndType(phoneNumber, type))
                .then(Mono.defer(() -> {
                    String code = generateCode();

                    VerificationCode verificationCode = VerificationCode.builder()
                            .phoneNumber(phoneNumber)
                            .email(email)
                            .code(code)
                            .type(type)
                            .firstName(firstName)
                            .lastName(lastName)
                            .countryCode(countryCode)
                            .expiresAt(LocalDateTime.now()
                                    .plusMinutes(AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES))
                            .build();

                    return verificationCodeRepository.save(verificationCode)
                            .flatMap(saved -> sendCode(phoneNumber, email, code, type)
                                    .thenReturn(saved))
                            .doOnSuccess(saved -> {
                                authMetrics.recordOtpSent(
                                        (email != null && !email.isEmpty()) ? "email" : "sms");
                                // Le CODE n'est plus journalise. Il l'etait avec
                                // le numero de destination, a INFO et sans
                                // condition : chaque code a usage unique jamais
                                // emis etait lisible dans Loki, avec son
                                // destinataire.
                                log.info(ServiceConstants.LOG_INFO_CODE_GENERATED, phoneNumber);
                            });
                }));
    }

    private AuthException rejectPendingCode(String phoneNumber) {
        log.warn(ServiceConstants.LOG_WARN_CODE_RESEND_TOO_SOON, phoneNumber);
        return new AuthException(ErrorCode.VERIFICATION_CODE_PENDING);
    }

    @Override
    @Transactional
    public Mono<Void> validateCode(String phoneNumber, String code, VerificationCodeType type) {
        log.info(ServiceConstants.LOG_INFO_VERIFYING_CODE, phoneNumber);

        return verificationCodeRepository.findByPhoneNumberAndCodeAndType(phoneNumber, code, type)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_INVALID)))
                .flatMap(this::validateAndMarkUsed)
                .doOnSuccess(unused -> log.info("Code validation successful for phone: {}", phoneNumber))
                .doOnError(AuthException.class,
                        e -> log.warn("Code validation failed for phone {}: {}", phoneNumber, e.getMessage()));
    }

    @Override
    @Transactional
    public Mono<Void> validateCodeByEmail(String email, String code, VerificationCodeType type) {
        log.info(ServiceConstants.LOG_INFO_VERIFYING_CODE, email);

        return verificationCodeRepository.findByEmailAndCodeAndType(email, code, type)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_INVALID)))
                .flatMap(this::validateAndMarkUsed)
                .doOnSuccess(unused -> log.info("Code validation successful for email: {}", email))
                .doOnError(AuthException.class,
                        e -> log.warn("Code validation failed for email {}: {}", email, e.getMessage()));
    }

    /**
     * Contrôle l'état du code puis le marque comme consommé.
     * L'incrément du compteur de tentatives est conservé même en cas de succès :
     * il sert de garde-fou en cas de rejeu.
     */
    private Mono<Void> validateAndMarkUsed(VerificationCode verificationCode) {
        String identifier = verificationCode.getEmail() != null
                ? verificationCode.getEmail()
                : verificationCode.getPhoneNumber();

        if (Boolean.TRUE.equals(verificationCode.getIsUsed())) {
            log.warn("Attempt to use already used verification code for: {}", identifier);
            return Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_USED));
        }
        if (verificationCode.isExpired()) {
            log.warn("Attempt to use expired verification code for: {}", identifier);
            return Mono.error(new AuthException(ErrorCode.VERIFICATION_CODE_EXPIRED));
        }
        if (verificationCode.getAttempts() >= AppConstants.MAX_VERIFICATION_ATTEMPTS) {
            log.warn("Too many validation attempts for verification code: {}", identifier);
            return Mono.error(new AuthException(ErrorCode.TOO_MANY_ATTEMPTS));
        }

        verificationCode.setAttempts(verificationCode.getAttempts() + 1);
        verificationCode.setIsUsed(true);
        verificationCode.setUsedAt(LocalDateTime.now());

        return verificationCodeRepository.save(verificationCode)
                .doOnSuccess(saved -> log.info(
                        "Verification code successfully validated and marked as used for: {}", identifier))
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> incrementAttempts(String phoneNumber, VerificationCodeType type) {
        return verificationCodeRepository
                .findTopByPhoneNumberAndTypeOrderByCreatedAtDesc(phoneNumber, type)
                .flatMap(code -> {
                    code.setAttempts(code.getAttempts() + 1);
                    return verificationCodeRepository.save(code);
                })
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> cleanupExpiredCodes() {
        log.info(ServiceConstants.LOG_INFO_RESENDING_CODE, "system");
        return verificationCodeRepository.deleteByExpiresAtBefore(LocalDateTime.now())
                .doOnSuccess(deleted -> log.info("Cleaned up {} expired verification codes", deleted))
                .then();
    }

    @Override
    public Mono<VerificationCode> getLatestCode(String phoneNumber, VerificationCodeType type) {
        return verificationCodeRepository
                .findTopByPhoneNumberAndTypeOrderByCreatedAtDesc(phoneNumber, type);
    }

    private String generateCode() {
        return String.valueOf(RANDOM.nextInt(900000) + 100000);
    }

    private TemplateType getSmsTemplateType(VerificationCodeType type) {
        return switch (type) {
            case PASSWORD_RESET -> TemplateType.SMS_PASSWORD_RESET;
            case REGISTRATION, PHONE_VERIFICATION, EMAIL_VERIFICATION, TWO_FACTOR_AUTH,
                 PHONE_CONFIRMATION, EMAIL_CONFIRMATION, PHONE_CHANGE ->
                    TemplateType.SMS_VERIFICATION;
        };
    }

    /**
     * Achemine le code selon le canal disponible.
     *
     * <p>Un numéro préfixé {@code temp_} signale une inscription par email seul :
     * il n'y a alors pas de SMS à envoyer.
     */
    private Mono<Void> sendCode(String phoneNumber, String email, String code, VerificationCodeType type) {
        boolean emailOnly = phoneNumber != null && phoneNumber.startsWith(TEMP_PHONE_PREFIX);

        if (emailOnly) {
            if (email == null || email.isEmpty()) {
                return Mono.error(new AuthException(ErrorCode.MISSING_REQUIRED_FIELD, "email"));
            }
            return emailService.sendVerificationEmail(email, code, type);
        }

        Map<String, String> variables = Map.of(
                ServiceConstants.TEMPLATE_VAR_CODE, code,
                ServiceConstants.TEMPLATE_VAR_MINUTES,
                String.valueOf(AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));

        // Pas de journal de succes ici.
        //
        // `doOnSuccess` sur un Mono<Void> se declenche AUSSI quand la source se
        // termine vide, et sendSms se termine vide dans deux cas ou rien n'est
        // parti : sms.enabled = false, et le repli du disjoncteur. Ce site
        // ecrivait donc « SMS envoyé avec succès » juste apres « Disjoncteur SMS
        // ouvert » — un journal qui affirme le contraire de ce qui s'est passe,
        // et precisement pendant un incident.
        //
        // Le succes est desormais journalise par le FOURNISSEUR, seul a savoir
        // qu'un envoi a ete accepte.
        Mono<Void> sms = templateService.formatTemplate(getSmsTemplateType(type), variables)
                .flatMap(message -> smsService.sendSms(phoneNumber, message))
                .onErrorMap(error -> !(error instanceof AuthException),
                        error -> {
                            log.error(ServiceConstants.LOG_ERROR_SMS_SEND_FAILED, phoneNumber, error);
                            return new AuthException(ErrorCode.SMS_SERVICE_ERROR, error);
                        });

        if (email == null || email.isEmpty()) {
            return sms;
        }

        // L'email est un doublon de courtoisie : son échec ne doit pas faire
        // échouer l'envoi alors que le SMS est parti.
        return sms.then(emailService.sendVerificationEmail(email, code, type)
                .doOnSuccess(unused -> log.info(ServiceConstants.LOG_INFO_EMAIL_SENT_SUCCESS, email))
                .onErrorResume(error -> {
                    log.warn(ServiceConstants.LOG_WARN_EMAIL_SEND_ERROR, error);
                    return Mono.empty();
                }));
    }

    // ==========================================
    // Replis Resilience4j
    // ==========================================

    @SuppressWarnings("unused")


    /**
     * Une erreur métier (code déjà en attente, champ manquant) ne doit pas être
     * masquée en « service indisponible » : elle est propagée telle quelle.
     */
    private Mono<VerificationCode> serviceUnavailable(String phoneNumber, Throwable e) {
        if (e instanceof AuthException authException) {
            return Mono.error(authException);
        }
        log.error("Fallback: Failed to generate verification code for {}: {}", phoneNumber, e.getMessage());
        return Mono.error(new AuthException(ErrorCode.SERVICE_UNAVAILABLE,
                "Service temporairement indisponible. Veuillez réessayer."));
    }
}
