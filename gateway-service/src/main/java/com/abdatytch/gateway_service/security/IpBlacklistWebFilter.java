package com.abdatytch.gateway_service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**

 * Refuse le trafic des IP mises en liste noire.
 *
 * <p><b>Clé partagée.</b> {@code security:ip:blacklist:<ip>}, dans la base de
 * sécurité partagée. Le nom et l'emplacement comptent autant que le mécanisme :
 * trois conventions coexistaient auparavant — un ensemble {@code ip_blacklist}
 * ici, {@code ip:blacklist:<ip>} dans la console d'administration,
 * {@code blacklist:ip:<ip>} dans le filtre d'interface-service — et dans trois
 * bases Redis différentes. Bloquer une IP depuis la console n'avait donc aucun
 * effet sur le trafic entrant. Le blocage était écrit, jamais lu.
 *
 * <p>Les entrées portent une durée de vie : une liste noire sans expiration
 * finit par bloquer des IP recyclées entre-temps par leur opérateur.
 */
public class IpBlacklistWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(IpBlacklistWebFilter.class);

    /**
     * Convention partagée, désormais détenue par
     * {@link com.abdatytch.commons.security.ClesRedisSecurite}.
     *
     * <p>Le littéral vivait ici, et security-service a besoin du même pour
     * ALIMENTER cette liste. Deux littéraux dans deux modules auraient divergé
     * en silence : lire une clé qui n'existe pas rend simplement « pas bloqué »,
     * donc la divergence ne produit aucune erreur — seulement une liste noire
     * qui ne bloque rien.
     *
     * <p>Les constantes restent exposées ici pour les appelants existants, mais
     * elles ne portent plus la valeur.
     */
    public static final String BLACKLIST_KEY_PREFIX =
            com.abdatytch.commons.security.ClesRedisSecurite.PREFIXE_IP_BLOQUEE;
    public static final String VIOLATION_KEY_PREFIX =
            com.abdatytch.commons.security.ClesRedisSecurite.PREFIXE_VIOLATIONS_IP;

    private static final int VIOLATIONS_BEFORE_BLACKLIST = 10;
    private static final Duration VIOLATION_WINDOW = Duration.ofMinutes(5);
    private static final Duration BLACKLIST_TTL = Duration.ofDays(1);

    private final ReactiveRedisTemplate<String, String> sharedSecurityRedis;
    private final ClientIpResolver clientIpResolver;

    public IpBlacklistWebFilter(ReactiveRedisTemplate<String, String> sharedSecurityRedis,
                                ClientIpResolver clientIpResolver) {
        this.sharedSecurityRedis = sharedSecurityRedis;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange);

        return sharedSecurityRedis.hasKey(BLACKLIST_KEY_PREFIX + clientIp)
                // defaultIfEmpty est pose sur le Mono<Boolean>, PAS sur le
                // Mono<Void> final.
                //
                // Sans lui, une reponse vide de Redis faisait que le flatMap ne
                // s'executait jamais : la requete n'etait ni transmise ni
                // repondue, et le client attendait indefiniment. onErrorResume
                // ne s'applique pas — il n'y a pas d'erreur, juste une absence
                // de valeur. Un filtre doit TOUJOURS soit poursuivre la chaine,
                // soit repondre.
                //
                // Le place sur le resultat final serait le piege inverse,
                // repertorie dans ce depot : chain.filter() rend un Mono<Void>
                // qui se termine vide en cas de SUCCES, donc le repli se
                // declencherait a chaque requete acceptee.
                //
                // La valeur de repli est `false`, conformement a l'ouverture en
                // cas de panne assumee par ce filtre.
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(blacklisted -> {
                    if (Boolean.TRUE.equals(blacklisted)) {
                        log.warn(" Requête refusée — IP en liste noire : {} sur {}",
                                clientIp, exchange.getRequest().getPath().value());
                        return forbidden(exchange);
                    }
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> {
                    // Fail-open assumé : une panne Redis ne doit pas fermer la
                    // plateforme à tout le monde pour protéger contre quelques IP.
                    // Le compromis est l'inverse de celui de la révocation, où
                    // c'est l'identité elle-même qui est en cause.
                    log.error(" Liste noire d'IP injoignable, requête acceptée : {}",
                            error.getMessage());
                    return chain.filter(exchange);
                });
    }

    // recordViolation a ete DEPLACEE dans SecurityViolationRecorder.
    //
    // Elle vivait ici et n'etait appelee nulle part : la liste noire ne se
    // remplissait jamais, ce filtre lisait une cle qu'aucun code n'ecrivait. Il
    // refusait correctement une adresse deja inscrite, mais aucune ne l'etait.
    //
    // Les filtres qui DETECTENT les violations ne sont pas celui qui lit la
    // liste ; les faire dependre de celui-ci pour partager une methode aurait
    // produit un couplage circulaire. D'ou une classe dediee, que chacun
    // utilise.

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"Access denied\"}".getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
