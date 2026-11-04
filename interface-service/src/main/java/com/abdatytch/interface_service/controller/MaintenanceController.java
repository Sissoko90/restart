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
        if (!SecretComparisonUtil.matches(System.getenv("ADMIN_SECRET_KEY"), adminSecret)) {
            log.warn(" Tentative d'accès maintenance avec secret admin invalide");
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Secret admin invalide")));
        }

        // Vérifier la clé dure de maintenance
        if (!SecretComparisonUtil.matches(maintenanceSecretKey, maintenanceKey)) {
            log.warn(" Tentative d'accès maintenance avec clé dure invalide");
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Clé de maintenance invalide")));
        }

        // Récupérer l'utilisateur maintenance
        return userRepository.findByPhoneNumber(request.getPhoneNumber())
            .switchIfEmpty(Mono.error(new RuntimeException("Utilisateur maintenance non trouvé")))
            .flatMap(user -> {
                // Vérifier le mot de passe
                if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    log.warn(" Tentative d'accès maintenance avec mot de passe incorrect pour {}", request.getPhoneNumber());
                    return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Mot de passe incorrect")));
                }

                // Vérifier le TOTP
                return totpService.validateTotpCode(user.getTotpSecret(), request.getTotpCode())
                    .flatMap(totpValid -> {
                        if (!totpValid) {
                            log.warn(" Tentative d'accès maintenance avec TOTP incorrect pour {}", request.getPhoneNumber());
                            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.of("error", "Code TOTP incorrect")));
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

                        log.info(" Accès maintenance accordé pour {}", user.getPhoneNumber());

                        Map<String, String> response = new HashMap<>();
                        response.put("token", maintenanceToken);
                        response.put("expiresIn", "3600");
                        return Mono.just(ResponseEntity.ok(response));
                    });
            })
            .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Erreur d'authentification: " + e.getMessage()))));
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
                    log.warn(" Consultation TOTP refusée : mot de passe admin incorrect");
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

                log.info(" Configuration TOTP consultée pour {}", user.getEmail());
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

                log.info(" TOTP admin régénéré pour {}", user.getEmail());

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
                auditInfo.put("totpEnabled", user.getTotpSecret() != null && !user.getTotpSecret().isEmpty());

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

                log.info(" Compte admin débloqué pour {}", user.getEmail());

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
