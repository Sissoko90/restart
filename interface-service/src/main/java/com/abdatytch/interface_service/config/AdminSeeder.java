package com.abdatytch.interface_service.config;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.abdatytch.commons.totp.TotpService;

/**

 * Seeder pour créer l'utilisateur ADMIN par défaut dans interface-service
 * Inclut la génération d'un secret TOTP pour l'authentification à deux facteurs
 */
@Component
// Activé par défaut ; désactivable (tests, environnements sans base)
// via app.seeders.enabled=false
@ConditionalOnProperty(name = "app.seeders.enabled", havingValue = "true", matchIfMissing = true)
@Order(2)
public class AdminSeeder implements CommandLineRunner {

    /* * Les .block() de cette classe sont DÉLIBÉRÉS et sans danger : ce code ne
     * s'exécute qu'au démarrage (CommandLineRunner) ou sur le pool de tâches
     * planifiées, jamais sur un EventLoop Netty. Bloquer y est le comportement
     * attendu — le démarrage doit être séquentiel.
     *
     * Ne pas reprendre ce motif dans du code servant des requêtes.
     */


    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;

    public AdminSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, TotpService totpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
    }

    /* * AUCUNE valeur par défaut, pour aucun de ces champs.
     *
     * Les quatre premiers en portaient une : un numéro de téléphone, une
     * adresse électronique et un nom réels, écrits dans le code. Outre qu'il
     * s'agit de données personnelles dans un dépôt, cela créait, sur tout
     * environnement mal configuré, un compte administrateur dont l'identifiant
     * était connu de quiconque lit le code — il ne restait qu'à trouver le mot
     * de passe.
     *
     * Sans ces variables, le service refuse désormais de démarrer. C'est le
     * comportement voulu : mieux vaut un échec franc au démarrage qu'un compte
     * administrateur créé à l'insu de tout le monde. Pour un environnement sans
     * base ni seeder, poser `app.seeders.enabled=false`.
     */
    @Value("${admin.default.phone}")
    private String adminPhone;

    @Value("${admin.default.email}")
    private String adminEmail;

    @Value("${admin.default.password}")
    private String adminPassword;

    @Value("${admin.default.firstName}")
    private String adminFirstName;

    @Value("${admin.default.lastName}")
    private String adminLastName;

    @Override
    public void run(String... args) {
        seedAdminUser();
    }

    private void seedAdminUser() {
        log.info("Vérification de l'utilisateur ADMIN par défaut...");

        // Vérifier si l'admin existe déjà
        if (userRepository.findByPhoneNumber(adminPhone).block() != null) {
            log.info("Utilisateur ADMIN existe déjà avec le téléphone: {}", adminPhone);
            return;
        }

        if (userRepository.findByEmail(adminEmail).block() != null) {
            log.info("Utilisateur ADMIN existe déjà avec l'email: {}", adminEmail);
            return;
        }

        try {
            log.info("Création de l'utilisateur ADMIN par défaut...");

            // Générer un secret TOTP unique pour l'admin
            String totpSecret = totpService.generateTotpSecret();

            User admin = new User();
            admin.generateId();
            admin.setUsername(adminEmail);
            admin.setEmail(adminEmail);
            admin.setPhoneNumber(adminPhone);
            admin.setFirstName(adminFirstName);
            admin.setLastName(adminLastName);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setEmailVerified(true); // Admin pré-vérifié
            admin.setPhoneVerified(true);  // Admin pré-vérifié
            admin.setMarketingConsent(false); // Admin ne reçoit pas de marketing
            admin.setTotpSecret(totpSecret); // Secret TOTP pour 2FA
            admin.setTotpEnabled(true); // TOTP activé par défaut pour admin
            admin.setRole("ADMIN"); // Rôle administrateur
            // Recovery secret sera généré automatiquement par RecoverySecretScheduler (après 1 minute)

            // Sauvegarder l'admin
            User savedAdmin = userRepository.save(admin).block();

            if (savedAdmin != null) {
                log.info("Utilisateur ADMIN créé dans interface-service avec ID: {}", savedAdmin.getId());
                log.info("ADMIN par défaut créé avec succès!");
                log.info("Téléphone: {}", admin.getPhoneNumber());
                log.info("Email: {}", admin.getEmail());
                // Le mot de passe n'est PAS journalisé, même tronqué : les
                // journaux partent vers Loki, sont conservés et relus. Quatre
                // caractères d'un mot de passe administrateur réduisent
                // sensiblement l'effort d'une attaque par force brute.
                log.info("Mot de passe: celui de ADMIN_DEFAULT_PASSWORD");
                // Le secret TOTP n'est PAS journalisé, même tronqué : c'est la
                // graine du second facteur du compte administrateur, et les
                // journaux partent vers Loki où ils sont conservés et relus.
                // Huit caractères en révèlent 40 bits sur 160.
                // // L'enrôlement se fait par GET /admin/totp/qr-code, après
                // authentification par mot de passe.
                log.info("Secret TOTP généré — enrôlement via GET /admin/totp/qr-code");
                log.warn("IMPORTANT: Changez le mot de passe par défaut en production!");
                log.warn("IMPORTANT: Configurez l'authentification TOTP avec le secret ci-dessus!");
            }

        } catch (Exception e) {
            log.error("Erreur lors de la création de l'utilisateur ADMIN: {}", e.getMessage(), e);
        }
    }

}
