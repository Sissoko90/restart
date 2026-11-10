package com.abdatytch.gateway_service.websocket;

import com.abdatytch.gateway_service.routing.ChoixDInstance;
import com.abdatytch.gateway_service.security.ClientIpResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declare le relais WebSocket comme filtre de la chaine EXTERNE.
 *
 * <h2>Un {@code @Bean}, et surtout pas les deux</h2>
 *
 * WebFlux collecte automatiquement tous les beans de type {@code WebFilter} et
 * les place dans la chaine HTTP. Un seul enregistrement suffit donc.
 *
 * <p>{@link RelaisWebSocket} n'est deliberement PAS annote
 * {@code @Component} et n'est PAS ajoute par {@code addFilterAt} dans
 * {@code SecurityConfig} : un filtre present dans la chaine externe ET dans
 * celle de Spring Security s'execute <b>deux fois par requete</b>. Ce depot
 * l'a paye sur sept filtres, avec pour consequence un plafond de debit qui
 * valait la moitie de l'annonce et chaque evenement de securite journalise en
 * double.
 *
 * <p>Pour un relais, la double execution serait pire qu'un compteur faux : la
 * premiere passe consommerait la poignee de main, et la seconde trouverait un
 * echange deja engage.
 */
@Configuration
public class ConfigurationDuRelais {

    @Bean
    public RelaisWebSocket relaisWebSocket(ChoixDInstance choixDInstance,
                                           ClientIpResolver resolveurDIp) {
        return new RelaisWebSocket(choixDInstance, resolveurDIp);
    }

    /**
     * Le lecteur du jeton annonce dans le sous-protocole.
     *
     * <p>Il permet a un NAVIGATEUR de s'abonner au flux : l'API
     * {@code WebSocket} des navigateurs ne permet pas de poser un en-tete
     * {@code Authorization}, mais elle permet d'annoncer des sous-protocoles.
     *
     * <p>Meme regle que le relais : un seul enregistrement, par
     * {@code @Bean}. Un filtre present dans la chaine externe ET dans celle de
     * Spring Security s'executerait deux fois — et celui-ci muterait alors une
     * requete deja mutee, ce qui est sans effet mais fait deux lectures de
     * jeton par poignee de main.
     */
    @Bean
    public JetonDuSousProtocole jetonDuSousProtocole() {
        return new JetonDuSousProtocole();
    }
}
