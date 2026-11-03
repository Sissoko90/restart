package com.abdatytch.auth_service.service.impl;

import com.abdatytch.commons.security.JwtClaimsReader;

import com.abdatytch.auth_service.client.InterfaceServiceClient;
import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.repository.UserRoleAssignmentRepository;
import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.auth_service.service.ITokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**

 * Service JWT pour auth-service
 *
 * ARCHITECTURE AUTH-AS-A-SERVICE:
 * - La génération de JWT est déléguée à interface-service
 * - La validation utilise les clés publiques via JWKS
 * - auth-service ne possède plus de clés RSA locales
 */
@Service
@Slf4j
public class JwtServiceImpl implements IJwtService {

    @Value("${jwt.expiration:86400000}")
    private long jwtExpirationMs;

    @Value("${jwt.issuer:interface-service}")
    private String jwtIssuer;

    private final ITokenBlacklistService tokenBlacklistService;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final com.abdatytch.auth_service.metrics.AuthMetrics authMetrics;
    private final JwksKeyProvider jwksKeyProvider;
    private final InterfaceServiceClient interfaceServiceClient;

    public JwtServiceImpl(ITokenBlacklistService tokenBlacklistService,
                         UserRoleAssignmentRepository roleAssignmentRepository,
                         com.abdatytch.auth_service.metrics.AuthMetrics authMetrics,
                         JwksKeyProvider jwksKeyProvider,
                         InterfaceServiceClient interfaceServiceClient) {
        this.tokenBlacklistService = tokenBlacklistService;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.authMetrics = authMetrics;
        this.jwksKeyProvider = jwksKeyProvider;
        this.interfaceServiceClient = interfaceServiceClient;
        log.info(" JwtService initialisé - Utilise JWKS depuis interface-service");
    }

    /**

     * Génère un token JWT pour un utilisateur via interface-service
     * La signature est déléguée à interface-service
     */
    @Override
    public Mono<String> generateToken(User user) {
        long startTime = System.nanoTime();

        log.info(ServiceConstants.LOG_INFO_JWT_GENERATED, user.getId());

        // Rôles actifs de l'utilisateur ; repli sur le rôle principal si aucune affectation
        // expand() resout la hierarchie a l'emission : le jeton d'un SUPER_ADMIN
        // porte aussi ROLE_ADMIN. Les services aval n'ont ainsi aucune
        // hierarchie a connaitre, et aucun ne peut l'oublier.
        return roleAssignmentRepository.findActiveRolesByUserId(user.getId())
                .flatMapIterable(assignment -> assignment.getRole().expand())
                .map(UserRole::getAuthority)
                .distinct()
                .collectList()
                .map(roles -> roles.isEmpty()
                        ? user.getRole().expand().stream().map(UserRole::getAuthority).toList()
                        : roles)
                .flatMap(roles -> {
                    Map<String, Object> customClaims = new HashMap<>();
                    customClaims.put(AppConstants.JWT_CLAIM_PHONE, user.getPhoneNumber());
                    customClaims.put("primaryRole", user.getRole().getAuthority());

                    return interfaceServiceClient.signUserToken(
                            user.getId(),
                            null, // projectId : null pour les utilisateurs internes
                            user.getEmail(),
                            user.getFirstName() + " " + user.getLastName(),
                            roles,
                            customClaims);
                })
                .map(InterfaceServiceClient.AuthTokenResponse::getAccessToken)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Échec de la génération du token via interface-service")))
                .doOnSuccess(token -> authMetrics.recordTokenGeneration(
                        java.time.Duration.ofNanos(System.nanoTime() - startTime)));
    }

