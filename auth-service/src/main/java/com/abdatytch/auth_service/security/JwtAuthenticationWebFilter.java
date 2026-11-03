package com.abdatytch.auth_service.security;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.commons.security.JwtClaimsReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Pose l'identité du porteur du jeton dans le contexte de sécurité réactif.
 *
 * <p>Ce filtre n'autorise rien et ne refuse rien : il authentifie, ou laisse
 * passer sans identité. C'est {@code SecurityConfig} qui décide ensuite, et
 * l'absence d'identité y produit un 401. La conséquence est qu'un défaut dans ce
 * filtre ne se voit jamais ici : il se voit comme un 401 inexplicable sur un
 * endpoint protégé.
 *
 * <h2>Le défaut corrigé, et pourquoi il était invisible</h2>
 *
 * Ce filtre exigeait {@code type == "developer"} :
 *
 * <pre>{@code if (!"developer".equals(tokenType)) return chain.filter(exchange);}</pre>
 *
 * Or les jetons émis par auth-service lui-même portent {@code type: "user"} —
 * {@code generateToken} délègue la signature à interface-service, dont
 * {@code generateTokenForUser} pose cette valeur. <b>Aucun jeton délivré par
 * {@code POST /auth/admin/login} n'authentifiait donc dans auth-service</b>, ce
 * qui rendait inatteignables la totalité de {@code /admin/**},
 * {@code /auth-users/**} et {@code /auth/erasure/**} : un administrateur
 * obtenait un jeton valide, puis un 401 à chaque usage.
 *
 * <p>Deux défauts s'empilaient derrière celui-là, et auraient persisté une fois
 * le premier corrigé :
 *
 * <ol>
 *   <li>seul {@code rolesList.get(0)} était retenu. {@code UserRole.expand()}
 *       résout la hiérarchie à l'émission, donc le jeton d'un SUPER_ADMIN porte
 *       {@code [ROLE_SUPER_ADMIN, ROLE_ADMIN]} : la seconde autorité était
 *       perdue, et un SUPER_ADMIN se voyait refuser {@code /admin/**} que son
 *       subordonné obtenait ;</li>
 *   <li>le claim était transmis brut comme autorité, sans préfixe. Un jeton de
 *       développeur porte {@code role: "DEVELOPER"}, et {@code hasRole} exige
 *       {@code ROLE_DEVELOPER}.</li>
 * </ol>
 *
 * <p>Les trois sont traités par {@code JwtClaimsReader}, qui existe pour cela :
 * il connaît les deux formes du claim de rôle, la liste des types recevables
 * comme jeton d'accès, et le préfixe attendu par Spring Security.
 *
 * <h2>Pas de {@code @Component}</h2>
 *
 * WebFlux place dans la chaîne HTTP <b>tout</b> bean de type {@code WebFilter},
 * et Spring Security en est un lui-même. Ce filtre, annoté {@code @Component}
 * <b>et</b> ajouté par {@code addFilterAt}, analysait donc le jeton <b>deux fois
 * par requête</b>. Voir la javadoc de {@code SecurityConfig} ;
 * {@code FilterWiringTest} verrouille la règle.
 */
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationWebFilter implements WebFilter {

    /** Valeur du claim {@code type} pour un jeton du portail développeur. */
    private static final String TYPE_DEVELOPPEUR = "developer";

    /** La seule autorité qu'un jeton de développeur puisse obtenir ici. */
    private static final String AUTORITE_DEVELOPPEUR = "ROLE_DEVELOPER";

    /** Valeur du claim {@code type} pour le jeton temporaire d'OAuth2. */
    private static final String TYPE_TEMPORAIRE_OAUTH2 = "oauth2_temp";

    /**
     * Autorité accordée à un jeton temporaire, et à lui seul.
     *
     * <p>Distincte de tout rôle d'utilisateur : la matrice d'autorisation exige
     * {@code hasRole("OAUTH2_TEMP")} sur l'unique endpoint concerné, et aucun
     * autre chemin ne la demande.
     */
    private static final String AUTORITE_TEMPORAIRE_OAUTH2 = "ROLE_OAUTH2_TEMP";

    /** L'unique chemin où un jeton temporaire authentifie quelque chose. */
    private static final String CHEMIN_DU_JETON_TEMPORAIRE = "/auth/oauth2/complete-profile";

    private final IJwtService jwtService;
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(AppConstants.JWT_HEADER);
        
        // Si pas de header Authorization ou ne commence pas par "Bearer "
        if (authHeader == null || !authHeader.startsWith(AppConstants.JWT_PREFIX)) {
            return chain.filter(exchange);
        }
        
        String jwt = authHeader.substring(AppConstants.JWT_PREFIX.length());
        
        // Validation réactive du token JWT (100% non-bloquant)
        return jwtService.validateTokenReactive(jwt)
            // Pose sur le Mono<BOOLEAN>, et surtout pas sur le Mono<Void> final :
            // chain.filter() se termine vide quand tout va bien, donc un
            // defaultIfEmpty place en fin de chaine se declencherait a CHAQUE
            // requete acceptee. L'erreur a deja ete commise une fois dans ce
            // depot, au Gateway.
            //
            // Ici, un Mono vide rendu par la validation ferait disparaitre la
            // requete. Ce filtre ne refuse jamais : il transmet sans identite et
            // laisse SecurityConfig decider.
            .defaultIfEmpty(Boolean.FALSE)
            .flatMap(isValid -> {
                if (Boolean.TRUE.equals(isValid)) {
                    try {
                        String userId = jwtService.getUserIdFromToken(jwt);
                        Map<String, Object> claims = jwtService.extractAllClaims(jwt);

                        // Un jeton de RAFRAICHISSEMENT porte la meme signature et la
                        // meme audience qu'un jeton d'acces, mais vit bien plus
                        // longtemps : l'accepter ici accorderait une session de la
                        // duree du rafraichissement. Meme raison pour temp_2fa, qui
                        // n'atteste que d'un premier facteur.
                        // Jeton temporaire OAuth2 : authentifie, mais pour UN
                        // SEUL chemin.
                        //
                        // Le controle de chemin est ici, a l'endroit ou
                        // l'autorite est accordee, et non laisse a la matrice
                        // d'autorisation. La raison est concrete : les regles
                        // de cette matrice sont des `.authenticated()`, qu'un
                        // jeton temporaire satisferait partout — y compris
                        // /auth/erasure/me, qui declenche un effacement RGPD.
                        // Un utilisateur a demi inscrit pourrait alors effacer
                        // un compte.
                        //
                        // La portee du jeton EST cet endpoint : l'encoder ici
                        // la rend impossible a oublier ailleurs. Un jeton
                        // temporaire presente sur un autre chemin n'authentifie
                        // rien, et la requete repond 401.
                        if (TYPE_TEMPORAIRE_OAUTH2.equals(claims.get("type"))) {
                            if (!CHEMIN_DU_JETON_TEMPORAIRE.equals(
                                    exchange.getRequest().getPath().value())) {
                                log.warn("Jeton temporaire OAuth2 présenté hors de son "
                                        + "unique chemin autorisé");
                                return chain.filter(exchange);
                            }
                            UsernamePasswordAuthenticationToken temporaire =
                                    new UsernamePasswordAuthenticationToken(
                                            userId, null,
                                            List.of(new SimpleGrantedAuthority(
                                                    AUTORITE_TEMPORAIRE_OAUTH2)));
                            return chain.filter(exchange).contextWrite(
                                    ReactiveSecurityContextHolder.withAuthentication(
                                            temporaire));
                        }

                        if (!JwtClaimsReader.isAccessToken(claims)) {
                            log.warn("Jeton refusé : type non recevable comme jeton d'accès ({})",
                                    claims.get("type"));
                            return chain.filter(exchange);
                        }

                        List<SimpleGrantedAuthority> autorites = autoritesDe(claims).stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList();

                        UsernamePasswordAuthenticationToken authToken =
                                new UsernamePasswordAuthenticationToken(userId, null, autorites);

                        log.debug("Jeton validé pour {} (type {}, autorités {})",
                                userId, claims.get("type"), autorites);

                        return chain.filter(exchange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authToken));
                    } catch (Exception e) {
                        log.error("Erreur lors de l'extraction des claims JWT: {}", e.getMessage());
                        return chain.filter(exchange);
                    }
                }
                
                // Token invalide, continuer sans authentification
                log.debug("JWT invalide, accès non authentifié");
                return chain.filter(exchange);
            })
            .onErrorResume(e -> {
                log.error("Erreur lors de la validation du JWT: {}", e.getMessage());
                return chain.filter(exchange);
            });
    }

    /**
     * Autorités accordées selon le <b>type</b> du jeton, et non selon le nom du
     * rôle qu'il déclare.
     *
     * <h2>La collision qu'il faut absolument éviter</h2>
     *
     * Deux espaces de rôles indépendants coexistent et <b>partagent des noms</b> :
     *
     * <ul>
     *   <li>les rôles de la plateforme, l'énumération {@code UserRole}, portés par
     *       les jetons {@code type: "user"} sous le claim {@code roles} ;</li>
     *   <li>les rôles du portail développeur, de simples chaînes en base, portés
     *       par les jetons {@code type: "developer"} sous le claim {@code role}.
     *       L'{@code AdminSeeder} d'interface-service y crée un compte de rôle
     *       {@code "ADMIN"}.</li>
     * </ul>
     *
     * Accorder une autorité d'après le nom du rôle ferait donc d'un
     * administrateur du <i>portail</i> un administrateur de la <i>plateforme
     * d'authentification</i> — une élévation de privilège obtenue sans rien
     * forger, par simple homonymie.
     *
     * <p>Un jeton de développeur reçoit par conséquent {@code ROLE_DEVELOPER} et
     * rien d'autre, quoi qu'annonce son claim de rôle. C'est ce que l'ancien code
     * voulait manifestement faire — sa valeur par défaut était déjà
     * {@code ROLE_DEVELOPER} — mais il laissait le claim l'écraser.
     */
    private List<String> autoritesDe(Map<String, Object> claims) {
        if (TYPE_DEVELOPPEUR.equals(claims.get("type"))) {
            return List.of(AUTORITE_DEVELOPPEUR);
        }
        return JwtClaimsReader.authorities(claims);
    }
}
