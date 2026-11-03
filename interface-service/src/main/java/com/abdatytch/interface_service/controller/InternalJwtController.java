package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.commons.security.SecretComparisonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**

 * Controller interne pour la génération de JWT
 * Utilisé par auth-service pour signer les tokens des utilisateurs finaux
 *
 * SÉCURITÉ: Cet endpoint est protégé par un secret interne
 * et ne doit être accessible que depuis les services internes
 */
@RestController
@RequestMapping("/internal/jwt")
public class InternalJwtController {

    private static final Logger log = LoggerFactory.getLogger(InternalJwtController.class);

    /**
     * Les seuls types recevables sur {@code /refresh}.
     *
     * <p>{@code refresh} est émis pour un développeur, {@code refresh_user}
     * pour un utilisateur final. Les types d'<b>accès</b> ({@code developer},
     * {@code user}) en sont exclus : les accepter permettrait de prolonger une
     * session indéfiniment depuis un simple jeton d'accès.
     *
     * <p>C'est la symétrie de {@code JwtClaimsReader.ACCESS_TOKEN_TYPES}, qui
     * refuse l'inverse — un jeton de rafraîchissement présenté comme jeton
     * d'accès.
     */
    private static final Set<String> TYPES_DE_RAFRAICHISSEMENT =
            Set.of("refresh", "refresh_user");

    /**
     * Secret partagé protégeant les trois endpoints.
     *
     * <p>Aucune valeur par défaut : il protège {@code /internal/jwt/sign}, donc
     * la capacité d'émettre un JWT pour n'importe quel utilisateur et n'importe
     * quel rôle. Un repli y serait un secret connu.
     *
     * <h3>Pourquoi par CONSTRUCTEUR et non par {@code @Value} sur le champ</h3>
     *
     * Avec un champ annoté, la classe n'était pas testable :
     * {@code WebTestClient.bindToController} n'utilise pas l'instance fournie
     * telle quelle, si bien qu'une valeur posée par
     * {@code ReflectionTestUtils.setField} n'atteignait pas le gestionnaire —
     * tous les appels répondaient 403 avec un secret pourtant identique des
     * deux côtés. Un endpoint de cette importance qu'on ne peut pas tester est
     * un endpoint qu'on ne teste pas.
     *
     * <p>Même raisonnement que pour {@code GatewayAccessWebFilter} d'
     * auth-service, dont le secret est passé au constructeur depuis qu'il n'est
     * plus un bean.
     */
    private final String internalSecret;

    private final IDevJwtService jwtService;

    public InternalJwtController(IDevJwtService jwtService,
                                 @Value("${security.internal-secret}") String internalSecret) {
        this.jwtService = jwtService;
        this.internalSecret = internalSecret;
    }

    /**

     * Endpoint interne pour signer un JWT
     * Utilisé par auth-service pour déléguer la signature des tokens
     *
     * COMMUNICATION INTER-SERVICES:
     * - X-Service-Token: JWT de service pour authentification (optionnel pour compatibilité)
     * - X-Internal-Secret: Secret partagé pour autorisation interne (obligatoire)
     *
     * @param secretHeader Secret interne pour authentification inter-services
     * @param serviceToken Token de service pour authentification (optionnel)
     * @param request Claims à inclure dans le token
     * @return Token JWT signé
     */
    @PostMapping("/sign")
    public Mono<ResponseEntity<Map<String, Object>>> signToken(
            @RequestHeader("X-Internal-Secret") String secretHeader,
            @RequestHeader(value = "X-Service-Token", required = false) String serviceToken,
            @RequestBody JwtSignRequest request) {

        // Vérifier le secret interne (obligatoire)
        if (!SecretComparisonUtil.matches(internalSecret, secretHeader)) {
            log.warn(" Tentative d'accès non autorisée à /internal/jwt/sign - Secret invalide");
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Unauthorized - Invalid internal secret")));
        }

        // Log du service token si présent (pour audit)
        if (serviceToken != null) {
            log.debug(" Service token reçu pour /internal/jwt/sign");
        }

        log.info(" Génération de JWT pour user: {} (project: {})",
                request.getUserId(), request.getProjectId());

        return Mono.fromCallable(() -> {
            // Générer le token avec les claims fournis
            String token = jwtService.generateTokenForUser(
                    request.getUserId(),
                    request.getProjectId(),
                    request.getEmail(),
                    request.getUsername(),
                    request.getRoles(),
                    request.getCustomClaims()
            );

            // Générer le refresh token
            String refreshToken = jwtService.generateRefreshTokenForUser(
                    request.getUserId(),
                    request.getProjectId()
            );

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accessToken", token);
            response.put("refreshToken", refreshToken);
            response.put("tokenType", "Bearer");
            response.put("expiresIn", jwtService.getAccessTokenExpiration());

            return ResponseEntity.ok(response);
        }).onErrorResume(e -> {
            log.error(" Erreur lors de la génération du token", e);
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Token generation failed")));
        });
    }

