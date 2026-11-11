package com.abdatytch.interface_service.controller;

import com.abdatytch.commons.security.SecretComparisonUtil;

import com.abdatytch.interface_service.dto.request.AdminCredentialsChangeRequest;
import com.abdatytch.interface_service.dto.request.AdminTotpRegenerateRequest;
import com.abdatytch.interface_service.dto.request.MaintenanceAuthRequest;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.commons.totp.TotpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**

 * Contrôleur REST pour la maintenance admin (dernière couche de sécurité)
 * Authentification séparée avec token JWT de maintenance + TOTP
 */
@RestController
@RequestMapping("/maintenance")
@Tag(name = "Maintenance", description = "API de maintenance admin (dernière couche de sécurité)")
public class MaintenanceController {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final IDevJwtService jwtService;

    /**

     * Aucune valeur par défaut : une configuration manquante doit faire échouer le
     * démarrage, pas laisser tourner le service avec une adresse admin codée en dur.
     */
    @Value("${admin.default.email}")
    private String adminEmail;

    /**

     * Aucune valeur par défaut : la clé de maintenance est un secret.
     * L'ancienne valeur par défaut était inscrite dans le code source et doit être
     * considérée comme compromise (voir docs/JWT-CONTRACT.md et le .env historique).
     */
    @Value("${MAINTENANCE_SECRET_KEY}")
    private String maintenanceSecretKey;

    /**
     * Le secret admin, lu comme <b>propriété Spring</b> et non dans
     * l'environnement du processus.
     *
     * <h3>Le défaut corrigé, et il fermait cet endpoint</h3>
     *
     * Ce contrôleur lisait {@code System.getenv("ADMIN_SECRET_KEY")}. Or
     * {@code DotenvConfig} dépose les valeurs de {@code .env} dans un
     * {@code MapPropertySource} de l'{@code Environment} Spring —
     * {@code System.getenv} est immuable en Java et ne peut pas être alimenté
     * ainsi. Renseigner {@code ADMIN_SECRET_KEY} dans {@code .env}, ce que
     * {@code .env.example} demande, n'avait donc <b>aucun effet ici</b> :
     * {@code getenv} rendait {@code null},
     * {@code SecretComparisonUtil.matches(null, ...)} rendait {@code false}, et
     * {@code /maintenance/auth} répondait 403 à tout le monde. Seul un
     * {@code export} dans le shell de l'exploitant le faisait fonctionner.
     *
     * <h3>Un secret, un nom, un lecteur</h3>
     *
     * {@code AdminSecretFilter} vérifie le <b>même en-tête</b>
     * {@code X-Admin-Secret} sur {@code /admin/**}, et lisait
     * {@code ${admin.secret.key:}}. Deux lecteurs d'un même secret avec deux
     * sources différentes : l'un pouvait accepter ce que l'autre refusait, et
     * les faire concorder demandait de renseigner la valeur deux fois, de deux
     * façons.
     *
     * <p>La propriété est désormais la seule source, et
     * {@code config-repo/dev/interface-service-dev.yml} la relie à
     * {@code ADMIN_SECRET_KEY}.
     *
     * <h3>Le défaut VIDE est délibéré</h3>
     *
     * La politique du projet est de ne pas bloquer le démarrage sur une
     * variable absente. {@code matches} refuse sur une valeur vide : l'endpoint
     * est donc <b>fermé</b> tant que le secret n'est pas configuré, jamais
     * ouvert — et {@code verifierConfiguration} le dit au démarrage, parce que
     * cela ne se réparera pas tout seul.
     */
    @Value("${admin.secret.key:}")
    private String adminSecretKey;

    /**
     * Annonce au démarrage ce que ce contrôleur ne protège pas.
     *
     * <p>Sans cela, un secret non configuré ne se manifeste qu'au premier appel
     * refusé — et comme le refus est un 403 banal, personne ne fait le lien
     * avec la configuration.
     */
    @jakarta.annotation.PostConstruct
    void verifierConfiguration() {
        if (adminSecretKey == null || adminSecretKey.isBlank()) {
            log.error("admin.secret.key n'est pas configure : /maintenance/auth "
                    + "refusera TOUS les appels. Renseigner ADMIN_SECRET_KEY.");
        }
    }

    public MaintenanceController(UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               TotpService totpService,
                               IDevJwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.jwtService = jwtService;
    }

