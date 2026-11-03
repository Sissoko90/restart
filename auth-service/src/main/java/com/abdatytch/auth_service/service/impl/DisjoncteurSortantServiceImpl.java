package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.service.IDisjoncteurSortant;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Disjoncteurs des appels sortants, en un seul detenteur.
 *
 * <p>Voir {@link IDisjoncteurSortant} pour le raisonnement.
 */
@Service
@Slf4j
public class DisjoncteurSortantServiceImpl implements IDisjoncteurSortant {

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
    static final String CONFIGURATION = "sortant-tiers";

    private final CircuitBreakerRegistry registre;

    public DisjoncteurSortantServiceImpl(
            CircuitBreakerRegistry registre,
            @Value("${resilience.sortant.taux-echec-pourcent:50}") float tauxDEchec,
            @Value("${resilience.sortant.fenetre-appels:10}") int fenetre,
            @Value("${resilience.sortant.appels-minimum:5}") int appelsMinimum,
            @Value("${resilience.sortant.attente-ouvert-secondes:30}") long attenteOuvert) {
        this.registre = registre;

        // La regle « qu'est-ce qu'un echec » est posee ICI, en code, et non en
        // YAML.
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
        // en code dit la regle une fois, lisiblement : seule une
        // indisponibilite declaree compte.
        CircuitBreakerConfig configuration = CircuitBreakerConfig.custom()
                .failureRateThreshold(tauxDEchec)
                .slidingWindowSize(fenetre)
                .minimumNumberOfCalls(appelsMinimum)
                .waitDurationInOpenState(Duration.ofSeconds(attenteOuvert))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(DisjoncteurSortantServiceImpl::estUneIndisponibilite)
                .build();

        registre.addConfiguration(CONFIGURATION, configuration);
    }

    /**
     * Seule une indisponibilite de la dependance compte comme un echec.
     *
     * <p>Un refus metier — identifiants invalides, numero mal forme, credit
     * epuise — signale que la dependance <b>fonctionne</b> : elle a repondu.
     * Le compter ouvrirait le disjoncteur sur du trafic parfaitement sain, et
     * sur un chemin d'authentification cela transformerait une rafale de
     * mauvais mots de passe en panne generale.
     */
    static boolean estUneIndisponibilite(Throwable erreur) {
        if (erreur instanceof AuthException auth) {
            // Regle sur le NOM du code, et non sur une constante precise.
            //
            // La version precedente ne reconnaissait que SERVICE_UNAVAILABLE :
            // elle aurait laisse passer SMS_PROVIDER_UNAVAILABLE, donc le
            // disjoncteur du fournisseur SMS ne se serait jamais ouvert — la
            // meme panne silencieuse que celle qu'on vient de corriger, sous
            // une autre forme.
            //
            // C'est la MEME convention que GlobalExceptionHandler, qui classe
            // tout code nomme UNAVAILABLE en 503 : une seule regle, deux
            // endroits qui s'y conforment.
            return auth.getErrorCode() != null
                    && auth.getErrorCode().name().contains("UNAVAILABLE");
        }
        // Ce qui n'a pas ete traduit par l'appelant : panne de transport,
        // delai, erreur inattendue. A compter, faute de quoi un oubli de
        // traduction rendrait le disjoncteur aveugle.
        return !(erreur instanceof IllegalArgumentException);
    }

    @Override
    public void declarer(String nom) {
        CircuitBreaker disjoncteur = registre.circuitBreaker(nom, CONFIGURATION);
        log.info("Disjoncteur {} declare au demarrage, etat {}",
                nom, disjoncteur.getState());
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
                // GlobalExceptionHandler la classerait en 500 — accusant ce
                // service alors qu'il vient de se proteger correctement.
                .onErrorMap(CallNotPermittedException.class, e -> {
                    log.warn("Disjoncteur {} OUVERT : appel refuse sans tentative", nom);
                    return new AuthException(ErrorCode.SERVICE_UNAVAILABLE);
                });
    }
}
