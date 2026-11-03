package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.interface_service.service.IDevKeyRotationService;
import com.abdatytch.interface_service.service.IDevTokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.Key;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service JWT pour les développeurs
 * Utilise les clés RSA propres à interface-service (isolées de auth-service)
 */
@Service
public class DevJwtServiceImpl implements IDevJwtService {

    private static final Logger log = LoggerFactory.getLogger(DevJwtServiceImpl.class);

    /**
     * Type du jeton de maintenance, attendu par {@code MaintenanceJwtFilter}.
     *
     * <p>Les deux côtés déclarent la chaîne séparément, ce qui est fragile mais
     * reste préférable à un couplage du filtre vers l'autorité. Le test
     * {@code typeDeMaintenanceAccordeAuFiltre} vérifie qu'ils s'accordent.
     */
    private static final String TYPE_MAINTENANCE = "maintenance";

    /** Projet porté par un jeton de maintenance : il n'en vise aucun. */
    private static final String PROJET_MAINTENANCE = "maintenance";

    @Value("${jwt.dev.expiration:3600000}")
    private long jwtExpirationMs;

    @Value("${jwt.dev.refresh-expiration:604800000}")
    private long refreshExpirationMs;

    @Value("${jwt.dev.issuer:interface-service}")
    private String jwtIssuer;

    /**
     * Liste d'audiences séparées par des virgules, émises dans le claim `aud`.
     * Doit couvrir tous les services consommateurs, sinon ils rejettent le token.
     */
    @Value("${jwt.dev.audience:restart-developers}")
    private String jwtAudience;

    private final IDevKeyRotationService keyRotationService;
    private final IDevTokenBlacklistService tokenBlacklistService;

    /**
     * Résout la clé publique de vérification à partir du `kid` porté par l'EN-TÊTE JWS.
     * Indispensable pendant une rotation : plusieurs clés sont valides simultanément
     * (clé courante + anciennes en période de grâce).
     */
    private Locator<Key> keyLocator() {
        return header -> {
            String keyId = (header instanceof JwsHeader jwsHeader) ? jwsHeader.getKeyId() : null;
            if (keyId != null) {
                PublicKey resolved = keyRotationService.getPublicKeyById(keyId);
                if (resolved != null) {
                    return resolved;
                }
                log.warn("Aucune clé publique connue pour kid={} - repli sur la clé courante", keyId);
            }
            return keyRotationService.getCurrentPublicKey();
        };
    }

    /**
     * Parser JWT commun : résolution de clé par `kid` + contrôle de l'émetteur.
     */
    private JwtParser jwtParser(boolean requireIssuer) {
        var builder = Jwts.parser().keyLocator(keyLocator());
        if (requireIssuer) {
            builder.requireIssuer(jwtIssuer);
        }
        return builder.build();
    }

    /**
     * Prépare un JwtBuilder commun : `kid` dans l'EN-TÊTE (et non plus seulement
     * dans le corps), émetteur, et TOUTES les audiences configurées.
     */
    private JwtBuilder signedBuilder(Map<String, Object> claims, String tokenId,
                                     String subject, Date issuedAt, Date expiry) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(keyRotationService.getCurrentKeyId()).and()
                .claims(claims)
                .id(tokenId)
                .subject(subject)
                .issuer(jwtIssuer);

        for (String audience : configuredAudiences()) {
            builder.audience().add(audience);
        }