    /**

     * Endpoint de maintenance pour obtenir un token JWT d'accès
     * Authentification requise : X-Admin-Secret + X-Maintenance-Identifier (clé dure) + Body (téléphone + mot de passe + TOTP)
     */
    @PostMapping("/auth")
    @Operation(summary = "Maintenance admin", description = "Obtenir un token JWT d'accès maintenance")
    public Mono<ResponseEntity<Map<String, String>>> maintenanceAccess(
            @RequestHeader("X-Admin-Secret") String adminSecret,
            @RequestHeader("X-Maintenance-Identifier") String maintenanceKey,
            @RequestBody MaintenanceAuthRequest request) {
        // Vérifier le secret admin
        if (!SecretComparisonUtil.matches(adminSecretKey, adminSecret)) {
            log.warn("Tentative d'accès maintenance avec secret admin invalide");
            return refus();
        }

        // Vérifier la clé dure de maintenance
        if (!SecretComparisonUtil.matches(maintenanceSecretKey, maintenanceKey)) {
            log.warn("Tentative d'accès maintenance avec clé dure invalide");
            return refus();
        }

        // Récupérer l'utilisateur maintenance
        return userRepository.findByPhoneNumber(request.getPhoneNumber())
            .flatMap(user -> {
                // Vérifier le mot de passe
                if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    log.warn("Tentative d'accès maintenance avec mot de passe incorrect pour {}", request.getPhoneNumber());
                    return refus();
                }

                // Vérifier le TOTP
                return totpService.validateTotpCode(user.getTotpSecret(), request.getTotpCode())
                    .flatMap(totpValid -> {
                        if (!totpValid) {
                            log.warn("Tentative d'accès maintenance avec TOTP incorrect pour {}", request.getPhoneNumber());
                            return refus();
                        }

                        // Methode DEDIEE, et non un type glisse dans les
                        // customClaims de generateTokenForUser.
                        //
                        // L'ancienne forme fonctionnait parce que les claims
                        // personnalises etaient appliques EN DERNIER et
                        // ecrasaient le type pose par l'autorite. Autrement dit,
                        // ce controleur exploitait la faille qui permettait aussi
                        // de faire passer un jeton de rafraichissement pour un
                        // jeton d'acces. Le type est desormais la decision de
                        // l'autorite.
                        String maintenanceToken = jwtService.generateMaintenanceToken(
                            user.getId().toString(),
                            user.getEmail(),
                            user.getPhoneNumber()
                        );

                        log.info("Accès maintenance accordé pour {}", user.getPhoneNumber());

                        Map<String, String> response = new HashMap<>();
                        response.put("token", maintenanceToken);
                        response.put("expiresIn", "3600");
                        return Mono.just(ResponseEntity.ok(response));
                    });
            })
            // Numero inconnu : le MEME refus, et non un message distinct.
            //
            // Le code precedent levait ici
            // `new RuntimeException("Utilisateur maintenance non trouve")`, que
            // le `onErrorResume` ci-dessous renvoyait au client sous la forme
            // « Erreur d'authentification: Utilisateur maintenance non trouve ».
            // L'appelant apprenait donc quels numeros correspondent a un compte
            // de maintenance — sur l'endpoint le plus privilegie de la
            // plateforme.
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("Tentative d'accès maintenance sur un numéro inconnu");
                return refus();
            }))
            // Une panne reste une panne, et ne se deguise plus en refus.
            //
            // Le `onErrorResume` precedent rendait 401 avec
            // `e.getMessage()` dans le corps : toute erreur de base de donnees
            // devenait un refus d'authentification, message interne compris. Un
            // 401 fait reessayer le client avec d'autres identifiants ; un 500
            // lui dit que le probleme n'est pas chez lui. Et le message, lui,
            // reste dans le journal.
            .onErrorResume(e -> {
                log.error("Erreur lors de l'authentification de maintenance : {}",
                        e.getMessage(), e);
                Map<String, String> corps = Map.of("error", "Erreur interne");
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(corps));
            });
    }

    /**
     * Le refus unique de {@code /maintenance/auth}.
     *
     * <h3>Le défaut corrigé : un oracle à cinq réponses</h3>
     *
     * Les cinq façons d'échouer donnaient cinq réponses distinctes :
     *
     * <pre>
     * secret admin faux        -> 403 « Secret admin invalide »
     * cle de maintenance fausse-> 403 « Clé de maintenance invalide »
     * numero inconnu           -> 401 « ... Utilisateur maintenance non trouvé »
     * mot de passe faux        -> 401 « Mot de passe incorrect »
     * code TOTP faux           -> 401 « Code TOTP incorrect »
     * </pre>
     *
     * <p>Un attaquant apprenait donc, à chaque tentative, <b>lequel des cinq
     * facteurs il avait déjà trouvé</b>. Il pouvait chercher le secret admin
     * seul, puis la clé de maintenance seule, puis énumérer les numéros, puis
     * le mot de passe, puis le TOTP — cinq recherches indépendantes au lieu
     * d'une combinaison. C'est l'endpoint qui délivre le jeton de maintenance,
     * celui qui permet de changer les identifiants admin et de régénérer son
     * TOTP.
     *
     * <p>Les cinq rendent maintenant le même 401 et le même corps. Le motif
     * réel est journalisé : l'exploitant le voit, l'appelant non.
     */
    private Mono<ResponseEntity<Map<String, String>>> refus() {
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Authentification refusée")));
    }

    /**

     * Endpoint pour changer les identifiants admin
     * Autorisation appliquée par MaintenanceJwtFilter (token JWT de type `maintenance`).
     */
    @PostMapping("/change-credentials")
    @Operation(summary = "Changer identifiants admin", description = "Change les identifiants admin (accès maintenance)")
    public Mono<ResponseEntity<Map<String, Object>>> changeAdminCredentials(
            @RequestBody AdminCredentialsChangeRequest request) {
        return userRepository.findByEmail(adminEmail)
            .flatMap(user -> {
                // Vérifier le recovery secret (mot de passe initial pour récupération d'accès)
                if (!passwordEncoder.matches(request.getRecoverySecret(), user.getRecoverySecretHash())) {
                    Map<String, Object> errorMap = new java.util.HashMap<>();
                    errorMap.put("error", "Recovery secret incorrect");
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorMap));
                }

                // Mettre à jour les identifiants
                user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
                if (request.getEmail() != null && !request.getEmail().isEmpty()) {
                    user.setEmail(request.getEmail());
                }
                if (request.getPhoneNumber() != null && !request.getPhoneNumber().isEmpty()) {
                    user.setPhoneNumber(request.getPhoneNumber());
                }

                Map<String, Object> successMap = new HashMap<>();
                successMap.put("message", "Identifiants admin mis à jour avec succès");
                successMap.put("email", user.getEmail());
                successMap.put("phoneNumber", user.getPhoneNumber());
                return userRepository.save(user)
                    .thenReturn(ResponseEntity.ok(successMap));
            })
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Admin non trouvé"))));
    }

    /**

     * Configuration TOTP admin.
     *
     * <p>L'URL de provisionnement contient le <b>secret TOTP en clair</b> : la
     * posséder revient à pouvoir générer tous les codes du compte admin. Le token de
     * maintenance (vérifié par MaintenanceJwtFilter) ne suffit donc pas, on exige en
     * plus le mot de passe admin — même niveau de preuve que /regenerate-totp.
     */
    @PostMapping("/totp-config")
    @Operation(summary = "Obtenir configuration TOTP",
            description = "Obtient la configuration TOTP admin (token de maintenance + mot de passe admin)")
    public Mono<ResponseEntity<Map<String, Object>>> getTotpConfig(
            @RequestBody AdminTotpRegenerateRequest request) {
        return userRepository.findByEmail(adminEmail)
            .flatMap(user -> {
                if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    log.warn("Consultation TOTP refusée : mot de passe admin incorrect");
                    Map<String, Object> errorMap = new HashMap<>();
                    errorMap.put("error", "Mot de passe incorrect");
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorMap));
                }

                Map<String, Object> config = new HashMap<>();
                config.put("email", user.getEmail());
                config.put("totpEnabled", user.getTotpEnabled());

                if (user.getTotpSecret() != null && !user.getTotpSecret().isEmpty()) {
                    String qrCodeUrl = totpService.generateQrCodeUrl(user.getEmail(), user.getTotpSecret(), "Restart Admin");
                    config.put("qrCodeUrl", qrCodeUrl);
                }

                log.info("Configuration TOTP consultée pour {}", user.getEmail());
                return Mono.just(ResponseEntity.ok(config));
            })
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Admin non trouvé"))));
    }

    /**

     * Endpoint pour régénérer le TOTP admin
     * Autorisation appliquée par MaintenanceJwtFilter (token JWT de type `maintenance`).
     */
    @PostMapping("/regenerate-totp")
    @Operation(summary = "Régénérer TOTP admin", description = "Régénère le secret TOTP admin (accès maintenance)")
    public Mono<ResponseEntity<Map<String, Object>>> regenerateAdminTotp(
            @RequestBody AdminTotpRegenerateRequest request) {
        return userRepository.findByEmail(adminEmail)
            .flatMap(user -> {
                // Vérifier le mot de passe
                if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    Map<String, Object> errorMap = new java.util.HashMap<>();
                    errorMap.put("error", "Mot de passe incorrect");
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorMap));
                }

                // Générer un nouveau secret TOTP
                String newTotpSecret = totpService.generateTotpSecret();
                String qrCodeUrl = totpService.generateQrCodeUrl(user.getEmail(), newTotpSecret, "Restart Admin");

                user.setTotpSecret(newTotpSecret);

                log.info("TOTP admin régénéré pour {}", user.getEmail());

                Map<String, Object> successMap = new java.util.HashMap<>();
                successMap.put("message", "TOTP régénéré avec succès");
                successMap.put("qrCodeUrl", qrCodeUrl);
                successMap.put("secret", newTotpSecret);

                return userRepository.save(user)
                    .thenReturn(ResponseEntity.ok(successMap));
            })
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Admin non trouvé"))));
    }

    /**

     * Endpoint pour l'audit de sécurité admin
     * Autorisation appliquée par MaintenanceJwtFilter (token JWT de type `maintenance`).
     */
    @GetMapping("/security-audit")
    @Operation(summary = "Audit de sécurité", description = "Obtient l'audit de sécurité admin (accès maintenance)")
    public Mono<ResponseEntity<Map<String, Object>>> getSecurityAudit() {
        return userRepository.findByEmail(adminEmail)
            .flatMap(user -> {
                Map<String, Object> auditInfo = new HashMap<>();
                auditInfo.put("email", user.getEmail());
                auditInfo.put("phoneNumber", user.getPhoneNumber());
                auditInfo.put("failedLoginAttempts", user.getFailedLoginAttempts());
                auditInfo.put("postConfirmationAttempts", user.getPostConfirmationAttempts());
                auditInfo.put("criticalAlertSent", user.getCriticalAlertSent());
                auditInfo.put("accountLockedUntil", user.getAccountLockedUntil() != null ? user.getAccountLockedUntil().toString() : "null");
                auditInfo.put("adminConfirmationPending", user.isAdminConfirmationPending());

                // DEUX champs, parce qu'il y a deux etats distincts.
                //
                // Ce bloc rendait un seul « totpEnabled », calcule comme
                // « un secret existe ». Or /totp-config rend un « totpEnabled »
                // qui lit le DRAPEAU user.getTotpEnabled(). Deux endpoints du
                // meme controleur publiaient donc le meme nom pour deux choses
                // differentes, et pouvaient se contredire.
                //
                // Les deux etats sont reels et l'ecart est porteur de sens :
                // l'enrolement TOTP de cette plateforme se fait en deux temps,
                // un secret est d'abord genere puis confirme. Un secret present
                // avec un drapeau a faux signifie « enrolement commence, jamais
                // confirme » — exactement ce qu'un audit doit voir, et ce que
                // l'ancien champ unique masquait.
                auditInfo.put("totpEnabled", user.getTotpEnabled());
                auditInfo.put("totpSecretPresent",
                        user.getTotpSecret() != null && !user.getTotpSecret().isEmpty());

                return Mono.just(ResponseEntity.ok(auditInfo));
            })
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Admin non trouvé"))));
    }

    /**

     * Endpoint pour débloquer le compte admin
     * Autorisation appliquée par MaintenanceJwtFilter (token JWT de type `maintenance`).
     */
    @PostMapping("/unlock-account")
    @Operation(summary = "Débloquer compte admin", description="Débloque le compte admin (accès maintenance)")
    public Mono<ResponseEntity<Map<String, String>>> unlockAdminAccount() {
        return userRepository.findByEmail(adminEmail)
            .flatMap(user -> {
                // Réinitialiser tous les compteurs et débloquer
                user.resetFailedLoginAttempts();
                user.resetPostConfirmationAttempts();
                user.setCriticalAlertSent(false);
                user.setCriticalMaintenanceToken(null);
                user.setCriticalMaintenanceExpiresAt(null);
                user.setAccountLockedUntil(null);
                user.setAdminConfirmationPending(false);
                user.setAdminConfirmationToken(null);
                user.setAdminConfirmationExpiresAt(null);

                log.info("Compte admin débloqué pour {}", user.getEmail());

                return userRepository.save(user)
                    .thenReturn(ResponseEntity.ok(Map.of(
                        "message", "Compte admin débloqué avec succès",
                        "email", user.getEmail()
                    )));
            })
            .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Admin non trouvé"))));
    }
}
