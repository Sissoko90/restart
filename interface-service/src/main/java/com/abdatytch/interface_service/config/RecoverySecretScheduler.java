package com.abdatytch.interface_service.config;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.EmailService;
import com.abdatytch.interface_service.service.RecoverySecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**

 * Tâche scheduled pour régénérer automatiquement le recovery secret toutes les 24h
 * Le nouveau recovery secret est chiffré avec RSA et envoyé par email à l'admin
 */
@Component
public class RecoverySecretScheduler {

    /* * Les .block() de cette classe sont DÉLIBÉRÉS et sans danger : ce code ne
     * s'exécute qu'au démarrage (CommandLineRunner) ou sur le pool de tâches
     * planifiées, jamais sur un EventLoop Netty. Bloquer y est le comportement
     * attendu — le démarrage doit être séquentiel.
     *
     * Ne pas reprendre ce motif dans du code servant des requêtes.
     */


    private static final Logger log = LoggerFactory.getLogger(RecoverySecretScheduler.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RecoverySecretService recoverySecretService;
    private final EmailService emailService;

    @Value("${admin.default.email}")
    private String adminEmail;

    public RecoverySecretScheduler(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   RecoverySecretService recoverySecretService,
                                   EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.recoverySecretService = recoverySecretService;
        this.emailService = emailService;
    }

    /**

     * Régénère le recovery secret toutes les 24h
     * Exécution initiale : 1 minute après le démarrage, puis toutes les 24h
     */
    @Scheduled(initialDelay = 60000, fixedRate = 24 * 60 * 60 * 1000) // 24h
    public void regenerateRecoverySecret() {
        try {
            log.info(" Début de la régénération du recovery secret...");

            // Récupérer l'utilisateur admin
            User admin = userRepository.findByEmail(adminEmail).block();

            if (admin == null) {
                log.warn(" Admin non trouvé, impossible de régénérer le recovery secret");
                return;
            }

            // Générer un nouveau recovery secret
            String newRecoverySecret = recoverySecretService.generateRecoverySecret();

            // Chiffrer le recovery secret avec la clé RSA publique
            String encryptedSecret = recoverySecretService.encryptRecoverySecret(newRecoverySecret);

            // Mettre à jour le recovery secret hash dans la base de données
            admin.setRecoverySecretHash(passwordEncoder.encode(newRecoverySecret));
            userRepository.save(admin).block();

            log.info(" Recovery secret régénéré pour l'admin: {}", adminEmail);

            // Envoyer l'email avec le recovery secret chiffré
            sendRecoverySecretEmail(admin, encryptedSecret);

        } catch (Exception e) {
            log.error(" Erreur lors de la régénération du recovery secret: {}", e.getMessage(), e);
        }
    }

    /**

     * Envoie l'email avec le recovery secret chiffré
     */
    private void sendRecoverySecretEmail(User admin, String encryptedSecret) {
        try {
            String subject = " Nouveau Recovery Secret - AbdaTech Admin";

            String body = "<div style='font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;'>"
                    + "<div style='background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 30px; text-align: center;'>"
                    + "<h1 style='color: white; margin: 0;'> AbdaTech</h1>"
                    + "<p style='color: rgba(255,255,255,0.9); margin: 10px 0 0 0;'>Système Sécurisé</p>"
                    + "</div>"
                    + "<div style='padding: 30px; background: #f9f9f9;'>"
                    + "<h2 style='color: #333; margin-top: 0;'>Nouveau Recovery Secret</h2>"
                    + "<p style='color: #666; line-height: 1.6;'>"
                    + "Bonjour " + admin.getFirstName() + ","
                    + "</p>"
                    + "<p style='color: #666; line-height: 1.6;'>"
                    + "Votre recovery secret a été régénéré automatiquement pour des raisons de sécurité."
                    + "</p>"
                    + "<div style='background: white; border-left: 4px solid #667eea; padding: 15px; margin: 20px 0;'>"
                    + "<p style='color: #333; margin: 0; font-weight: bold;'>Recovery Secret Chiffré (RSA) :</p>"
                    + "<code style='display: block; background: #f4f4f4; padding: 10px; margin: 10px 0; word-break: break-all; color: #333;'>"+ encryptedSecret
                + "</code>"
                    + "</div>"
                    + "<p style='color: #666; line-height: 1.6;'>"
                    + "Ce recovery secret est chiffré avec votre clé RSA publique. "
                    + "Utilisez votre clé privée pour le déchiffrer."
                    + "</p>"
                    + "<p style='color: #666; line-height: 1.6;'>"
                    + " <strong>Important :</strong> Gardez ce recovery secret en sécurité. "
                    + "Il vous permettra de récupérer l'accès à votre compte admin si vous perdez votre mot de passe."
                    + "</p>"
                    + "<p style='color: #666; line-height: 1.6;'>"
                    + "Ce recovery secret expirera dans 24 heures et sera régénéré automatiquement."
                    + "</p>"
                    + "</div>"
                    + "<div style='background: #333; color: white; padding: 20px; text-align: center;'>"
                    + "<p style='margin: 0; font-size: 12px; color: rgba(255,255,255,0.7);'>"
                    + "Cet email a été envoyé automatiquement par le système AbdaTech."
                    + "</p>"
                    + "</div>"
                    + "</div>";

            emailService.sendAdminAlertEmail(admin.getEmail(), subject, body).block();

            log.info(" Email avec le recovery secret chiffré envoyé à: {}", admin.getEmail());

        } catch (Exception e) {
            log.error(" Erreur lors de l'envoi de l'email avec le recovery secret: {}", e.getMessage(), e);
        }
    }
}
