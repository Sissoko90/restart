package com.abdatytch.gateway_service.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Enregistre les violations de securite et met une adresse en liste noire.
 *
 * <h2>Pourquoi cette classe existe</h2>
 *
 * {@link IpBlacklistWebFilter} refuse les adresses inscrites dans une cle Redis,
 * et portait une methode {@code recordViolation} chargee de les y inscrire au
 * bout de dix violations. <b>Cette methode n'etait appelee nulle part.</b>
 *
 * <p>La liste noire ne se remplissait donc jamais : le filtre lisait une cle
 * qu'aucun code n'ecrivait. Il refusait correctement une adresse deja inscrite,
 * mais aucune ne l'etait — une protection presente et jamais declenchee. Meme
 * nature que les 33 {@code @PreAuthorize} inertes d'auth-service et que le
 * {@code isValidImage} inatteignable de user-service.
 *
 * <h2>Pourquoi la metrique est ICI et pas dans chaque filtre</h2>
 *
 * Les quatre filtres qui detectent — traversee de repertoire, XSS dans l'URL,
 * XSS dans le corps, depassement de debit — appellent deja
 * {@link #enregistrer}. Y poser un compteur chacun en ferait quatre copies a
 * maintenir d'accord, et le jour ou un cinquieme filtre apparait il serait
 * compte par Redis et pas par Prometheus. Un seul point de convergence, une
 * seule metrique, etiquetee par motif.
 *
 * <p>L'enregistrement est sorti du filtre de lecture pour une raison simple :
 * les filtres qui <b>detectent</b> les violations — traversee de repertoire,
 * XSS, depassement de debit — ne sont pas celui qui lit la liste. Les faire
 * dependre les uns des autres pour partager une methode aurait produit un
 * couplage circulaire. Ici, chacun signale ; un seul refuse.
 */
public class SecurityViolationRecorder {

    private static final Logger log = LoggerFactory.getLogger(SecurityViolationRecorder.class);

    // Les prefixes viennent du filtre QUI LIT. Les redeclarer ici serait le
    // moyen le plus sur de les voir diverger un jour, et alors l'enregistreur
    // ecrirait une cle que personne ne lit — exactement la panne qu'on corrige.
    static final String VIOLATION_KEY_PREFIX = IpBlacklistWebFilter.VIOLATION_KEY_PREFIX;
    static final String BLACKLIST_KEY_PREFIX = IpBlacklistWebFilter.BLACKLIST_KEY_PREFIX;

    /** Au-dela de ce nombre de violations dans la fenetre, l'adresse est inscrite. */
    static final int VIOLATIONS_BEFORE_BLACKLIST = 10;

    /**
     * Fenetre de comptage. Volontairement courte : une adresse partagee derriere
     * un NAT d'operateur peut produire quelques violations sans etre hostile.
     */
    static final Duration VIOLATION_WINDOW = Duration.ofMinutes(5);

    static final Duration BLACKLIST_TTL = Duration.ofDays(1);

    /** Violations signalees, par motif. Voir la javadoc de la classe. */
    static final String METRIQUE_VIOLATIONS = "gateway.security.violations";

    /** Mises en liste noire effectives — le signal rare, donc le plus parlant. */
    static final String METRIQUE_LISTE_NOIRE = "gateway.security.ip.blacklisted";

    private final ReactiveRedisTemplate<String, String> redis;
    private final MeterRegistry registry;

    public SecurityViolationRecorder(ReactiveRedisTemplate<String, String> redis,
                                     MeterRegistry registry) {
        this.redis = redis;
        this.registry = registry;
    }

    /**
     * Signale une violation. N'interrompt jamais le traitement de la requete.
     *
     * <p>Le refus de la requete en cours appartient au filtre qui detecte ; cette
     * methode ne fait que compter. Une erreur Redis est avalee : perdre un
     * compteur de violations est preferable a refuser du trafic legitime parce
     * que le cache est indisponible.
     */
    public Mono<Void> enregistrer(String clientIp, String motif) {
        if (clientIp == null || clientIp.isBlank()) {
            return Mono.empty();
        }

        // Le compteur est incremente AVANT Redis, et deliberement.
        //
        // L'enregistrement Redis avale ses erreurs — perdre un compteur de
        // violations vaut mieux que refuser du trafic legitime pendant une
        // panne de cache. Mais si la metrique dependait de ce meme Redis, une
        // panne rendrait les violations INVISIBLES a la supervision au moment
        // precis ou elles comptent le plus. La metrique ne doit donc rien
        // devoir au cache.
        Counter.builder(METRIQUE_VIOLATIONS)
                .tag("motif", motif == null ? "inconnu" : motif)
                .description("Violations de securite detectees par les filtres du Gateway")
                .register(registry)
                .increment();

        String cle = VIOLATION_KEY_PREFIX + clientIp;

        return redis.opsForValue().increment(cle)
                .flatMap(nombre -> assurerExpiration(cle, nombre)
                        .thenReturn(nombre))
                .flatMap(nombre -> {
                    log.warn("Violation de securite {}/{} depuis {} : {}",
                            nombre, VIOLATIONS_BEFORE_BLACKLIST, clientIp, motif);
                    if (nombre < VIOLATIONS_BEFORE_BLACKLIST) {
                        return Mono.<Void>empty();
                    }
                    log.error("Adresse {} mise en liste noire pour {} apres {} violations",
                            clientIp, BLACKLIST_TTL, nombre);
                    // Sans etiquette d'adresse : une IP est une donnee
                    // personnelle au sens du RGPD — AccountErasureService
                    // l'efface a ce titre — et une etiquette de cardinalite
                    // non bornee ferait exploser Prometheus. Le motif suffit
                    // a savoir ce qui declenche les mises en liste noire ;
                    // l'adresse reste dans le journal, qui est effaçable.
                    Counter.builder(METRIQUE_LISTE_NOIRE)
                            .tag("motif", motif == null ? "inconnu" : motif)
                            .description("Adresses mises en liste noire par le Gateway")
                            .register(registry)
                            .increment();
                    return redis.opsForValue()
                            .set(BLACKLIST_KEY_PREFIX + clientIp, motif, BLACKLIST_TTL)
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Violation non enregistree, Redis injoignable : {}", e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Garantit qu'une expiration existe, meme si elle a echoue au premier appel.
     *
     * <p>Poser l'expiration uniquement quand le compteur vaut 1 laisse une faille
     * : si cet appel echoue, la cle survit <b>sans expiration</b>. Les appels
     * suivants incrementent sans jamais repasser par 1, donc sans jamais reposer
     * d'expiration — et le compteur ne redescend plus. Une adresse franchirait le
     * seuil puis resterait definitivement signalee a cause d'un incident Redis
     * d'une seconde.
     */
    private Mono<Boolean> assurerExpiration(String cle, Long nombre) {
        if (nombre != null && nombre == 1L) {
            return redis.expire(cle, VIOLATION_WINDOW);
        }
        return redis.getExpire(cle)
                .flatMap(duree -> duree == null || duree.isNegative() || duree.isZero()
                        ? redis.expire(cle, VIOLATION_WINDOW)
                        : Mono.just(true))
                .defaultIfEmpty(true);
    }
}
