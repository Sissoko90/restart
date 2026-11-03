package com.abdatytch.gateway_service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**

 * Limitation de débit distribuée, partagée entre les instances du Gateway.
 *
 * <p>Trois plafonds selon la sensibilité du chemin : très bas sur les endpoints
 * d'authentification anonymes (c'est là que se tentent les attaques par force
 * brute), intermédiaire sur l'administration, normal ailleurs.
 *
 * <p><b>Deux corrections importantes par rapport à la version précédente.</b>
 * <ol>
 * <li>L'IP vient de {@link ClientIpResolver} et non plus directement de
 * {@code X-Forwarded-For}. Cet en-tête étant écrit par le client, il
 * suffisait d'en changer la valeur à chaque appel pour obtenir un compteur
 * neuf : la limite ne limitait rien.</li>
 * <li>Une panne Redis bascule sur {@link LocalRateLimiter} au lieu de laisser
 * tout passer. Un plafond par instance vaut mieux que pas de plafond.</li>
 * </ol>
 */
public class RedisRateLimitWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitWebFilter.class);

    private static final int PUBLIC_AUTH_LIMIT = 5;
    private static final int ADMIN_LIMIT = 50;
    private static final int PROTECTED_LIMIT = 100;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String KEY_PREFIX = "rate_limit:";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ClientIpResolver clientIpResolver;
    private final LocalRateLimiter fallbackLimiter;

    /**
     * Signale les depassements, pour que la liste noire se remplisse.
     *
     * <p>Peut etre {@code null} : le limiteur doit continuer a fonctionner meme
     * sans enregistreur, et les constructeurs existants sont conserves pour les
     * tests.
     */
    private SecurityViolationRecorder violationRecorder;

    public RedisRateLimitWebFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                                   ClientIpResolver clientIpResolver) {
        this(redisTemplate, clientIpResolver, new LocalRateLimiter(WINDOW));
    }

    RedisRateLimitWebFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                            ClientIpResolver clientIpResolver,
                            LocalRateLimiter fallbackLimiter) {
        this.redisTemplate = redisTemplate;
        this.clientIpResolver = clientIpResolver;
        this.fallbackLimiter = fallbackLimiter;
    }

    /** Branche l'enregistreur de violations. */
    public RedisRateLimitWebFilter avecEnregistreur(SecurityViolationRecorder recorder) {
        this.violationRecorder = recorder;
        return this;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String clientIp = clientIpResolver.resolve(exchange);
        String path = exchange.getRequest().getPath().value();
        int limit = determineLimit(path);

        String redisKey = KEY_PREFIX + clientIp + ":" + normalizePath(path);

        return redisTemplate.opsForValue()
                .increment(redisKey)
                // Une reponse VIDE est traitee comme la panne qu'elle est.
                //
                // Sans cela, un Mono vide traversait tous les flatMap sans les
                // declencher : la requete n'etait ni transmise ni repondue, et
                // le client attendait jusqu'a son delai d'expiration.
                // onErrorResume ne s'appliquait pas — il n'y a pas d'erreur,
                // juste une absence de valeur. Le meme piege a ete trouve dans
                // IpBlacklistWebFilter.
                //
                // Le repli n'est PAS « laisser passer » : on bascule sur le
                // compteur local, en reutilisant le chemin degrade deja
                // correct. Laisser passer sans plafond exposerait l'essai de
                // mots de passe au moment precis ou la supervision est
                // degradee.
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Redis n'a rien renvoye pour le compteur de debit")))
                // L'expiration est posee quand le compteur vaut 1, et REVERIFIEE
                // ensuite. La poser uniquement a 1 laissait une faille : si cet
                // appel echouait, la cle survivait SANS expiration, les appels
                // suivants n'y repassaient jamais, et le compteur ne redescendait
                // plus. Une adresse franchissait le plafond puis restait
                // definitivement limitee a cause d'un incident Redis d'une
                // seconde.
                .flatMap(count -> count == 1
                        ? redisTemplate.expire(redisKey, WINDOW).thenReturn(count)
                        : assurerExpiration(redisKey).thenReturn(count))
                .flatMap(count -> {
                    if (count > limit) {
                        log.warn(" Plafond atteint pour {} sur {} ({}/{})",
                                clientIp, path, count, limit);
                        return signaler(clientIp, "rate-limit:" + normalizePath(path))
                                .then(tooManyRequests(exchange, limit));
                    }
                    exchange.getResponse().getHeaders().add("X-Rate-Limit-Limit", String.valueOf(limit));
                    exchange.getResponse().getHeaders()
                            .add("X-Rate-Limit-Remaining", String.valueOf(limit - count));
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> {
                    log.error(" Redis injoignable pour la limitation de débit, repli local : {}",
                            error.getMessage());
                    if (fallbackLimiter.tryConsume(clientIp + ":" + normalizePath(path), limit)) {
                        return chain.filter(exchange);
                    }
                    log.warn(" Plafond local atteint pour {} sur {}", clientIp, path);
                    return tooManyRequests(exchange, limit);
                });
    }

    /** Repose l'expiration si elle manque, ce qui auto-repare une cle orpheline. */
    private Mono<Boolean> assurerExpiration(String cle) {
        return redisTemplate.getExpire(cle)
                .flatMap(duree -> duree == null || duree.isNegative() || duree.isZero()
                        ? redisTemplate.expire(cle, WINDOW)
                        : Mono.just(true))
                .defaultIfEmpty(true);
    }

    private Mono<Void> signaler(String clientIp, String motif) {
        return violationRecorder == null
                ? Mono.empty()
                : violationRecorder.enregistrer(clientIp, motif);
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, int limit) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("X-Rate-Limit-Limit", String.valueOf(limit));
        exchange.getResponse().getHeaders().add("X-Rate-Limit-Remaining", "0");
        exchange.getResponse().getHeaders()
                .add("Retry-After", String.valueOf(WINDOW.getSeconds()));
        return exchange.getResponse().setComplete();
    }

    private int determineLimit(String path) {
        // Tout l'espace d'authentification anonyme est traité comme sensible :
        // énumérer les chemins un par un laissait sans protection les endpoints
        // ajoutés ensuite — inscription, OTP, réinitialisation de mot de passe.
        //
        // /api/v1/auth-sdk/ A DU ETRE AJOUTE, et son absence etait une faille
        // ouverte :
        //
        //   "/api/v1/auth/"  ne prefixe PAS  "/api/v1/auth-sdk/..."
        //
        // La barre oblique finale ferme le motif sur le segment « auth ». Toute
        // la surface SDK d'auth-service — connexion, inscription, OTP,
        // reinitialisation — tombait donc dans PROTECTED_LIMIT, le plafond le
        // plus LAXE (100/min au lieu de 5/min). Et elle vient d'etre rendue
        // publique, donc atteignable sans jeton.
        //
        // Le defaut est de la meme famille que ceux deja corriges ici : un
        // controle de chemin qui designe moins large que son intention. Le
        // commentaire ci-dessus annoncait « tout l'espace d'authentification
        // anonyme », et il en manquait la moitie.
        if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/auth-sdk/")) {
            return PUBLIC_AUTH_LIMIT;
        }
        if (path.startsWith("/api/v1/admin/") || path.startsWith("/actuator/")) {
            return ADMIN_LIMIT;
        }
        return PROTECTED_LIMIT;
    }

    /** Regroupe les identifiants de ressource : {@code /api/v1/users/123}  {@code /api/v1/users/*}. */
    private String normalizePath(String path) {
        String[] segments = path.split("/");
        if (segments.length > 4) {
            return String.join("/", segments[0], segments[1], segments[2], segments[3], "*");
        }
        return path;
    }
}
