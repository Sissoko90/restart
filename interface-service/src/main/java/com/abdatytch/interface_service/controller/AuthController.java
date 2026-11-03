package com.abdatytch.interface_service.controller;

import com.abdatytch.commons.security.SecretMasking;
import com.abdatytch.interface_service.dto.request.LoginRequest;
import com.abdatytch.interface_service.dto.request.RegisterRequest;
import com.abdatytch.interface_service.dto.request.ResetPasswordWithOtpRequest;
import com.abdatytch.interface_service.dto.request.VerifyEmailRequest;
import com.abdatytch.interface_service.dto.request.VerifyPhoneRequest;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.service.UserService;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Contrôleur REST pour l'authentification
 * Interface-service : Inscription, connexion, réinitialisation
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "Authentication", description = "API d'authentification")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    
    @Value("${jwt.secret}")
    private String jwtSecret;
    
    @Value("${jwt.expiration}")
    private long jwtExpiration;
    
    @Value("${jwt.refresh-expiration}")
    private long jwtRefreshExpiration;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Inscription d'un développeur", description = "Crée un nouveau compte développeur")
    public Mono<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        log.info("Demande d'inscription pour: {}", request.getEmail());
        
        return userService.register(request)
                .doOnSuccess(response -> log.info("Inscription réussie pour: {}", response.getEmail()));
    }

    @PostMapping("/login")
    @Operation(summary = "Connexion développeur", description = "Authentifie un développeur avec email ou téléphone et mot de passe")
    public Mono<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        log.info("Demande de connexion pour: {}", request.getIdentifier());

        return userService.login(request)
                .doOnSuccess(response -> {
                    UserResponse user = (UserResponse) response.get("user");
                    log.info("Connexion réussie pour: {}", user.getEmail());
                });
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Vérifier l'email", description = "Vérifie l'adresse email avec le code OTP reçu")
    public Mono<Map<String, String>> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        log.info("Vérification de l'email avec OTP pour: {}", request.getEmail());

        return userService.verifyEmail(request)
                .then(Mono.just(Map.of(
                    "message", "Email vérifié avec succès",
                    "email", request.getEmail()
                )))
                .doOnSuccess(unused -> log.info("Email vérifié avec succès pour: {}", request.getEmail()));
    }

    @PostMapping("/verify-phone")
    @Operation(summary = "Vérifier le téléphone", description = "Vérifie le numéro de téléphone avec le code OTP reçu")
    public Mono<Map<String, String>> verifyPhone(@Valid @RequestBody VerifyPhoneRequest request) {
        log.info("Vérification de téléphone avec OTP pour: {}", request.getPhoneNumber());

        return userService.verifyPhone(request)
                .then(Mono.just(Map.of(
                    "message", "Téléphone vérifié avec succès",
                    "phoneNumber", request.getPhoneNumber()
                )))
                .doOnSuccess(unused -> log.info("Téléphone vérifié avec succès pour: {}", request.getPhoneNumber()));
    }

    @PostMapping("/request-email-otp")
    @Operation(summary = "Demander code OTP email", description = "Envoie un nouveau code OTP par email")
    public Mono<Map<String, String>> requestEmailOtp(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        log.info("Demande de code OTP pour email: {}", email);

        return userService.requestEmailOtp(email)
                .then(Mono.just(Map.of(
                    "message", "Code OTP envoyé avec succès",
                    "email", email,
                    "expiresIn", "10 minutes"
                )))
                .doOnSuccess(unused -> log.info("Code OTP envoyé par email à: {}", email));
    }

    @PostMapping("/request-phone-otp")
    @Operation(summary = "Demander code OTP téléphone", description = "Envoie un nouveau code OTP par SMS")
    public Mono<Map<String, String>> requestPhoneOtp(@RequestBody Map<String, String> request) {
        String phoneNumber = request.get("phoneNumber");
        log.info("Demande de code OTP pour téléphone: {}", phoneNumber);

        return userService.requestPhoneOtp(phoneNumber)
                .then(Mono.just(Map.of(
                    "message", "Code OTP envoyé avec succès",
                    "phoneNumber", phoneNumber,
                    "expiresIn", "10 minutes"
                )))
                .doOnSuccess(unused -> log.info("Code OTP envoyé par SMS à: {}", phoneNumber));
    }

    @PostMapping("/request-password-reset")
    @Operation(summary = "Demander réinitialisation mot de passe", description = "Envoie un code OTP par email pour réinitialiser le mot de passe")
    public Mono<Map<String, String>> requestPasswordReset(@RequestBody Map<String, String> request) {
        String email = request.get("email");
        log.info("Demande de réinitialisation de mot de passe pour: {}", email);

        return userService.requestPasswordReset(email)
                .then(Mono.just(Map.of(
                    "message", "Code OTP de réinitialisation envoyé avec succès",
                    "email", email,
                    "expiresIn", "10 minutes"
                )))
                .doOnSuccess(unused -> log.info("Code OTP de réinitialisation envoyé à: {}", email));
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Réinitialiser le mot de passe", description = "Réinitialise le mot de passe avec le token")
    public Mono<Void> resetPassword(@RequestBody Map<String, String> request) {
        String token = request.get("token");
        String newPassword = request.get("newPassword");

        // Empreinte, jamais le jeton : celui-ci permet de CHANGER le mot de
        // passe de son porteur. Le filet d'appender de ce service l'attrapait,
        // mais la decision appartient au point d'appel.
        log.info("Réinitialisation de mot de passe, jeton {}", SecretMasking.masquer(token));

        return userService.resetPassword(token, newPassword)
                .doOnSuccess(unused -> log.info("Mot de passe réinitialisé avec succès"));
    }

    @PostMapping("/reset-password-otp")
    @Operation(summary = "Réinitialiser le mot de passe avec OTP", description = "Réinitialise le mot de passe avec le code OTP reçu par email")
    public Mono<Map<String, String>> resetPasswordWithOtp(@Valid @RequestBody ResetPasswordWithOtpRequest request) {
        log.info("Réinitialisation de mot de passe avec OTP pour: {}", request.getEmail());

        return userService.resetPasswordWithOtp(request)
                .then(Mono.just(Map.of(
                    "message", "Mot de passe réinitialisé avec succès",
                    "email", request.getEmail()
                )))
                .doOnSuccess(unused -> log.info("Mot de passe réinitialisé avec succès pour: {}", request.getEmail()));
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Rafraîchir le token", description = "Génère un nouveau token d'accès")
    public Mono<Map<String, String>> refreshToken(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        log.info("Demande de rafraîchissement de token");
        
        // Valider le refresh token et générer nouveaux tokens
        return userService.findByRefreshToken(refreshToken)
                .switchIfEmpty(Mono.error(new RuntimeException("Refresh token invalide")))
                .flatMap(user -> {
                    // Générer nouveaux tokens JWT
                    String newAccessToken = generateAccessToken(user);
                    String newRefreshToken = generateRefreshToken(user);
                    
                    // Mettre à jour le refresh token en base
                    return userService.updateRefreshToken(user.getId(), newRefreshToken)
                            .thenReturn(Map.of(
                                "accessToken", newAccessToken,
                                "refreshToken", newRefreshToken,
                                "expiresIn", "3600",
                                "tokenType", "Bearer"
                            ));
                })
                .doOnSuccess(tokens -> log.info("Tokens rafraîchis avec succès"))
                .doOnError(error -> log.error("Erreur lors du rafraîchissement: {}", error.getMessage()));
    }

    /**
     * Génère un token d'accès JWT
     */
    private String generateAccessToken(UserResponse user) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
            
            return JWT.create()
                    .withIssuer("interface-service")
                    .withSubject(user.getId().toString())
                    .withClaim("email", user.getEmail())
                    .withClaim("firstName", user.getFirstName())
                    .withClaim("lastName", user.getLastName())
                    .withClaim("roles", List.of("USER"))
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(System.currentTimeMillis() + jwtExpiration * 1000))
                    .sign(algorithm);
        } catch (Exception e) {
            log.error("Erreur lors de la génération du token JWT: {}", e.getMessage());
            throw new RuntimeException("Erreur génération token", e);
        }
    }

    /**
     * Génère un refresh token
     */
    private String generateRefreshToken(UserResponse user) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
            
            return JWT.create()
                    .withIssuer("interface-service")
                    .withSubject(user.getId().toString())
                    .withClaim("type", "refresh")
                    .withIssuedAt(new Date())
                    .withExpiresAt(new Date(System.currentTimeMillis() + jwtRefreshExpiration * 1000))
                    .sign(algorithm);
        } catch (Exception e) {
            log.error("Erreur lors de la génération du refresh token: {}", e.getMessage());
            throw new RuntimeException("Erreur génération refresh token", e);
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Déconnexion", description = "Invalide les tokens de l'utilisateur")
    public Mono<Void> logout(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        log.info("Demande de déconnexion");
        
        // Invalider le refresh token en base/cache
        return userService.invalidateRefreshToken(refreshToken)
                .doOnSuccess(unused -> log.info("Déconnexion réussie - Token invalidé"))
                .doOnError(error -> log.error("Erreur lors de la déconnexion: {}", error.getMessage()))
                .onErrorResume(error -> Mono.empty()); // Continue même si l'invalidation échoue
    }
}
