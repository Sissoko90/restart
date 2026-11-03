package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
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

    private final JavaMailSender mailSender;

    @Autowired(required = false)
    private JavaMailSender adminMailSender;

    @Autowired(required = false)
    private JavaMailSender criticalMailSender;

    @Value("${admin.mail.from}")
    private String adminFrom;

    @Value("${admin.critical.mail.from}")
    private String criticalFrom;

    public EmailServiceImpl(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Value("${app.email.from:noreply@abdatech.com}")
    private String fromEmail;

    @Value("${app.email.verification-url:http://localhost:3000/verify-email}")
    private String verificationBaseUrl;

    @Value("${app.email.reset-password-url:http://localhost:3000/reset-password}")
    private String resetPasswordBaseUrl;

    @Override
    public Mono<Void> sendVerificationEmail(String email, String token, String firstName) {
        log.info("Envoi d'email de vérification à: {}", email);
        
        return Mono.fromRunnable(() -> {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromEmail);
                message.setTo(email);
                message.setSubject("Vérifiez votre adresse email - AbdaTech");
                
                String verificationUrl = verificationBaseUrl + "?token=" + token;
                String emailBody = buildVerificationEmailBody(firstName, verificationUrl);
                message.setText(emailBody);
                
                mailSender.send(message);
                log.info("Email de vérification envoyé avec succès à: {}", email);
                
            } catch (Exception e) {
                log.error("Erreur lors de l'envoi de l'email de vérification à: {} - {}", email, e.getMessage());
                throw new RuntimeException("Erreur lors de l'envoi de l'email", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

    @Override
    public Mono<Void> sendPasswordResetEmail(String email, String token, String firstName) {
        log.info("Envoi d'email de réinitialisation à: {}", email);

        return Mono.fromRunnable(() -> {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromEmail);
                message.setTo(email);
                message.setSubject("Réinitialisation de votre mot de passe - AbdaTech");

                String resetUrl = resetPasswordBaseUrl + "?token=" + token;
                String emailBody = buildPasswordResetEmailBody(firstName, resetUrl);
                message.setText(emailBody);

                mailSender.send(message);
                log.info("Email de réinitialisation envoyé avec succès à: {}", email);

            } catch (Exception e) {
                log.error("Erreur lors de l'envoi de l'email de réinitialisation à: {} - {}", email, e.getMessage());
                throw new RuntimeException("Erreur lors de l'envoi de l'email", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

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

    /**
     * Construit le corps de l'email de vérification
     */
    private String buildVerificationEmailBody(String firstName, String verificationUrl) {
        return String.format("""
            Bonjour %s,
            
            Bienvenue sur AbdaTech ! 
            
            Pour activer votre compte développeur, veuillez cliquer sur le lien ci-dessous :
            
            %s
            
            Ce lien est valide pendant 24 heures.
            
            Si vous n'avez pas créé de compte, vous pouvez ignorer cet email.
            
            Cordialement,
            L'équipe AbdaTech
            """
, firstName, verificationUrl);
    }

    /**
     * Construit le corps de l'email de réinitialisation
     */
    private String buildPasswordResetEmailBody(String firstName, String resetUrl) {
        return String.format("""
            Bonjour %s,

            Vous avez demandé la réinitialisation de votre mot de passe.

            Pour créer un nouveau mot de passe, cliquez sur le lien ci-dessous :

            %s

            Ce lien est valide pendant 1 heure.

            Si vous n'avez pas demandé cette réinitialisation, vous pouvez ignorer cet email.
            Votre mot de passe actuel reste inchangé.
            """
, firstName, resetUrl);
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
