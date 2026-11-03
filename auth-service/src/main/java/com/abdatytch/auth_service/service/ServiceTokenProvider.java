package com.abdatytch.auth_service.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;

/**

 * Service dédié à la génération de tokens de service pour la communication inter-services
 * Utilise un secret partagé simple au lieu de RSA pour éviter les dépendances circulaires
 */
@Service
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    // Aucun défaut : secret d'appel de /internal/jwt/sign.
    @Value("${security.internal-secret}")
    private String internalSecret;

    /**

     * Génère un token de service simple pour l'authentification inter-services
     * Utilise HMAC256 avec le secret interne pour éviter les dépendances RSA
     */
    public String generateServiceToken() {
        try {
            Instant now = Instant.now();
            Instant expiration = now.plusSeconds(300); // 5 minutes

            String token = Jwts.builder()
                    .setSubject("auth-service")
                    .setIssuer("auth-service")
                    .setAudience("interface-service")
                    .setIssuedAt(Date.from(now))
                    .setExpiration(Date.from(expiration))
                    .claim("type", "service")
                    .claim("service", "auth-service")
                    .signWith(SignatureAlgorithm.HS256, internalSecret.getBytes())
                    .compact();

            log.debug("Service token généré pour auth-service  interface-service");
            return token;

        } catch (Exception e) {
            log.error("Erreur lors de la génération du service token", e);
            throw new RuntimeException("Impossible de générer le service token", e);
        }
    }
}