    public String getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    public String getPhoneFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get(AppConstants.JWT_CLAIM_PHONE, String.class);
    }

    /**

     * <p>La lecture passe par {@code JwtClaimsReader} : le claim {@code roles}
     * est une LISTE pour un jeton d'utilisateur final, et
     * {@code claims.get("roles", String.class)} levait donc une exception. Un
     * rôle absent produisait par ailleurs un {@code NullPointerException} sur
     * le {@code replace} qui suivait.
     *
     * @return le rôle porté par le jeton, ou {@code null} s'il n'en porte aucun
     * ou si sa valeur ne correspond à aucun rôle connu.
     */
    public UserRole getRoleFromToken(String token) {
        String role = JwtClaimsReader.primaryRole(parseToken(token));
        if (role == null) {
            log.warn("Jeton sans rôle exploitable");
            return null;
        }
        String normalized = role.replace(ServiceConstants.ROLE_PREFIX, "");
        try {
            return UserRole.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("Rôle inconnu dans le jeton : {}", normalized);
            return null;
        }
    }

    public String getTokenIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }

    /* * Le validateToken synchrone a été retiré : il faisait deux .block() sur la
     * blacklist Redis, ce qui bloquait un thread de l'EventLoop à chaque requête
     * de profil. Utiliser validateTokenReactive.
     */

    /**

     * Validation réactive du token JWT (100% non-bloquant)
     * Utilise les clés publiques JWKS depuis interface-service
     */
    @Override
    public Mono<Boolean> validateTokenReactive(String token) {
        return Mono.fromCallable(() -> {
            if (jwksKeyProvider.getDefaultPublicKey() == null) {
                throw new RuntimeException("Aucune clé publique disponible");
            }
            return jwtParser(true)
                    .parseSignedClaims(token)
                    .getPayload();
        })
        .flatMap(claims -> {
            String tokenId = claims.getId();
            String userId = claims.getSubject();

            Mono<Boolean> tokenRevokedCheck = tokenId != null
                ? tokenBlacklistService.isTokenRevoked(tokenId)
                : Mono.just(false);

            Mono<Boolean> userRevokedCheck = userId != null
                ? tokenBlacklistService.hasRevokedTokens(userId)
                : Mono.just(false);

            return Mono.zip(tokenRevokedCheck, userRevokedCheck)
                .map(tuple -> {
                    if (tuple.getT1()) {
                        log.warn("Token révoqué détecté: {}", tokenId);
                        return false;
                    }
                    if (tuple.getT2()) {
                        log.warn("Utilisateur avec tokens révoqués: {}", userId);
                        return false;
                    }
                    return true;
                })
                // Mono.zip se termine VIDE si l'une de ses sources l'est : un
                // hasKey Redis interrompu en vol suffit. La validation rendrait
                // alors un Mono vide, le flatMap de l'appelant ne s'executerait
                // jamais, et la requete serait perdue — ni authentifiee, ni
                // refusee, juste un client en attente jusqu'a son expiration.
                //
                // onErrorReturn ne couvre pas ce cas : il n'y a pas d'erreur,
                // seulement une absence de valeur.
                //
                // La valeur de repli est FALSE : ne pas savoir si un jeton est
                // revoque, c'est ne pas savoir si son porteur a encore le droit.
                // Meme politique que TokenRevocationChecker au Gateway.
                .defaultIfEmpty(false);
        })
        .onErrorResume(SignatureException.class, e -> {
            log.error(ServiceConstants.LOG_ERROR_SIGNATURE_JWT, e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(MalformedJwtException.class, e -> {
            log.error(ServiceConstants.LOG_ERROR_MALFORMED_JWT, e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(ExpiredJwtException.class, e -> {
            log.error(ServiceConstants.LOG_ERROR_EXPIRED_JWT, e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(UnsupportedJwtException.class, e -> {
            log.error(ServiceConstants.LOG_ERROR_UNSUPPORTED_JWT, e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(e -> {
            log.error("Erreur lors de la validation du token: {}", e.getMessage());
            return Mono.just(false);
        });
    }

    /* * generateServiceToken a été retiré : il appelait interface-service en HTTP
     * bloquant pour obtenir un token de service. Utiliser ServiceTokenProvider,
     * qui le forge localement en HMAC-SHA256 sans aucune I/O.
     */

    /**

     * Parse un token JWT avec les clés JWKS
     */
    private Claims parseToken(String token) {
        if (jwksKeyProvider.getDefaultPublicKey() == null) {
            throw new RuntimeException("Aucune clé publique disponible pour parser le token");
        }
        return jwtParser(false)
                .parseSignedClaims(token)
                .getPayload();
    }

    /**

     * Parser JWT commun : la clé de vérification est résolue via le `kid` porté par
     * l'EN-TÊTE JWS. Indispensable pendant une rotation de clés côté interface-service,
     * où plusieurs clés publiques sont valides simultanément (période de grâce).
     * L'ancien code utilisait getFirstPublicKey(), dont l'ordre n'est pas déterministe.
     */
    private JwtParser jwtParser(boolean requireIssuer) {
        var builder = Jwts.parser().keyLocator(jwksKeyProvider.keyLocator());
        if (requireIssuer) {
            builder.requireIssuer(jwtIssuer);
        }
        return builder.build();
    }

    /**

     * Génère un token JWT avec des claims personnalisés via interface-service
     */
    @Override
    public Mono<String> generateTokenWithClaims(Map<String, Object> claims) {
        String userId = (String) claims.getOrDefault(AppConstants.JWT_CLAIM_USER_ID, "temp");

        return interfaceServiceClient.signUserToken(
                        userId,
                        null,
                        (String) claims.get("email"),
                        (String) claims.get("username"),
                        null,
                        claims)
                .map(InterfaceServiceClient.AuthTokenResponse::getAccessToken)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Échec de la génération du token avec claims")));
    }

    /**

     * Extrait tous les claims d'un token JWT
     */
    public Map<String, Object> extractAllClaims(String token) {
        try {
            Claims claims = parseToken(token);
            Map<String, Object> claimsMap = new HashMap<>();
            claims.forEach((key, value) -> claimsMap.put(key, value));
            return claimsMap;
        } catch (Exception e) {
            log.error("Erreur lors de l'extraction des claims", e);
            return new HashMap<>();
        }
    }

    public long getExpirationMs() {
        return jwtExpirationMs;
    }

    /**

     * Révoque un token JWT en l'ajoutant à la blacklist
     */
    public void revokeToken(String token) {
        try {
            Claims claims = parseToken(token);
            String tokenId = claims.getId();
            Date expiration = claims.getExpiration();
            long expirationMs = expiration.getTime() - System.currentTimeMillis();

            if (expirationMs > 0) {
                tokenBlacklistService.revokeToken(tokenId, expirationMs);
                log.info("Token révoqué avec succès: {}", tokenId);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la révocation du token", e);
            throw new RuntimeException("Échec de la révocation du token", e);
        }
    }

    /**

     * Révoque tous les tokens d'un utilisateur
     */
    @Override
    public void revokeAllUserTokens(String userId) {
        log.info("Révocation de tous les tokens pour l'utilisateur: {}", userId);
        tokenBlacklistService.revokeAllUserTokens(userId);
    }

    /**

     * Génère un token temporaire pour la vérification 2FA via interface-service
     * Expire après 5 minutes
     */
    @Override
    public Mono<String> generateTempToken(User user) {
        log.info("Génération d'un token temporaire 2FA pour l'utilisateur: {}", user.getId());

        Map<String, Object> customClaims = new HashMap<>();
        customClaims.put("phone", user.getPhoneNumber());
        customClaims.put("type", "temp_2fa");
        customClaims.put("exp", System.currentTimeMillis() + 300000); // 5 minutes

        return interfaceServiceClient.signUserToken(
                        user.getId(),
                        null,
                        user.getEmail(),
                        user.getFirstName(),
                        List.of("TEMP_2FA"),
                        customClaims)
                .map(InterfaceServiceClient.AuthTokenResponse::getAccessToken)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Échec de la génération du token temporaire 2FA")));
    }
}
