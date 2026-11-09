package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.mail.internet.MimeMessage;

/**
 * Implémentation du service d'envoi d'emails
 * Configuration depuis les variables d'environnement
 */
@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    /**
     * Les TROIS expediteurs sont desormais nommes explicitement.
     *
     * <h2>Le defaut que les qualificateurs ferment</h2>
     *
     * Aucune des trois injections n'en portait, et {@code adminMailSender}
     * etait {@code @Primary}. Mesure faite dans
     * {@code QuelExpediteurDeCourrielTest} : {@code @Primary} l'emporte sur la
     * correspondance par nom de parametre, donc le champ {@code mailSender}
     * recevait l'expediteur d'ADMINISTRATION.
     *
     * <p>Tous les courriels ordinaires — codes de verification, reinitialisation
     * de mot de passe — partaient par ce compte, avec un {@code From} qui ne lui
     * correspond pas. Un serveur SMTP refuse ou reecrit un expediteur qui n'est
     * pas le compte authentifie.
     *
     * <p>Deux des trois cablages etaient pourtant corrects — l'injection par
     * CHAMP se resout par le nom du champ — ce qui rendait le defaut d'autant
     * plus difficile a voir.
     *
     * <p>Les qualificateurs sont explicites partout, y compris la ou le nom
     * suffisait : une resolution qui depend du nom d'un champ est une
     * resolution qu'un renommage casse en silence.
     */
    private final JavaMailSender mailSender;

    @Autowired(required = false)
    @Qualifier("adminMailSender")
    private JavaMailSender adminMailSender;

    @Autowired(required = false)
    @Qualifier("criticalMailSender")
    private JavaMailSender criticalMailSender;

    @Value("${admin.mail.from}")
    private String adminFrom;

    @Value("${admin.critical.mail.from}")
    private String criticalFrom;

    public EmailServiceImpl(@Qualifier("mailSender") JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * Adresse d'expedition, sans valeur de repli plausible.
     *
     * <p>Le defaut etait {@code noreply@abdatech.com}, sur un domaine que ce
     * projet ne porte plus. Garder une adresse de repli d'apparence valide est
     * le pire des cas : l'envoi part, le serveur distant refuse ou classe en
     * indesirable, et rien ne designe la cause.
     *
     * <p>Vide, l'echec survient a l'ENVOI avec un message explicite, et non au
     * demarrage — la politique du depot est de ne jamais refuser de demarrer
     * pour une variable absente.
     *
     * <p>auth-service emploie deja {@code ${EMAIL_FROM}} sans defaut : ce
     * changement rapproche les deux services au lieu de les laisser divergents.
     */
    @Value("${app.email.from:}")
    private String fromEmail;





    @Override
    public Mono<Void> sendEmailOtp(String email, String otpCode, String firstName) {
        log.info("Envoi de code OTP par email à: {}", email);

        return Mono.fromRunnable(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom(fromEmail);
                helper.setTo(email);
                helper.setSubject("Code de vérification - Abdabase");

                // Lire le template HTML
                ClassPathResource templateResource = new ClassPathResource("templates/otp-email.html");
                String htmlContent = new String(templateResource.getInputStream().readAllBytes());

                // Remplacer les placeholders
                htmlContent = htmlContent.replace("{{firstName}}", firstName != null ? firstName : "");
                htmlContent = htmlContent.replace("{{otpCode}}", otpCode);

                helper.setText(htmlContent, true);

                // Attacher le logo en tant que ressource inline
                ClassPathResource logoResource = new ClassPathResource("assets/logo.png");
                helper.addInline("logo", logoResource);

                mailSender.send(message);
                log.info("Code OTP envoyé par email avec succès à: {}", email);

            } catch (Exception e) {
                log.error("Erreur lors de l'envoi du code OTP par email à: {} - {}", email, e.getMessage());
                throw new RuntimeException("Erreur lors de l'envoi de l'email", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

    @Override
    public Mono<Void> sendPasswordResetOtp(String email, String otpCode, String firstName) {
        log.info("Envoi de code OTP de réinitialisation de mot de passe par email à: {}", email);

        return Mono.fromRunnable(() -> {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

                helper.setFrom(fromEmail);
                helper.setTo(email);
                helper.setSubject("Code de réinitialisation de mot de passe - Abdabase");

                // Lire le template HTML
                ClassPathResource templateResource = new ClassPathResource("templates/reset-password-otp-email.html");
                String htmlContent = new String(templateResource.getInputStream().readAllBytes());

                // Remplacer les placeholders
                htmlContent = htmlContent.replace("{{firstName}}", firstName != null ? firstName : "");
                htmlContent = htmlContent.replace("{{otpCode}}", otpCode);

                helper.setText(htmlContent, true);

                // Attacher le logo en tant que ressource inline
                ClassPathResource logoResource = new ClassPathResource("assets/logo.png");
                helper.addInline("logo", logoResource);

                mailSender.send(message);
                log.info("Code OTP de réinitialisation envoyé par email avec succès à: {}", email);

            } catch (Exception e) {
                log.error("Erreur lors de l'envoi du code OTP de réinitialisation par email à: {} - {}", email, e.getMessage());
                throw new RuntimeException("Erreur lors de l'envoi de l'email", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }



    @Override
    public Mono<Void> sendAdminAlertEmail(String adminEmail, String subject, String message) {
        if (adminMailSender == null) {
            log.warn("AdminMailSender non configuré, impossible d'envoyer l'alerte admin");
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            MimeMessage mimeMessage = adminMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(adminFrom);
            helper.setTo(adminEmail);
            helper.setSubject(subject);
            helper.setText(message, true); // true = HTML

            adminMailSender.send(mimeMessage);
            log.info("Alerte admin envoyée à: {}", adminEmail);
            return (Void) null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(error -> log.error("Erreur envoi alerte admin: {}", error.getMessage()))
        .onErrorResume(error -> Mono.empty());
    }

    @Override
    public Mono<Void> sendCriticalAlertEmail(String criticalEmail, String subject, String message) {
        if (criticalMailSender == null) {
            log.warn("CriticalMailSender non configuré, impossible d'envoyer l'alerte critique");
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            SimpleMailMessage mailMessage = new SimpleMailMessage();
            mailMessage.setFrom(criticalFrom);
            mailMessage.setTo(criticalEmail);
            mailMessage.setSubject(subject);
            mailMessage.setText(message);

            criticalMailSender.send(mailMessage);
            log.info("Alerte critique envoyée à: {}", criticalEmail);
            return (Void) null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(error -> log.error("Erreur envoi alerte critique: {}", error.getMessage()))
        .onErrorResume(error -> Mono.empty());
    }
}
