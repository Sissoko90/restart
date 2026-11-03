package com.abdatytch.user_service.security;

import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.user_service.constants.AppConstants;
import com.abdatytch.user_service.constants.SecurityConstants;
import com.abdatytch.user_service.exception.InvalidTokenException;
import com.abdatytch.user_service.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**

 * Validateur de tokens JWT
 * Utilise JwksKeyProvider pour récupérer les clés publiques depuis auth-service
 * Supporte la rotation des clés sans redéploiement
 */
@Component
@Slf4j
public class JwtTokenValidator {

    private final JwksKeyProvider jwksKeyProvider;
    private final TokenBlacklistService tokenBlacklistService;

    /**

     * Émetteur attendu : interface-service est l'autorité de signature de la plateforme.
     * Aligné sur `jwt.issuer` de config-repo/dev/application-dev.yml.
     */
    @Value("${jwt.issuer:interface-service}")
    private String expectedIssuer;

    public JwtTokenValidator(JwksKeyProvider jwksKeyProvider, TokenBlacklistService tokenBlacklistService) {
        this.jwksKeyProvider = jwksKeyProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        log.info(" JwtTokenValidator initialized with JWKS support");
    }

    public Mono<Claims> validateTokenReactive(String token) {
        try {
            log.debug(SecurityConstants.LOG_DEBUG_VALIDATING_TOKEN);

            // Vérifier qu'au moins une clé publique est disponible
            if (jwksKeyProvider.getDefaultPublicKey() == null) {
                return Mono.error(new InvalidTokenException("No public key available for JWT validation"));
            }

            // Clé résolue par `kid` de l'en-tête JWS (obligatoire pendant une rotation :
            // plusieurs clés publiques sont valides simultanément)
            Claims claims = Jwts.parser()
                    .requireIssuer(expectedIssuer)
                    .keyLocator(jwksKeyProvider.keyLocator())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String tokenId = claims.getId();
            String userId = claims.getSubject();

            // Vérifications réactives
            Mono<Boolean> tokenCheck = tokenId != null
                ? tokenBlacklistService.isTokenRevoked(tokenId)
                : Mono.just(false);

            Mono<Boolean> userCheck = userId != null
                ? tokenBlacklistService.hasRevokedTokens(userId)
                : Mono.just(false);

            return Mono.zip(tokenCheck, userCheck)
                .flatMap(tuple -> {
                    if (Boolean.TRUE.equals(tuple.getT1())) {
                        log.warn("Token révoqué détecté: {}", tokenId);
                        return Mono.error(new InvalidTokenException("Token révoqué"));
                    }
                    if (Boolean.TRUE.equals(tuple.getT2())) {
                        log.warn("Utilisateur avec tokens révoqués: {}", userId);
                        return Mono.error(new InvalidTokenException("Tous les tokens de l'utilisateur ont été révoqués"));
                    }
                    log.debug(SecurityConstants.LOG_DEBUG_TOKEN_VALIDATED, claims.getSubject());
                    return Mono.just(claims);
                });
        } catch (Exception e) {
            log.error(SecurityConstants.LOG_ERROR_TOKEN_VALIDATION_FAILED, e.getMessage());
            return Mono.error(new InvalidTokenException(String.format(
                AppConstants.EX_TOKEN_VALIDATION_FAILED,
                e.getMessage()), e));
        }
    }

    /* * Les variantes synchrones validateToken(String) et isServiceToken(String) ont
     * été retirées : elles appelaient .block() sur la validation réactive, ce qui
     * bloquait un thread de l'EventLoop à chaque requête authentifiée. Et
     * isServiceToken re-validait le token une seconde fois.
     */

    /**

     * Détermine si des claims DÉJÀ VALIDÉS correspondent à un token de service.
     * Méthode pure : aucune I/O, donc aucun blocage possible.
     */
    public boolean isServiceToken(Claims claims) {
        String roles = (String) claims.get(SecurityConstants.CLAIM_ROLES);
        boolean isService = SecurityConstants.ROLE_SERVICE.equals(roles)
                || SecurityConstants.ROLE_SERVICE_SHORT.equals(claims.getSubject());
        log.debug(SecurityConstants.LOG_DEBUG_IS_SERVICE_TOKEN, isService);
        return isService;
    }
}