        return builder
                .issuedAt(issuedAt)
                .expiration(expiry)
                .signWith(keyRotationService.getCurrentPrivateKey(), SignatureAlgorithm.RS256);
    }

    /**
     * Découpe `jwt.dev.audience` en audiences distinctes (ordre préservé, doublons ignorés).
     */
    private Set<String> configuredAudiences() {
        Set<String> audiences = new LinkedHashSet<>();
        if (jwtAudience != null) {
            Arrays.stream(jwtAudience.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(audiences::add);
        }
        if (audiences.isEmpty()) {
            audiences.add("restart-developers");
        }
        return audiences;
    }

    public DevJwtServiceImpl(IDevKeyRotationService keyRotationService,
                             IDevTokenBlacklistService tokenBlacklistService) {
        this.keyRotationService = keyRotationService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    public String generateToken(User developer) {
        log.info("Génération de token JWT pour le développeur: {}", developer.getId());

        String tokenId = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        Map<String, Object> claims = new HashMap<>();
        claims.put("developerId", developer.getId().toString());
        claims.put("email", developer.getEmail());
        claims.put("username", developer.getUsername());
        claims.put("role", developer.getRole() != null ? developer.getRole() : "DEVELOPER");
        claims.put("type", "developer");

        String token = signedBuilder(claims, tokenId, developer.getId().toString(), now, expiryDate)
                .compact();

        log.info("Token JWT généré avec succès pour le développeur: {}, expire: {}", 
                developer.getId(), expiryDate);

        return token;
    }

    @Override
    public String generateRefreshToken(User developer) {
        log.info("Génération de refresh token pour le développeur: {}", developer.getId());

        String tokenId = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpirationMs);

        Map<String, Object> claims = new HashMap<>();
        claims.put("developerId", developer.getId().toString());
        claims.put("type", "refresh");

        return signedBuilder(claims, tokenId, developer.getId().toString(), now, expiryDate)
                .compact();
    }

    /*
     * Le validateToken synchrone a été retiré : il appelait .block() sur la
     * blacklist Redis, ce qui bloquait un thread de l'EventLoop. Utiliser
     * validateTokenReactive.
     */

    @Override
    public Mono<Boolean> validateTokenReactive(String token) {
        return Mono.fromCallable(() -> jwtParser(true)
                    .parseSignedClaims(token)
                    .getPayload())
        .flatMap(claims -> {
            String tokenId = claims.getId();
            String developerId = claims.getSubject();

            Mono<Boolean> tokenRevokedCheck = tokenId != null
                    ? tokenBlacklistService.isTokenRevoked(tokenId)
                    : Mono.just(false);

            Mono<Boolean> developerRevokedCheck = developerId != null
                    ? tokenBlacklistService.hasDeveloperRevokedTokens(developerId)
                    : Mono.just(false);

            return Mono.zip(tokenRevokedCheck, developerRevokedCheck)
                    .map(tuple -> {
                        boolean isTokenRevoked = tuple.getT1();
                        boolean hasDeveloperRevokedTokens = tuple.getT2();

                        if (isTokenRevoked) {
                            log.warn("Token révoqué détecté: {}", tokenId);
                            return false;
                        }

                        if (hasDeveloperRevokedTokens) {
                            log.warn("Développeur avec tokens révoqués: {}", developerId);
                            return false;
                        }

                        return true;
                    })
                    // Mono.zip se termine VIDE si l'une de ses sources l'est : un
                    // hasKey Redis interrompu en vol suffit. La validation
                    // rendrait alors un Mono vide, le flatMap de l'appelant ne
                    // s'executerait jamais, et la requete serait perdue — ni
                    // acceptee, ni refusee, juste un client en attente.
                    //
                    // onErrorResume ne couvre pas ce cas : il n'y a pas
                    // d'erreur, seulement une absence de valeur.
                    //
                    // Repli FALSE : ne pas savoir si un jeton est revoque, c'est
                    // ne pas savoir si son porteur a encore le droit. Meme
                    // politique que TokenRevocationChecker au Gateway et que
                    // JwtServiceImpl d'auth-service.
                    .defaultIfEmpty(false);
        })
        .onErrorResume(SignatureException.class, e -> {
            log.error("Signature JWT invalide: {}", e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(MalformedJwtException.class, e -> {
            log.error("Token JWT malformé: {}", e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(ExpiredJwtException.class, e -> {
            log.error("Token JWT expiré: {}", e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(UnsupportedJwtException.class, e -> {
            log.error("Token JWT non supporté: {}", e.getMessage());
            return Mono.just(false);
        })
        .onErrorResume(e -> {
            log.error("Erreur lors de la validation du token: {}", e.getMessage());
            return Mono.just(false);
        });
    }

    @Override
    public String getDeveloperIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    @Override
    public String getEmailFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("email", String.class);
    }

    @Override
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }

    @Override
    public String getTokenIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }

    @Override
    public Map<String, Object> extractAllClaims(String token) {
        try {
            Claims claims = parseToken(token);
            Map<String, Object> claimsMap = new HashMap<>();
            claims.forEach(claimsMap::put);
            return claimsMap;
        } catch (Exception e) {
            log.error("Erreur lors de l'extraction des claims", e);
            return new HashMap<>();
        }
    }

    @Override
    public void revokeToken(String token) {
        try {
            Claims claims = parseToken(token);
            String tokenId = claims.getId();
            Date expiration = claims.getExpiration();
            long expirationMs = expiration.getTime() - System.currentTimeMillis();

            if (expirationMs > 0) {
                tokenBlacklistService.revokeToken(tokenId, expirationMs).subscribe();
                log.info("Token révoqué avec succès: {}", tokenId);
            }
        } catch (Exception e) {
            log.error("Erreur lors de la révocation du token", e);
            throw new RuntimeException("Échec de la révocation du token", e);
        }
    }

    @Override
    public void revokeAllDeveloperTokens(String developerId) {
        log.info("Révocation de tous les tokens pour le développeur: {}", developerId);
        tokenBlacklistService.revokeAllDeveloperTokens(developerId).subscribe();
    }

    @Override
    public long getExpirationMs() {
        return jwtExpirationMs;
    }

    @Override
    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }

    private Claims parseToken(String token) {
        return jwtParser(false)
                .parseSignedClaims(token)
                .getPayload();
    }

    // ==========================================
    // Méthodes Auth-as-a-Service (pour auth-service)
    // ==========================================

    @Override
    public String generateTokenForUser(String userId, String projectId, String email,
                                        String username, java.util.List<String> roles,
                                        Map<String, Object> customClaims) {
        log.info("Génération de token JWT pour user final: {} (project: {})", userId, projectId);

        String tokenId = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        Map<String, Object> claims = new HashMap<>();

        // Les claims LIBRES d'abord, les invariants de l'autorité ENSUITE.
        //
        // L'ordre était inverse — `claims.putAll(customClaims)` venait en
        // dernier — si bien qu'un appelant pouvait réécrire `type`, `roles`,
        // `userId` et `projectId` en les glissant dans sa carte de claims
        // personnalisés.
        //
        // `type` est le plus grave des quatre : c'est lui, et lui seul, qui
        // distingue un jeton d'ACCÈS d'un jeton de RAFRAÎCHISSEMENT. Les deux
        // portent la même signature et la même audience ; seul ce claim empêche
        // qu'un jeton de longue durée serve de jeton d'accès. Laisser le client
        // le fixer revient à lui laisser décider de la durée de sa session.
        //
        // L'autorité de signature ne doit jamais accepter d'un appelant la
        // valeur d'un claim dont elle est elle-même garante. Les paramètres
        // nommés (`roles`, `userId`) restent sous le contrôle de l'appelant,
        // mais explicitement et par un seul chemin — pas par deux, dont l'un
        // silencieux.
        if (customClaims != null) {
            claims.putAll(customClaims);
        }

        claims.put("userId", userId);
        claims.put("projectId", projectId);
        claims.put("type", "user");

        if (email != null) {
            claims.put("email", email);
        }
        if (username != null) {
            claims.put("username", username);
        }
        if (roles != null && !roles.isEmpty()) {
            claims.put("roles", roles);
        }

        return signedBuilder(claims, tokenId, userId, now, expiryDate).compact();
    }

    /**
     * Jeton de maintenance : le type est posé ici, par l'autorité.
     *
     * <p>Voir {@code IDevJwtService.generateMaintenanceToken} pour le défaut de
     * conception que cette méthode remplace.
     */
    @Override
    public String generateMaintenanceToken(String userId, String email, String username) {
        log.info("Génération d'un token de maintenance pour: {}", userId);

        String tokenId = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("projectId", PROJET_MAINTENANCE);
        claims.put("type", TYPE_MAINTENANCE);
        if (email != null) {
            claims.put("email", email);
        }
        if (username != null) {
            claims.put("username", username);
        }
        claims.put("roles", java.util.List.of("ADMIN"));

        return signedBuilder(claims, tokenId, userId, now, expiryDate).compact();
    }

    @Override
    public String generateRefreshTokenForUser(String userId, String projectId) {
        log.info("Génération de refresh token pour user final: {} (project: {})", userId, projectId);

        String tokenId = UUID.randomUUID().toString();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpirationMs);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("projectId", projectId);
        claims.put("type", "refresh_user");

        return signedBuilder(claims, tokenId, userId, now, expiryDate).compact();
    }

    @Override
    public String extractUserId(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }

    @Override
    public String extractClaim(String token, String claimName) {
        Claims claims = parseToken(token);
        Object value = claims.get(claimName);
        return value != null ? value.toString() : null;
    }

    @Override
    public long getAccessTokenExpiration() {
        return jwtExpirationMs / 1000; // Retourne en secondes
    }
}
