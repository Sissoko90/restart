package com.abdatytch.media_service.security;

import com.abdatytch.commons.security.JwtClaimsReader;

import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.media_service.constants.SecurityConstants;
import com.abdatytch.media_service.service.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**

 * Filtre WebFlux pour valider les tokens JWT
 * Utilise JwksKeyProvider pour récupérer les clés publiques depuis auth-service
 * Supporte la rotation des clés sans redéploiement
 */
@Component
@Order(2)
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationWebFilter implements WebFilter {

    private final JwksKeyProvider jwksKeyProvider;
    private final TokenBlacklistService tokenBlacklistService;

    /**

     * Émetteur attendu : interface-service est l'autorité de signature de la plateforme.
     * Aligné sur `jwt.issuer` de config-repo/dev/application-dev.yml.
     */
    @Value("${jwt.issuer:interface-service}")
    private String expectedIssuer;

    /**

     * Audience attendue pour ce service. Le token en porte plusieurs ;
     * il suffit que celle-ci figure dans la liste.
     */
    @Value("${jwt.audience:media-service}")
    private String expectedAudience;

    /**
     * L'analyseur JWT, construit UNE fois.
     *
     * <h3>Le defaut corrige</h3>
     *
     * {@code Jwts.parser()...build()} etait appele dans {@code validateJwtToken},
     * donc <b>a chaque requete portant un jeton</b>. Or {@code build()} de jjwt
     * fait une recherche {@link java.util.ServiceLoader}, qui <b>lit des JAR sur
     * le disque</b> :
     *
     * <pre>
     * BlockingOperationError: Blocking call! java.io.RandomAccessFile#readBytes
     *   at java.util.ServiceLoader$LazyClassPathLookupIterator.parse
     *   at io.jsonwebtoken.impl.lang.Services.loadFirst
     *   at io.jsonwebtoken.impl.DefaultJwtParserBuilder.build
     *   at JwtAuthenticationWebFilter.validateJwtToken
     * </pre>
     *
     * Un appel bloquant sur une EventLoop, sur le chemin de chaque requete
     * authentifiee — exactement ce que la politique de ce depot interdit. Le
     * cout etait reel en plus : un analyseur construit par requete.
     *
     * <h3>Pourquoi il n'etait visible qu'en integration continue</h3>
     *
     * jjwt met le resultat de sa recherche en cache. Le <b>premier</b> appel
     * paie la lecture disque ; les suivants non. En local, un test precedent
     * rechauffait ce cache depuis un fil ordinaire avant que BlockHound ne soit
     * arme. Sur le runner, l'ordre des classes de test etait different — et cet
     * ordre depend du balayage du systeme de fichiers, qui n'est pas le meme sur
     * macOS et sur Linux.
     *
     * <h3>Pourquoi cacher l'analyseur est sur</h3>
     *
     * {@code JwksKeyProvider.keyLocator()} resout la cle <b>au moment de
     * l'analyse</b>, par le {@code kid} de l'en-tete JWS, en lisant le cache
     * vivant des cles. Un analyseur reutilise suit donc la rotation de cles
     * comme un analyseur neuf. Ce qui est fige, ce sont l'emetteur et l'audience
     * attendus — qui ne changent pas a chaud.
     */
    private volatile io.jsonwebtoken.JwtParser parser;

    /**
     * Construit l'analyseur apres l'injection des {@code @Value}.
     *
     * <p>Et non dans le constructeur : {@code expectedIssuer} et
     * {@code expectedAudience} y seraient encore nuls, et
     * {@code requireIssuer(null)} ferait echouer toute verification.
     */
    @jakarta.annotation.PostConstruct
    void construireLAnalyseur() {
        parser = Jwts.parser()
                .keyLocator(entete -> jwksKeyProvider.keyLocator().locate(entete))
                .requireIssuer(expectedIssuer)
                .requireAudience(expectedAudience)
                .build();
    }

    /**
     * Peuple le contexte de sécurité quand un jeton est présent, et ne refuse
     * <b>jamais</b> l'absence de jeton.
     *
     * <h3>Le défaut corrigé : ce filtre décidait à la place de SecurityConfig</h3>
     *
     * Il rendait 401 dès qu'un chemin hors de sa propre liste de chemins publics
     * arrivait sans en-tête {@code Authorization}. Deux conséquences :
     *
     * <ul>
     *   <li><b>la documentation OpenAPI était injoignable</b>, y compris par la
     *       passerelle. Elle est en {@code permitAll} dans {@code SecurityConfig}
     *       — à dessein, un navigateur qui charge Swagger ne porte aucun jeton —
     *       mais ce filtre s'exécute à {@code AUTHENTICATION}, donc <b>avant</b>
     *       la décision d'autorisation, et la refusait ;</li>
     *   <li>deux détenteurs pour une même règle : la liste de ce filtre et les
     *       {@code pathMatchers} de {@code SecurityConfig}. Le défaut dominant
     *       de ce dépôt, dans sa forme la plus coûteuse — celui des deux qui
     *       s'exécute en premier gagne, et ce n'est pas celui qu'on lit.</li>
     * </ul>
     *
     * <p>Le patron retenu est celui des <b>trois autres services</b> (quotas,
     * setting, auth) : un filtre d'authentification <b>authentifie</b>, il
     * n'autorise pas. Sans jeton, la chaîne continue sans identité et
     * {@code anyExchange().authenticated()} refuse — donc les endpoints métier
     * restent protégés, par la couche qui a le droit de le dire.
     *
     * <p>Un jeton <b>invalide</b> reste refusé ici, et c'est délibéré : une
     * signature fausse, un jeton expiré ou révoqué sont des faits que seule
     * cette couche sait établir, et les laisser passer silencieusement
     * reviendrait à traiter un faux jeton comme une absence de jeton.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        String authHeader = exchange.getRequest().getHeaders()
            .getFirst(SecurityConstants.HEADER_AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(SecurityConstants.BEARER_PREFIX)) {
            log.debug("Aucun jeton porteur sur {} : la chaine decide", path);
            return chain.filter(exchange);
        }

        String token = authHeader.substring(SecurityConstants.BEARER_PREFIX.length());

        return validateJwtToken(token, exchange, chain, path);
    }

    private Mono<Void> validateJwtToken(String token, ServerWebExchange exchange, WebFilterChain chain, String path) {
        try {
            // Vérifier qu'au moins une clé publique est disponible
            if (jwksKeyProvider.getDefaultPublicKey() == null) {
                log.error(" No public key available for JWT validation");
                exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                return exchange.getResponse().setComplete();
            }

            // 1. Parser et vérifier signature (clé résolue par `kid` de l'en-tête JWS,
            // indispensable pendant une rotation de clés)
            // L'analyseur est celui construit au demarrage : le reconstruire ici
            // ferait une lecture disque sur une EventLoop. Voir le champ.
            Claims claims = parser
                .parseSignedClaims(token)
                .getPayload();

            // 2. Vérifier expiration explicitement
            if (claims.getExpiration() != null && claims.getExpiration().before(new java.util.Date())) {
                log.warn(" Expired JWT token for path: {}", path);
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                exchange.getResponse().getHeaders().add("X-Error-Reason", "TOKEN_EXPIRED");
                return exchange.getResponse().setComplete();
            }

            // 3. Vérifier blacklist
            return tokenBlacklistService.isBlacklisted(token)
                // Un Mono vide ici ne doit pas passer pour un succes.
                //
                // Sans ce defaut, un `flatMap` qui ne s'execute pas rend un
                // Mono<Void> vide : l'echange se termine sans que personne
                // n'ecrive de statut, et le client recoit 200 OK avec un corps
                // VIDE. La requete n'a jamais atteint le gestionnaire, et
                // l'appelant la croit reussie — sur un envoi de document, un
                // non-evenement rapporte comme un succes.
                //
                // `TokenBlacklistServiceImpl` garantit deja de ne jamais rendre
                // vide, et c'est lui qui detient la POLITIQUE (inconnu =
                // revoque). Ce defaut-ci protege le FLOT DE CONTROLE du filtre,
                // qui ne peut pas supposer que toute implementation honore le
                // contrat. Les deux ne disent pas la meme chose.
                .defaultIfEmpty(Boolean.TRUE)
                .flatMap(isBlacklisted -> {
                    if (Boolean.TRUE.equals(isBlacklisted)) {
                        log.warn(" Blacklisted token used for path: {}", path);
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        exchange.getResponse().getHeaders().add("X-Error-Reason", "TOKEN_REVOKED");
                        return exchange.getResponse().setComplete();
                    }

                    // 4. Token valide - extraire informations
                    String userId = claims.getSubject();
                    log.debug(" JWT validated for user: {}", userId);

                    // Stocker les informations dans les attributs
                    exchange.getAttributes().put("userId", userId);
                    exchange.getAttributes().put("claims", claims);

                    // Extraire le rôle depuis les CLAIMS VALIDÉS du token.
                    // Ne JAMAIS le lire depuis le header X-User-Role : ce header est
                    // fourni par l'appelant et recopié tel quel par le Gateway lorsqu'il
                    // ne parvient pas à le dériver du JWT -> usurpation de rôle possible.
                    String userRole = JwtClaimsReader.primaryRole(claims);
                    if (userRole != null) {
                        exchange.getAttributes().put("userRole", userRole);
                        log.debug(" User role from validated claims: {}", userRole);
                    }

                    log.info(" JWT valid for user: {} accessing path: {}", userId, path);

                    // La propagation du contexte de securite, SANS laquelle ce
                    // service refusait TOUS ses endpoints.
                    //
                    // Ce filtre validait le jeton correctement puis transmettait
                    // sans rien deposer dans le contexte reactif. Or
                    // `SecurityConfig` exige `anyExchange().authenticated()` et
                    // `hasRole("ADMIN")`, et l'AuthorizationWebFilter installe
                    // par `authorizeExchange` s'execute APRES l'authentification.
                    // Il ne trouvait aucune identite : /media/**,
                    // /admin/documents/** et /admin/document-types/** etaient
                    // refuses malgre un jeton parfaitement valide.
                    //
                    // media-service etait le SEUL des cinq services a ne pas
                    // produire d'Authentication, et le seul dont la couche
                    // declarative l'exigeait. Le durcissement qui a remplace
                    // `permitAll()` par `authenticated()` etait juste ; il
                    // supposait que les filtres maison peuplaient le contexte.
                    //
                    // Toutes les autorites, et non la premiere : un SUPER_ADMIN
                    // en porte deux, et n'en garder qu'une lui retirait la
                    // surface de son subordonne. La lecture et le prefixe ROLE_
                    // sont delegues a JwtClaimsReader, seul detenteur de cette
                    // regle dans la plateforme.
                    var autorites = JwtClaimsReader.authorities(claims).stream()
                            .map(SimpleGrantedAuthority::new)
                            .toList();

                    var authentification = new UsernamePasswordAuthenticationToken(
                            userId, null, autorites);

                    return chain.filter(exchange).contextWrite(
                            ReactiveSecurityContextHolder.withAuthentication(authentification));
                });

        } catch (ExpiredJwtException e) {
            log.warn(" Expired JWT token: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("X-Error-Reason", "TOKEN_EXPIRED");
            return exchange.getResponse().setComplete();
        } catch (SignatureException e) {
            log.warn(" Invalid JWT signature: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("X-Error-Reason", "INVALID_SIGNATURE");
            return exchange.getResponse().setComplete();
        } catch (MalformedJwtException e) {
            log.warn(" Malformed JWT token: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("X-Error-Reason", "MALFORMED_TOKEN");
            return exchange.getResponse().setComplete();
        } catch (Exception e) {
            log.error(" JWT validation error: {}", e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("X-Error-Reason", "VALIDATION_ERROR");
            return exchange.getResponse().setComplete();
        }
    }

    // `isPublicEndpoint` a ete RETIREE.
    //
    // Elle tenait d'abord sa propre copie de
    // `path.matches(motif.replace("**", ".*"))`, puis deleguait a
    // SecurityConstants. Les deux formes avaient le meme tort de fond : ce
    // filtre n'a pas a savoir ce qui est public.
    //
    // Un filtre d'AUTHENTIFICATION authentifie. L'autorisation appartient a
    // SecurityConfig, dont les `pathMatchers` sont desormais le seul juge — et
    // ils viennent de CheminsPublics, partage par les cinq services.
    //
    // Tant que cette methode existait, la liste du filtre et celle de
    // SecurityConfig etaient deux detenteurs d'une meme regle, et c'est le
    // filtre qui gagnait puisqu'il s'execute a AUTHENTICATION, avant la
    // decision d'autorisation. La documentation declaree `permitAll` etait donc
    // refusee en 401.

}
