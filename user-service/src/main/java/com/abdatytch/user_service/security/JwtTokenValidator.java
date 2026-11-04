package com.abdatytch.user_service.security;

import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.user_service.constants.AppConstants;
import com.abdatytch.user_service.constants.SecurityConstants;
import com.abdatytch.user_service.exception.InvalidTokenException;
import com.abdatytch.user_service.exception.ValidationIndisponibleException;
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

    /**
     * L'analyseur JWT, construit UNE fois.
     *
     * <p>{@code Jwts.parser()...build()} etait appele a chaque validation. Or
     * {@code build()} de jjwt fait une recherche
     * {@link java.util.ServiceLoader}, qui <b>lit des JAR sur le disque</b> :
     * un appel bloquant sur une EventLoop, sur le chemin de chaque requete
     * authentifiee.
     *
     * <p>Cacher l'analyseur est sur : {@code keyLocator()} resout la cle au
     * moment de l'analyse, par le {@code kid} de l'en-tete JWS, donc la rotation
     * de cles continue de fonctionner. Voir
     * {@code media-service/JwtAuthenticationWebFilter} pour la trace qui l'a
     * revele.
     *
     * <p>Ce validateur n'exige pas d'audience, contrairement aux filtres des
     * autres services. C'est le contrat existant, non modifie ici.
     */
    private volatile io.jsonwebtoken.JwtParser parser;

    /**
     * Construit l'analyseur apres l'injection des {@code @Value}.
     *
     * <p>Et non dans le constructeur, ou l'emetteur attendu serait encore nul.
     */
    @jakarta.annotation.PostConstruct
    void construireLAnalyseur() {
        parser = Jwts.parser()
                .requireIssuer(expectedIssuer)
                .keyLocator(entete -> jwksKeyProvider.keyLocator().locate(entete))
                .build();
    }

    public JwtTokenValidator(JwksKeyProvider jwksKeyProvider, TokenBlacklistService tokenBlacklistService) {
        this.jwksKeyProvider = jwksKeyProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        log.info(" JwtTokenValidator initialized with JWKS support");
    }

    public Mono<Claims> validateTokenReactive(String token) {
        try {
            log.debug(SecurityConstants.LOG_DEBUG_VALIDATING_TOKEN);

            // Aucune cle publique : la plateforme est incapable de JUGER, et le
            // jeton n'est pas en cause.
            //
            // C'etait une InvalidTokenException, donc un 401 — le client
            // renouvelait un jeton qui n'avait rien, le renouvellement echouait
            // pour la meme raison (JWKS injoignable), et tous les utilisateurs
            // se trouvaient deconnectes pendant la panne. Le type dit maintenant
            // la difference, et la reponse est une erreur serveur.
            if (jwksKeyProvider.getDefaultPublicKey() == null) {
                return Mono.error(new ValidationIndisponibleException(
                        "Aucune cle publique disponible : validation JWT impossible"));
            }

            // Clé résolue par `kid` de l'en-tête JWS (obligatoire pendant une rotation :
            // plusieurs clés publiques sont valides simultanément)
            // L'analyseur construit au demarrage : le reconstruire ici ferait
            // une lecture disque sur une EventLoop. Voir le champ.
            Claims claims = parser
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
