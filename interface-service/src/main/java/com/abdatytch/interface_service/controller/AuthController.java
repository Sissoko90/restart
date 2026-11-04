package com.abdatytch.interface_service.controller;

import com.abdatytch.commons.security.SecretMasking;
import com.abdatytch.interface_service.dto.request.LoginRequest;
import com.abdatytch.interface_service.dto.request.RegisterRequest;
import com.abdatytch.interface_service.dto.request.ResetPasswordWithOtpRequest;
import com.abdatytch.interface_service.dto.request.VerifyEmailRequest;
import com.abdatytch.interface_service.dto.request.VerifyPhoneRequest;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.exception.BusinessException;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.interface_service.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    /**
     * L'autorite de signature, pour la REVOCATION a la deconnexion.
     *
     * <p>Les trois {@code @Value} qui vivaient ici — {@code jwt.secret},
     * {@code jwt.expiration}, {@code jwt.refresh-expiration} — ont ete retires
     * avec le second mecanisme de signature HMAC qu'ils alimentaient. Un
     * secret SYMETRIQUE n'a rien a faire dans le service qui detient les cles
     * privees RSA de la plateforme.
     */
    private final IDevJwtService jwtService;

    public AuthController(UserService userService, IDevJwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
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

    /**
     * Rafraîchit la paire de jetons.
     *
     * <h3>Ce qui était cassé, et de TROIS façons indépendantes</h3>
     *
     * <ol>
     *   <li>la connexion <b>ne persistait pas</b> le jeton qu'elle émettait.
     *       {@code updateRefreshToken} n'avait qu'un seul appelant : ce flux
     *       lui-même. Le premier rafraîchissement ne pouvait rien trouver ;</li>
     *   <li>la recherche portait sur la colonne <b>en clair</b>
     *       {@code refresh_token}, jamais écrite — alors que {@code V1} avait
     *       créé {@code refresh_token_hash}, le bon magasin ;</li>
     *   <li>les nouveaux jetons étaient signés en <b>HMAC256</b> avec
     *       {@code ${jwt.secret}}, alors que les huit services valident du
     *       <b>RS256</b> par JWKS. {@code Algorithm.HMAC256} n'existait que
     *       dans cette classe, et aucun validateur de la plateforme n'accepte
     *       HS256 : les jetons rendus n'étaient utilisables nulle part.</li>
     * </ol>
     *
     * <p>Un second mécanisme de signature, avec un secret <b>symétrique</b>,
     * dans le service qui est l'autorité JWT de la plateforme. Il est retiré :
     * la frappe des jetons appartient à {@code UserService}, qui le fait déjà
     * pour la connexion, avec les clés RSA et le {@code kid} publié au JWKS.
     *
     * <p>Le refus est un <b>401</b> et non un 500 : un jeton de
     * rafraîchissement inconnu ou périmé est un défaut d'authentification, pas
     * une panne du serveur. Il rendait une {@code RuntimeException} générique.
     */
    @PostMapping("/refresh-token")
    @Operation(summary = "Rafraîchir le token", description = "Génère une nouvelle paire de jetons RS256")
    public Mono<Map<String, Object>> refreshToken(@RequestBody Map<String, String> request) {
        log.info("Demande de rafraîchissement de token");

        return userService.refreshTokens(request.get("refreshToken"))
                .doOnSuccess(tokens -> log.info("Tokens rafraîchis avec succès"))
                .onErrorResume(BusinessException.class, refus -> {
                    // Le motif est journalise, jamais rendu : distinguer
                    // « inconnu » de « perime » renseignerait un appelant sur
                    // l'existence du jeton.
                    log.warn("Rafraîchissement refusé : {}", refus.getMessage());
                    return Mono.error(new ResponseStatusException(
                            HttpStatus.UNAUTHORIZED, "Jeton de rafraîchissement invalide"));
                });
    }

    /**
     * Déconnexion : invalide le jeton de rafraîchissement ET révoque l'accès.
     *
     * <h3>Ce qui était cassé</h3>
     *
     * Cette méthode rendait <b>204</b> en ne révoquant rien. Elle mettait à
     * {@code null} une colonne que la connexion n'écrivait jamais, et
     * {@code .onErrorResume(error -> Mono.empty())} avalait tout échec — y
     * compris celui de l'invalidation. Un développeur qui se déconnectait
     * gardait un jeton d'accès valable jusqu'à son expiration.
     *
     * <p>Le mécanisme de révocation existait et <b>fonctionnait</b> :
     * {@code DevTokenBlacklistServiceImpl} alimente Redis, et
     * {@code validateTokenReactive} le consulte. Il n'était simplement pas
     * appelé ici.
     *
     * <p>Le jeton d'accès est donc révoqué <b>en plus</b> du jeton de
     * rafraîchissement, quand l'appelant le fournit. Révoquer l'un sans l'autre
     * ne ferme pas la session : le rafraîchissement seul laisse l'accès courant
     * vivant, et l'accès seul laisse le porteur en obtenir un nouveau.
     *
     * <p>Le code reste <b>204</b> même si rien ne correspondait : une
     * déconnexion ne doit pas renseigner l'appelant sur l'existence d'un jeton.
     * Mais les erreurs ne sont plus avalées en silence — elles sont
     * journalisées avec leur cause.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Déconnexion", description = "Invalide le jeton de rafraîchissement et révoque le jeton d'accès")
    public Mono<Void> logout(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        String accessToken = request.get("accessToken");
        log.info("Demande de déconnexion");

        // `revokeToken` est synchrone et analyse le jeton : hors du fil de la
        // requete, comme toute operation qui calcule sur le chemin reactif.
        Mono<Void> revocation = (accessToken == null || accessToken.isBlank())
                ? Mono.empty()
                : Mono.<Void>fromRunnable(() -> jwtService.revokeToken(accessToken))
                        .subscribeOn(Schedulers.boundedElastic());

        return userService.invalidateRefreshToken(refreshToken)
                .then(revocation)
                .doOnSuccess(unused -> log.info("Déconnexion traitée"))
                // Le detail est journalise : l'avaler sans trace etait le
                // defaut. L'appelant recoit 204 dans tous les cas, mais
                // l'exploitant voit ce qui a echoue.
                .onErrorResume(error -> {
                    log.error("Déconnexion incomplète : {}", error.getMessage(), error);
                    return Mono.empty();
                });
    }
}
