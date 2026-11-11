package com.abdatytch.market_service.config;

import com.abdatytch.commons.resilience.DisjoncteurSortant;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.market_service.exception.MarketException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Le disjoncteur des appels sortants.
 *
 * <p>Une instance par dependance, jamais une partagee : deux dependances qui
 * portent le meme nom tombent ensemble, donc la panne de user-service fermerait
 * le chemin de media-service.
 */
@Configuration
public class DisjoncteurSortantConfig {

    @Bean
    public IDisjoncteurSortant disjoncteurSortant(
            CircuitBreakerRegistry registre,
            @Value("${resilience.sortant.taux-echec-pourcent:50}") float tauxDEchec,
            @Value("${resilience.sortant.fenetre-appels:10}") int fenetre,
            @Value("${resilience.sortant.appels-minimum:5}") int appelsMinimum,
            @Value("${resilience.sortant.attente-ouvert-secondes:30}") long attenteOuvert) {
        return new DisjoncteurSortant(registre,
                DisjoncteurSortantConfig::estUneIndisponibilite,
                MarketException::dependanceIndisponible,
                tauxDEchec, fenetre, appelsMinimum, attenteOuvert);
    }

    /**
     * Ce que le disjoncteur compte comme panne.
     *
     * <p>Tout, SAUF ce que l'appelant a mal demande. Une liste blanche de types
     * d'exception rendrait le disjoncteur aveugle au premier oubli de
     * traduction — c'est le defaut qu'avait la configuration d'origine de ce
     * depot, dont la liste ne nommait que des types de RestTemplate sur une
     * pile WebClient.
     */
    static boolean estUneIndisponibilite(Throwable erreur) {
        if (erreur instanceof MarketException refus) {
            // Un 4xx dit que la DEMANDE etait mauvaise : la dependance a
            // parfaitement repondu. Le compter ouvrirait le disjoncteur sur une
            // suite de requetes invalides, donc couperait un service sain.
            return refus.getStatusCode().is5xxServerError();
        }
        return !(erreur instanceof IllegalArgumentException);
    }
}
