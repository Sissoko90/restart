package com.abdatytch.gateway_service.security;

import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.commons.security.JwtClaimsReader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SecurityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**

 * Validation des jetons d'accès à l'entrée de la plateforme.
 *
 * <p>Ce filtre est le seul contrôle cryptographique que traverse la totalité du
 * trafic. Il vérifie, dans cet ordre :
 * <ol>
 * <li>la signature RS256, la clé étant résolue par le {@code kid} de l'en-tête
 * JWS — indispensable pendant une rotation, où plusieurs clés publiques
 * sont valides simultanément ;</li>
 * <li>l'émetteur, qui doit être l'autorité de la plateforme ;</li>
 * <li>l'expiration (assurée par JJWT) ;</li>
 * <li>l'audience, qui doit être <b>présente</b> et reconnue ;</li>
 * <li>le type, qui doit être un type de jeton d'<b>accès</b> ;</li>
 * <li>la révocation, consultée auprès de l'autorité.</li>
 * </ol>
 *
 * <p>Les claims validés sont publiés dans les attributs d'échange sous
 * {@link #VALIDATED_CLAIMS_ATTRIBUTE} : c'est la seule source d'identité
 * autorisée en aval. Rien qui vienne du client ne doit la renseigner.
 *
 * @see JwtClaimsReader pour la lecture des claims de rôle
 * @see TokenRevocationChecker pour le contrat de révocation
 */
public class JwtWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtWebFilter.class);

    private static final String TOKEN_PREFIX = "Bearer ";

    /** Attribut d'échange portant les claims JWT après validation complète. */
    public static final String VALIDATED_CLAIMS_ATTRIBUTE = "gateway.validatedJwtClaims";

    private final JwksKeyProvider jwksKeyProvider;
    private final TokenRevocationChecker revocationChecker;
    private final String expectedIssuer;
    private final Set<String> acceptedAudiences;

    /**

     * L'émetteur et les audiences sont passés par le constructeur, et non lus par
     * {@code @Value} : ce filtre ne doit pas être un bean, sans quoi WebFlux
     * l'ajouterait à la chaîne globale en plus de celle de Spring Security, et
     * il s'exécuterait deux fois par requête.
     */
    public JwtWebFilter(JwksKeyProvider jwksKeyProvider,
                        TokenRevocationChecker revocationChecker,
                        String expectedIssuer,
                        String expectedAudience) {
        this.jwksKeyProvider = jwksKeyProvider;
        this.revocationChecker = revocationChecker;
        this.expectedIssuer = expectedIssuer;

        // L'analyseur est construit ICI, et non dans un @PostConstruct.
        //
        // L'emetteur arrive en argument de constructeur : rien n'oblige a
        // attendre le conteneur. Et surtout, SecurityConfig comme
        // JwtWebFilterTest construisent ce filtre par `new` — un @PostConstruct
        // n'aurait pas tourne dans le test, l'analyseur serait reste nul, et
        // TOUS les jetons auraient ete refuses. C'est exactement ce que les
        // tests ont signale quand j'ai essaye cette voie.
        this.parser = Jwts.parser()
                .keyLocator(entete -> jwksKeyProvider.keyLocator().locate(entete))
                .requireIssuer(expectedIssuer)
                .build();
        String audienceCsv = expectedAudience == null ? "" : expectedAudience;
        this.acceptedAudiences = Arrays.stream(audienceCsv.split(","))
                .map(String::trim)
                .filter(audience -> !audience.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        if (acceptedAudiences.isEmpty()) {
            // Signalé au démarrage : sinon la panne n'apparaît qu'au premier appel
            // authentifié, sous la forme d'un 401 sans explication.
            log.error("`jwt.audience` n'est pas configurée : TOUS les jetons seront refusés.");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(TOKEN_PREFIX)) {
            // Aucune authentification n'est établie : `authorizeExchange` refusera
            // ensuite l'accès. On ne court-circuite pas ici, car certains chemins
            // sont légitimement anonymes.
            log.debug("Aucun jeton présenté pour {} — l'autorisation tranchera", path);
            return chain.filter(exchange);
        }

        String token = authHeader.substring(TOKEN_PREFIX.length());

        return validateToken(token, path)
                // switchIfEmpty sur le Mono<Claims>, et SURTOUT PAS sur le
                // Mono<Void> final.
                //
                // Une validation qui ne rend ni valeur ni erreur ferait que la
                // requete n'est ni authentifiee ni refusee : perdue, et le
                // client attend jusqu'a son delai d'expiration. Un jeton dont on
                // ne peut rien dire est un jeton refuse.
                //
                // Le poser sur le resultat final serait catastrophique :
                // authenticate() rend un Mono<Void> qui se termine VIDE en cas
                // de SUCCES, donc toute requete valide recevrait un 401. J'ai
                // commis cette erreur en ecrivant ce correctif, apres l'avoir
                // documentee trois fois ailleurs — elle est tres facile a faire.
                //
                // L'erreur est routee vers le onErrorResume existant, qui
                // repond sans dire pourquoi.
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "validation de jeton sans resultat")))
                .flatMap(claims -> revocationChecker
                        // La date d'EMISSION est transmise : une revocation
                        // globale ne doit frapper que les jetons anterieurs a
                        // elle. Sans ce troisieme argument, le verificateur
                        // faisait un hasKey et rejetait aussi les jetons emis
                        // APRES la revocation, pendant les sept jours de vie de
                        // la cle.
                        .isRevoked(claims.getId(), claims.getSubject(),
                                claims.getIssuedAt() == null
                                        ? null : claims.getIssuedAt().toInstant())
                        .flatMap(revoked -> {
                            if (Boolean.TRUE.equals(revoked)) {
                                log.warn("Jeton révoqué présenté par {} sur {}",
                                        claims.getSubject(), path);
                                return unauthorized(exchange);
                            }
                            return authenticate(exchange, chain, claims, path);
                        }))
                .onErrorResume(e -> {
                    // Le détail reste dans les journaux : la réponse ne dit jamais
                    // POURQUOI le jeton est refusé, pour ne pas guider un attaquant.
                    log.warn("Jeton refusé sur {} : {}", path, e.getMessage());
                    return unauthorized(exchange);
                });
    }

    private Mono<Void> authenticate(ServerWebExchange exchange, WebFilterChain chain,
                                    Claims claims, String path) {
        List<String> roles = JwtClaimsReader.roles(claims);

        exchange.getAttributes().put(VALIDATED_CLAIMS_ATTRIBUTE, claims);

        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        log.debug("Jeton valide — sujet {}, rôles {}, chemin {}",
                claims.getSubject(), roles, path);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities);

        return chain.filter(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    /**
     * L'unique chemin ou un jeton de type {@code oauth2_temp} est recevable.
     *
     * <p>Ce jeton est emis par auth-service a la fin d'un parcours OAuth2
     * incomplet, et ne sert qu'a terminer l'inscription. Il voyage desormais
     * dans {@code Authorization: Bearer} — un seul canal de justificatif pour
     * tout le service — ce qui le fait passer par ce filtre.
     *
     * <p>{@code requireAccessToken} le refuserait : ce n'est pas un jeton
     * d'acces, et il ne doit effectivement pas en ouvrir la surface. L'exception
     * est donc <b>bornee a ce chemin</b>, ici comme dans auth-service, qui exige
     * en outre l'autorite {@code ROLE_OAUTH2_TEMP}. Presente ailleurs, ce jeton
     * est refuse des deux cotes.
     */
    private static final String CHEMIN_DU_JETON_TEMPORAIRE =
            "/api/v1/auth-sdk/oauth2/complete-profile";

    /**
     * L'analyseur JWT, construit UNE fois.
     *
     * <p>{@code Jwts.parser()...build()} etait appele dans {@link #validateToken},
     * donc <b>a chaque requete</b> traversant la passerelle. Or {@code build()}
     * de jjwt fait une recherche {@link java.util.ServiceLoader}, qui <b>lit des
     * JAR sur le disque</b>.
     *
     * <p>Le {@code Mono.fromCallable} ci-dessous n'y change rien : sans saut
     * d'ordonnanceur, il s'execute sur le fil qui souscrit — une EventLoop
     * Netty. C'est donc un appel bloquant sur le chemin de chaque requete, dans
     * le composant le plus chaud de la plateforme.
     *
     * <p>Cacher l'analyseur est sur : {@code keyLocator()} resout la cle au
     * moment de l'analyse, par le {@code kid} de l'en-tete JWS, donc la rotation
     * de cles continue de fonctionner — c'est precisement ce que le javadoc de
     * {@code JwksKeyProvider.keyLocator()} garantit. L'audience, elle, est
     * verifiee separement par {@code requireValidAudience}.
     */
    private final io.jsonwebtoken.JwtParser parser;

    private Mono<Claims> validateToken(String token, String path) {
        return Mono.fromCallable(() -> {
            if (jwksKeyProvider.getDefaultPublicKey() == null) {
                throw new IllegalStateException("Aucune clé publique disponible pour la validation");
            }

            // L'analyseur construit au demarrage : voir le champ.
            Claims claims = parser
                    .parseSignedClaims(token)
                    .getPayload();

            requireValidAudience(claims);
            // Le jeton temporaire d'OAuth2 n'est pas un jeton d'acces, et ne
            // doit pas en ouvrir la surface : l'exception est bornee au seul
            // chemin qui le consomme.
            if (!CHEMIN_DU_JETON_TEMPORAIRE.equals(path)) {
                requireAccessToken(claims);
            }

            return claims;
        });
    }

    /**

     * L'audience doit être présente ET reconnue.
     *
     * <p>La version précédente ne validait l'audience que lorsque le claim
     * existait. Un jeton dépourvu d'{@code aud} passait donc sans contrôle — or
     * un jeton sans destinataire n'est destiné à personne, et l'accepter revient
     * à honorer n'importe quel jeton signé par l'autorité, quel qu'en soit
     * l'usage prévu.
     */
    private void requireValidAudience(Claims claims) {
        Set<String> accepted = acceptedAudiences;

        if (accepted.isEmpty()) {
            // Refuser tout serait une panne silencieuse ; accepter tout serait une
            // faille silencieuse. On refuse, en disant pourquoi.
            throw new IllegalStateException(
                    "`jwt.audience` n'est pas configurée : impossible de valider l'audience");
        }

        Object audienceClaim = claims.get("aud");

        if (audienceClaim instanceof String single) {
            if (!accepted.contains(single)) {
                throw new SecurityException("Audience non reconnue : " + single);
            }
            return;
        }
        if (audienceClaim instanceof Collection<?> audiences && !audiences.isEmpty()) {
            boolean match = audiences.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .anyMatch(accepted::contains);
            if (!match) {
                throw new SecurityException("Audience non reconnue : " + audiences);
            }
            return;
        }

        throw new SecurityException("Jeton sans audience");
    }

    /**

     * Seul un jeton d'accès ouvre les routes.
     *
     * <p>Un jeton de rafraîchissement porte une signature tout aussi valide et
     * la même audience : rien ne le distinguait d'un jeton d'accès pour ce
     * filtre. Comme il vit bien plus longtemps, l'accepter revenait à offrir une
     * session de la durée du rafraîchissement.
     */
    private void requireAccessToken(Claims claims) {
        if (!JwtClaimsReader.isAccessToken(claims)) {
            throw new SecurityException("Type de jeton non recevable en accès : " + claims.get("type"));
        }
    }

    /**
     * Chemins de la surface SDK joignables sans jeton.
     *
     * <p>Miroir des {@code permitAll} de {@code auth-service/SecurityConfig},
     * exprimes avec le prefixe public {@code /api/v1/auth-sdk} que le Gateway
     * reecrit en {@code /auth}. Verrouille par {@code SurfacePubliqueSdkTest}.
     */
    private static final java.util.Set<String> CHEMINS_PUBLICS_SDK = java.util.Set.of(
            "/api/v1/auth-sdk/login",
            "/api/v1/auth-sdk/refresh",
            "/api/v1/auth-sdk/forgot-password",
            "/api/v1/auth-sdk/reset-password",
            "/api/v1/auth-sdk/resend-code",
            "/api/v1/auth-sdk/phone/auth-request",
            "/api/v1/auth-sdk/phone/auth-verify",
            "/api/v1/auth-sdk/phone/register-request",
            "/api/v1/auth-sdk/phone/register-verify",
            "/api/v1/auth-sdk/email/auth-request",
            "/api/v1/auth-sdk/email/auth-verify",
            "/api/v1/auth-sdk/email/register",
            "/api/v1/auth-sdk/email/register-verify",
            "/api/v1/auth-sdk/biometric/authenticate/initiate",
            "/api/v1/auth-sdk/biometric/authenticate/verify",
            "/api/v1/auth-sdk/oauth2/callback",
            // Echange du code de remise contre les jetons : voir SecurityConfig.
            // Sans cette ligne, le parcours OAuth2 aboutirait, la redirection
            // arriverait avec son code, et le frontal se verrait refuser
            // l'echange en 401 — la correction aurait casse le parcours qu'elle
            // protege.
            "/api/v1/auth-sdk/oauth2/exchange",
            // Fournisseurs sociaux utilisables : l'ecran de connexion en a
            // besoin AVANT toute session, pour n'afficher que les boutons qui
            // fonctionnent. Sans cette ligne, le Gateway exige un jeton pour
            // savoir comment en obtenir un.
            "/api/v1/auth-sdk/oauth2/providers",
            "/api/v1/auth-sdk/admin/login",
            "/api/v1/auth-sdk/admin/totp/enroll",
            "/api/v1/auth-sdk/admin/totp/reset");

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/v1/auth/")
                // Accuses de reception SMS : AfrikSMS n'a aucun jeton a
                // presenter. Sans cette ligne, le filtre exigerait un JWT et
                // tous les accuses seraient rejetes en 401 — un point d'entree
                // ecrit, route, et jamais atteint.
                || path.startsWith("/api/v1/sms/accuses/")
                // Surface SDK d'auth-service : tout ce qui PRECEDE l'obtention
                // d'un jeton. Sans ces lignes, le filtre exigerait un jeton
                // pour obtenir un jeton — et c'est ce qu'il faisait : la
                // connexion d'un utilisateur final repondait 401 au Gateway,
                // avant meme d'atteindre auth-service.
                //
                // Cette liste DOIT correspondre aux permitAll de
                // auth-service/SecurityConfig. SurfacePubliqueSdkTest le
                // verifie : deux listes qui decident de la meme chose, dans deux
                // services, divergent sans cela — et la divergence produit un
                // 401 que rien ne rattache a un desaccord de configuration.
                || CHEMINS_PUBLICS_SDK.contains(path)
                || path.startsWith("/api/v1/auth-sdk/oauth2/authorize/")
                || path.startsWith("/api/v1/auth-sdk/oauth2/callback/")
                || path.startsWith("/api/v1/admin/login")
                || path.startsWith("/api/v1/admin/totp/qr-code")
                || path.startsWith("/api/v1/maintenance/")
                || path.startsWith("/actuator/")
                || path.equals("/")
                || path.startsWith("/.well-known/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }
}
