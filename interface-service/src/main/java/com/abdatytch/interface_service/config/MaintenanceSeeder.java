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

 * Seeder pour créer l'utilisateur MAINTENANCE par défaut dans interface-service
 * Utilisé pour l'accès maintenance en cas de crise de sécurité
 */
@Component
// Activé par défaut ; désactivable (tests, environnements sans base)
// via app.seeders.enabled=false
@ConditionalOnProperty(name = "app.seeders.enabled", havingValue = "true", matchIfMissing = true)
@Order(3)
public class MaintenanceSeeder implements CommandLineRunner {

    /* * Les .block() de cette classe sont DÉLIBÉRÉS et sans danger : ce code ne
     * s'exécute qu'au démarrage (CommandLineRunner) ou sur le pool de tâches
     * planifiées, jamais sur un EventLoop Netty. Bloquer y est le comportement
     * attendu — le démarrage doit être séquentiel.
     *
     * Ne pas reprendre ce motif dans du code servant des requêtes.
     */


    private static final Logger log = LoggerFactory.getLogger(MaintenanceSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;

    public MaintenanceSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder, TotpService totpService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
    }

    @Value("${maintenance.phone}")
    private String maintenancePhone;

    // Aucun défaut : mot de passe du compte de maintenance.
    @Value("${maintenance.password}")
    private String maintenancePassword;

    /**
     * Adresse du compte de maintenance, qui était <b>écrite en dur</b>.
     *
     * <p>C'était {@code maintenance@abdatech.com}, sur un domaine que ce projet
     * ne porte plus. Une adresse en dur dans le code est une adresse qu'on ne
     * corrige pas sans recompiler — et celle-ci est enregistrée sur un compte
     * réel, donc c'est elle qui recevrait les courriels de ce compte.
     *
     * <p>Défaut vide, et le compte n'est pas créé sans elle : même politique que
     * le téléphone et le mot de passe ci-dessus. Un compte créé sur une valeur
     * de repli porterait une identité lisible dans le dépôt.
     */
    @Value("${maintenance.email:}")
    private String maintenanceEmail;

    @Override
    public void run(String... args) {
        seedMaintenanceUser();
    }

    private void seedMaintenanceUser() {
        // Sans configuration, aucun compte. Même politique que
        // SuperAdminSeeder : un compte créé sur des valeurs de repli aurait des
        // identifiants lisibles dans le dépôt, ce qui est pire que pas de
        // compte du tout.
        //
        // Ce contrôle protège aussi le démarrage : la propriété a maintenant un
        // défaut vide dans config-repo, donc une plateforme qui ne configure
        // pas ce compte se lève sans lui au lieu d'échouer.
        if (maintenancePhone == null || maintenancePhone.isBlank()
                || maintenancePassword == null || maintenancePassword.isBlank()
                || maintenanceEmail == null || maintenanceEmail.isBlank()) {
            log.info("Compte de maintenance non créé : MAINTENANCE_PHONE, "
                    + "MAINTENANCE_PASSWORD et MAINTENANCE_EMAIL ne sont pas tous "
                    + "renseignés. Aucun compte de repli n'est créé.");
            return;
        }

        log.info(" Vérification de l'utilisateur MAINTENANCE par défaut...");

        // Vérifier si l'utilisateur maintenance existe déjà
        if (userRepository.findByPhoneNumber(maintenancePhone).block() != null) {
            log.info(" Utilisateur MAINTENANCE existe déjà avec le téléphone: {}", maintenancePhone);
            return;
        }

        try {
            log.info(" Création de l'utilisateur MAINTENANCE par défaut...");

            // Générer un secret TOTP unique pour la maintenance
            String totpSecret = totpService.generateTotpSecret();

            User maintenance = new User();
            maintenance.generateId();
            maintenance.setUsername("maintenance@" + maintenancePhone);
            maintenance.setEmail(maintenanceEmail);
            maintenance.setPhoneNumber(maintenancePhone);
            maintenance.setFirstName("Maintenance");
            maintenance.setLastName("User");
            maintenance.setPasswordHash(passwordEncoder.encode(maintenancePassword));
            maintenance.setEmailVerified(true);
            maintenance.setPhoneVerified(true);
            maintenance.setMarketingConsent(false);
            maintenance.setTotpSecret(totpSecret);
            maintenance.setTotpEnabled(true);
            maintenance.setRole("MAINTENANCE");

            // Sauvegarder l'utilisateur maintenance
            User savedMaintenance = userRepository.save(maintenance).block();

            if (savedMaintenance != null) {
                log.info(" Utilisateur MAINTENANCE créé dans interface-service avec ID: {}", savedMaintenance.getId());
                log.info(" MAINTENANCE par défaut créé avec succès!");
                log.info(" Téléphone: {}", maintenance.getPhoneNumber());
                // Le mot de passe n'est PAS journalisé, pas même tronqué. Les
                // quatre premiers caractères retirent déjà l'essentiel de
                // l'entropie d'un mot de passe choisi par un humain, et ces
                // journaux partent vers Loki, où ils sont conservés. Il est
                // dans MAINTENANCE_PASSWORD, qui est sa source.
                //
                // Au passage, substring(0, 4) lançait une exception sur un mot
                // de passe de moins de quatre caractères, ce qui faisait
                // échouer la création du compte dans le catch en aval.
                log.info(" Mot de passe : celui de MAINTENANCE_PASSWORD");
                // Le secret TOTP n'est PAS journalisé : c'est la graine du second
                // facteur, et les journaux partent vers Loki où ils sont conservés.
                log.info(" Secret TOTP généré pour le compte de maintenance");
                // L'adresse du COMPTE, et non un litteral.
                //
                // C'etait une seconde adresse ecrite en dur, distincte de celle
                // posee sur le compte quelques lignes plus haut. L'etiquette du
                // QR code annoncait donc une adresse que le compte ne porte
                // pas : l'exploitant scanne un code libelle pour quelqu'un
                // d'autre, et son authentificateur affiche un compte qui
                // n'existe pas sous ce nom.
                String maskedQrUrl = totpService.generateQrCodeUrl(
                        maintenanceEmail, totpSecret, "Restart Maintenance");
                maskedQrUrl = maskedQrUrl.replaceAll("secret=[A-Z2-7]+", "secret=****");
                log.info(" QR Code URL: {}", maskedQrUrl);
                log.warn("IMPORTANT: Configurez l'authentification TOTP avec le secret ci-dessus!");
                log.warn("IMPORTANT: Conservez ce secret en lieu sûr!");
            }

        } catch (Exception e) {
            log.error(" Erreur lors de la création de l'utilisateur MAINTENANCE: {}", e.getMessage(), e);
        }
    }
}
