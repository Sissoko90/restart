package com.abdatytch.commons.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Disjoncteurs des appels sortants, en un seul detenteur.
 *
 * <p>Voir {@link IDisjoncteurSortant} pour le raisonnement. Cette classe porte
 * le <b>mecanisme</b> ; les deux regles propres a chaque module lui sont
 * injectees.
 *
 * <h2>Pourquoi deux strategies injectees</h2>
 *
 * L'implementation d'origine, dans auth-service, connaissait
 * {@code AuthException} et {@code ErrorCode} — le vocabulaire d'erreurs d'un
 * seul module. La porter telle quelle ici aurait oblige platform-commons a
 * connaitre les exceptions de tous les services, ou force notification-service
 * a adopter celles de l'authentification.
 *
 * <p>Les deux regles qui varient sont donc des parametres :
 *
 * <ul>
 *   <li>{@code estUneIndisponibilite} : quelles erreurs comptent comme une
 *       panne de la dependance. Chaque module parle de SES types ;</li>
 *   <li>{@code refusQuandOuvert} : quoi lever quand le disjoncteur refuse.
 *       auth-service y remet son {@code AuthException(SERVICE_UNAVAILABLE)}, ce
 *       qui preserve son comportement a l'identique — y compris sur les chemins
 *       OAuth2, ou un changement de type d'exception aurait transforme un 503
 *       en 500.</li>
 * </ul>
 *
 * <p>Tout le reste — la configuration, le passage par le registre, l'operateur
 * Reactor, la traduction du refus — est partage, et c'etait le but.
 */
public class DisjoncteurSortant implements IDisjoncteurSortant {

    private static final Logger log = LoggerFactory.getLogger(DisjoncteurSortant.class);

    /**
     * Nom de la configuration partagee, enregistree dans le registre Spring.
     *
     * <p>Les instances sont creees <b>par le registre</b> et non par
     * {@code CircuitBreaker.of(...)}. La difference est l'observabilite :
     * {@code resilience4j-micrometer} s'abonne aux evenements du registre, donc
     * une instance creee hors de lui n'apparait ni dans Prometheus ni dans
     * {@code /actuator/circuitbreakers}. Un disjoncteur qu'on ne voit pas
     * s'ouvrir ne vaut pas grand-chose.
     */
    public static final String CONFIGURATION = "sortant-tiers";

    private final CircuitBreakerRegistry registre;
    private final Function<String, Throwable> refusQuandOuvert;

    /**
     * @param registre              registre Spring de resilience4j
     * @param estUneIndisponibilite quelles erreurs comptent comme une panne de
     *                              la dependance. Voir la javadoc de classe
     * @param refusQuandOuvert      l'erreur a lever quand le disjoncteur est
     *                              ouvert, construite depuis le nom de
     *                              l'instance
     * @param tauxDEchec            pourcentage d'echecs qui ouvre le disjoncteur
     * @param fenetre               nombre d'appels observes
     * @param appelsMinimum         en dessous, le taux n'est pas evalue — sans
     *                              quoi un seul echec sur un seul appel
     *                              ouvrirait a 100 %
     * @param attenteOuvert         secondes avant de retenter un appel
     */
    public DisjoncteurSortant(CircuitBreakerRegistry registre,
                              Predicate<Throwable> estUneIndisponibilite,
                              Function<String, Throwable> refusQuandOuvert,
                              float tauxDEchec,
                              int fenetre,
                              int appelsMinimum,
                              long attenteOuvert) {
        this.registre = registre;
        this.refusQuandOuvert = refusQuandOuvert;

        // La regle « qu'est-ce qu'un echec » est posee en CODE, et non en YAML.
        //
        // La configuration d'origine la declarait par une liste de noms de
        // classes d'exceptions. Cette liste contenait des types de
        // RestTemplate — ResourceAccessException, HttpServerErrorException —
        // sur une pile qui utilise WebClient. Resultat : avec
        // `recordExceptions` renseigne, tout ce qui n'y figure pas compte comme
        // un SUCCES, donc aucun echec de transport n'etait compte.
        //
        // Une liste de noms de classes en YAML ne se verifie pas a la
        // compilation et ne suit pas un changement de client HTTP. Un predicat
        // dit la regle une fois, lisiblement.
        CircuitBreakerConfig configuration = CircuitBreakerConfig.custom()
                .failureRateThreshold(tauxDEchec)
                .slidingWindowSize(fenetre)
                .minimumNumberOfCalls(appelsMinimum)
                .waitDurationInOpenState(Duration.ofSeconds(attenteOuvert))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(estUneIndisponibilite::test)
                .build();

        registre.addConfiguration(CONFIGURATION, configuration);
    }

    @Override
    public void declarer(String nom) {
        CircuitBreaker disjoncteur = registre.circuitBreaker(nom, CONFIGURATION);
        log.info("Disjoncteur {} declare au demarrage, etat {}", nom, disjoncteur.getState());
    }

    @Override
    public <T> Mono<T> proteger(String nom, Mono<T> appel) {
        // `circuitBreaker(nom, config)` est idempotent : il rend l'instance
        // deja creee par declarer(), ou la cree si personne ne l'a declaree.
        CircuitBreaker disjoncteur = registre.circuitBreaker(nom, CONFIGURATION);

        return appel
                .transformDeferred(CircuitBreakerOperator.of(disjoncteur))
                // Disjoncteur ouvert : l'appel n'a pas eu lieu. Sans cette
                // traduction, CallNotPermittedException remonterait brute et le
                // gestionnaire d'exceptions du module la classerait en 500 —
                // accusant ce service alors qu'il vient de se proteger
                // correctement.
                .onErrorMap(CallNotPermittedException.class, e -> {
                    log.warn("Disjoncteur {} OUVERT : appel refuse sans tentative", nom);
                    return refusQuandOuvert.apply(nom);
                });
    }
}