    /**

     * Rafraîchit un token JWT
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refreshToken(
            @RequestHeader("X-Internal-Secret") String secretHeader,
            @RequestBody RefreshTokenRequest request) {

        if (!SecretComparisonUtil.matches(internalSecret, secretHeader)) {
            log.warn(" Tentative d'accès non autorisée à /internal/jwt/refresh");
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Unauthorized")));
        }

        return jwtService.validateTokenReactive(request.getRefreshToken())
                // Une validation VIDE ne doit pas perdre la requete. Le
                // Mono.zip de validateTokenReactive peut se terminer vide si
                // l'une de ses sources Redis l'est : le flatMap ci-dessous ne
                // s'executerait alors jamais, et le client attendrait jusqu'a
                // son delai d'expiration. Repli sur FALSE : ne pas savoir si un
                // jeton est revoque, c'est ne pas savoir si son porteur a
                // encore le droit.
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(isValid -> {
                    if (!isValid) {
                        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.<String, Object>of("error", "Invalid refresh token")));
                    }

                    // Le TYPE du jeton est verifie.
                    //
                    // validateTokenReactive ne controle que la signature,
                    // l'expiration et la revocation — pas la nature du jeton. Un
                    // jeton d'ACCES etait donc accepte ici comme jeton de
                    // rafraichissement, ce qui permettait de prolonger une
                    // session indefiniment a partir d'un seul jeton d'acces : le
                    // rafraichissement perdait toute limite de duree.
                    String type = jwtService.extractClaim(request.getRefreshToken(), "type");
                    // Le test de nullite est SEPARE, et non delegue au Set.
                    //
                    // `Set.of(...)` est immuable et leve une
                    // NullPointerException sur `contains(null)`. Un jeton sans
                    // claim `type` — cas tout a fait atteignable, et le premier
                    // qu'essaierait quelqu'un cherchant a contourner ce
                    // controle — produisait donc un 500 au lieu d'un refus
                    // propre. Un defaut introduit par cette correction meme, et
                    // trouve par le test ecrit pour elle.
                    if (type == null || !TYPES_DE_RAFRAICHISSEMENT.contains(type)) {
                        log.warn(" Jeton de type '{}' refusé sur /internal/jwt/refresh", type);
                        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                                .body(Map.<String, Object>of("error", "Invalid refresh token")));
                    }

                    String userId = jwtService.extractUserId(request.getRefreshToken());
                    String projectId = jwtService.extractClaim(request.getRefreshToken(), "projectId");

                    // Les ROLES sont reportes depuis le jeton presente.
                    //
                    // L'appel passait `roles = null`, si bien que le jeton
                    // regenere n'en portait AUCUN : un administrateur perdait
                    // ses droits au premier rafraichissement, sans message ni
                    // trace. Le symptome serait apparu comme un 401 ou un 403
                    // intermittent, attribuable a tout sauf a sa cause.
                    List<String> roles = rolesDuJeton(request.getRefreshToken());

                    String newAccessToken = jwtService.generateTokenForUser(
                            userId, projectId, null, null, roles, null);

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("accessToken", newAccessToken);
                    response.put("tokenType", "Bearer");
                    response.put("expiresIn", jwtService.getAccessTokenExpiration());

                    return Mono.just(ResponseEntity.ok(response));
                });
    }

    /**
     * Rôles portés par un jeton, ou {@code null} s'il n'en porte aucun.
     *
     * <p>{@code null} plutôt qu'une liste vide : {@code generateTokenForUser}
     * n'écrit le claim {@code roles} que s'il est non vide, et un claim
     * {@code roles: []} explicite se lirait comme « aucun rôle accordé » là où
     * son absence se lit « rôles non applicables ».
     */
    private List<String> rolesDuJeton(String token) {
        String brut = jwtService.extractClaim(token, "roles");
        if (brut == null || brut.isBlank()) {
            return null;
        }
        // extractClaim rend un toString() : une liste y apparait sous la forme
        // « [ROLE_A, ROLE_B] ».
        String contenu = brut.startsWith("[") && brut.endsWith("]")
                ? brut.substring(1, brut.length() - 1)
                : brut;
        List<String> roles = java.util.Arrays.stream(contenu.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .toList();
        return roles.isEmpty() ? null : roles;
    }

    /**

     * Vérifie si un token est valide
     */
    @PostMapping("/verify")
    public Mono<ResponseEntity<Map<String, Object>>> verifyToken(
            @RequestHeader("X-Internal-Secret") String secretHeader,
            @RequestBody VerifyTokenRequest request) {

        if (!SecretComparisonUtil.matches(internalSecret, secretHeader)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Unauthorized")));
        }

        return jwtService.validateTokenReactive(request.getToken())
                // Meme raison que sur /refresh : une validation vide perdrait la
                // requete. Un jeton dont on ne sait pas s'il est revoque est
                // declare non valide.
                .defaultIfEmpty(Boolean.FALSE)
                .map(isValid -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("valid", isValid);

                    if (isValid) {
                        response.put("userId", jwtService.extractUserId(request.getToken()));
                        response.put("projectId", jwtService.extractClaim(request.getToken(), "projectId"));
                        // Le TYPE est rendu a l'appelant : sans lui, celui-ci ne
                        // peut pas distinguer un jeton d'acces d'un jeton de
                        // rafraichissement, alors que les deux sont « valides ».
                        response.put("type", jwtService.extractClaim(request.getToken(), "type"));
                    }

                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    // extractUserId et extractClaim analysent le jeton a nouveau
                    // et peuvent lever sur une forme inattendue. Un 500 ici
                    // renseignerait sur la pile d'appels de l'autorite de
                    // signature.
                    log.warn(" Vérification de jeton en échec : {}", e.getMessage());
                    return Mono.just(ResponseEntity.ok(
                            Map.<String, Object>of("valid", false)));
                });
    }

    // Request DTOs
    public static class JwtSignRequest {
        private String userId;
        private String projectId;
        private String email;
        private String username;
        private java.util.List<String> roles;
        private Map<String, Object> customClaims;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public java.util.List<String> getRoles() { return roles; }
        public void setRoles(java.util.List<String> roles) { this.roles = roles; }
        public Map<String, Object> getCustomClaims() { return customClaims; }
        public void setCustomClaims(Map<String, Object> customClaims) { this.customClaims = customClaims; }
    }

    public static class RefreshTokenRequest {
        private String refreshToken;
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }

    public static class VerifyTokenRequest {
        private String token;
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }
}
