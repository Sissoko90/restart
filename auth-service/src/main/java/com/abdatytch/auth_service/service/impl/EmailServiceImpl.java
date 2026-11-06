package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.enums.TemplateType;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.service.IEmailService;
import com.abdatytch.auth_service.service.IMessageTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Envoi d'emails.
 *
 * <p>{@link JavaMailSender} n'a pas d'API réactive : l'envoi SMTP est donc exécuté
 * sur {@code Schedulers.boundedElastic()}, le pool prévu pour l'I/O bloquante.
 * L'EventLoop Netty n'est jamais retenu.
 */
@Service
@RefreshScope
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements IEmailService {

    private final IMessageTemplateService templateService;
    private final JavaMailSender mailSender;

    /**
     * Affiche les codes de vérification en clair dans les journaux.
     *
     * <p>Faux par défaut, et à n'activer qu'en développement local.
     */
    @Value("${auth.dev.log-verification-codes:false}")
    private boolean journaliserCodes;

    @Value("${email.enabled:false}")
    private boolean emailEnabled;

    @Value("${email.from}")
    private String fromEmail;

    @Override
    public Mono<Void> sendVerificationEmail(String toEmail, String code, VerificationCodeType type) {
        log.info(ServiceConstants.LOG_INFO_SENDING_VERIFICATION_EMAIL, toEmail, type);

        if (!emailEnabled) {
            log.warn(ServiceConstants.LOG_WARN_EMAIL_DISABLED, toEmail);
            journaliserCodeEnDeveloppement(code);
            return Mono.empty();
        }

        TemplateType templateType = getTemplateTypeForVerification(type);
        Map<String, String> variables = Map.of(
                ServiceConstants.TEMPLATE_VAR_CODE, code,
                ServiceConstants.TEMPLATE_VAR_MINUTES,
                String.valueOf(AppConstants.VERIFICATION_CODE_EXPIRY_MINUTES));

        return Mono.zip(
                        templateService.getTemplate(templateType).map(t -> t.getSubject()),
                        templateService.formatTemplate(templateType, variables))
                .flatMap(tuple -> sendEmail(toEmail, tuple.getT1(), tuple.getT2()))
                // L'échec d'envoi d'un code de vérification est bloquant pour
                // l'utilisateur : on le remonte explicitement.
                .onErrorMap(error -> !(error instanceof AuthException),
                        error -> {
                            log.error(ServiceConstants.LOG_ERROR_EMAIL_SERVICE, error);
                            return new AuthException(ErrorCode.EMAIL_SERVICE_ERROR, error);
                        });
    }

    @SuppressWarnings("unused")

    /**
     * Affiche le code en clair, <b>uniquement</b> si on l'a explicitement demandé.
     *
     * <p>L'affichage était conditionné à {@code !emailEnabled}, un drapeau
     * d'<b>exploitation</b> : en production, un serveur de messagerie coupé y
     * suffisait pour que tous les codes partent dans les journaux. Le besoin
     * réel — lire le code en développement local, faute de le recevoir — mérite
     * un interrupteur qui le dit et qui est faux par défaut.
     */
    private void journaliserCodeEnDeveloppement(String code) {
        if (journaliserCodes) {
            log.info(ServiceConstants.LOG_INFO_CODE_EN_DEVELOPPEMENT, code);
        }
    }

    @Override
    public Mono<Void> sendWelcomeEmail(String toEmail, String firstName) {
        log.info(ServiceConstants.LOG_INFO_SENDING_WELCOME_EMAIL, toEmail);

        if (!emailEnabled) {
            log.warn(ServiceConstants.LOG_WARN_EMAIL_DISABLED, toEmail);
            return Mono.empty();
        }

        Map<String, String> variables = Map.of(
                ServiceConstants.TEMPLATE_VAR_FIRST_NAME,
                firstName != null ? firstName : ServiceConstants.TEMPLATE_VAR_EMPTY);

        return Mono.zip(
                        templateService.getTemplate(TemplateType.EMAIL_WELCOME).map(t -> t.getSubject()),
                        templateService.formatTemplate(TemplateType.EMAIL_WELCOME, variables))
                .flatMap(tuple -> sendEmail(toEmail, tuple.getT1(), tuple.getT2()))
                // L'email de bienvenue n'est pas critique : son échec ne doit pas
                // faire échouer l'inscription.
                .onErrorResume(error -> {
                    log.warn(ServiceConstants.LOG_WARN_EMAIL_SEND_ERROR, error);
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Void> envoyerAvisDeSecurite(String toEmail, String sujet, String corps) {
        if (!emailEnabled) {
            // WARN et non INFO : en developpement l'envoi est coupe, et une
            // alerte de securite qui ne part pas doit se voir dans les
            // journaux. L'appelant le compte aussi en metrique.
            log.warn(ServiceConstants.LOG_WARN_EMAIL_DISABLED, toEmail);
            return Mono.empty();
        }
        // L'erreur n'est PAS avalee ici : c'est l'appelant qui decide de ne pas
        // faire echouer l'operation, et qui compte l'echec. Avaler aux deux
        // etages rendrait le compteur aveugle.
        return sendEmail(toEmail, sujet, corps);
    }

    /**
     * Envoi SMTP, isolé sur le pool d'I/O bloquante.
     */
    private Mono<Void> sendEmail(String toEmail, String subject, String body) {
        return Mono.fromRunnable(() -> {
                    log.info(ServiceConstants.LOG_INFO_SENDING_EMAIL, fromEmail, toEmail, subject);

                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(fromEmail);
                    message.setTo(toEmail);
                    message.setSubject(subject);
                    message.setText(body);

                    mailSender.send(message);
                    log.info(ServiceConstants.LOG_INFO_EMAIL_SENT_SUCCESS, toEmail);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(error -> {
                    log.error(ServiceConstants.LOG_ERROR_EMAIL_SEND_FAILED, toEmail, error);
                    return new IllegalStateException(ServiceConstants.EX_EMAIL_SEND_FAILED, error);
                })
                .then();
    }

    private TemplateType getTemplateTypeForVerification(VerificationCodeType type) {
        return switch (type) {
            case REGISTRATION, PHONE_VERIFICATION, EMAIL_VERIFICATION,
                 PHONE_CONFIRMATION, EMAIL_CONFIRMATION,
                 // Le code d'un changement de numero part par SMS, au nouveau
                 // numero. Cette branche ne sert donc que si le compte n'a pas
                 // de numero joignable — un cas que le service refuse en
                 // amont. Elle est la pour que le switch reste exhaustif sans
                 // `default`, qui absorberait en silence le prochain type.
                 PHONE_CHANGE -> TemplateType.EMAIL_VERIFICATION;
            case PASSWORD_RESET -> TemplateType.EMAIL_PASSWORD_RESET;
            case TWO_FACTOR_AUTH -> TemplateType.EMAIL_VERIFICATION;
        };
    }
}
