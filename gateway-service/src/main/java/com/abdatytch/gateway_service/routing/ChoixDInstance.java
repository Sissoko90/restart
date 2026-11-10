package com.abdatytch.gateway_service.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Choisit une instance d'un service, en repartissant la charge.
 *
 * <h2>Pourquoi cette logique a quitte {@code GatewayRoutesConfig}</h2>
 *
 * Elle y etait privee, et la passerelle a desormais DEUX chemins qui proxifient :
 * le {@code RouterFunction} pour l'HTTP, et {@link
 * com.abdatytch.gateway_service.websocket.RelaisWebSocket} pour la montee en
 * protocole. Recopier le choix d'instance dans le second aurait produit deux
 * politiques d'equilibrage pour une seule passerelle — et ce depot vient de
 * passer une journee sur les consequences d'un routage ecrit a deux endroits.
 *
 * <h2>{@code RoundRobinLoadBalancer}, et un repli journalise</h2>
 *
 * Chaque appel va a l'instance suivante. Le {@code ReactiveDiscoveryClient}
 * reste injecte comme <b>repli</b> pour le cas ou aucun equilibreur n'est
 * configure pour ce service — et ce repli journalise, pour qu'un retour
 * silencieux au comportement « premiere instance » ne passe pas inaperçu.
 *
 * <p>Le defaut d'origine : la resolution se faisait par
 * {@code discoveryClient.getInstances(nom).next()}, c'est-a-dire la PREMIERE
 * instance que Consul renvoie. Avec plusieurs instances d'un service, une seule
 * recevait tout le trafic ; les autres restaient inactives tout en etant saines
 * et enregistrees.
 *
 * <h2>Une absence d'instance est une ERREUR, pas un {@code Mono} vide</h2>
 *
 * Vide, la chaine se terminerait sans reponse et le client attendrait son
 * propre delai d'expiration — ce qui ne designe rien. L'erreur explicite
 * devient un 503 cote HTTP, et une poignee de main refusee cote WebSocket.
 */
@Component
public class ChoixDInstance {

    private static final Logger log = LoggerFactory.getLogger(ChoixDInstance.class);

    private final ReactiveDiscoveryClient decouverte;
    private final LoadBalancerClientFactory fabriqueDEquilibrage;

    public ChoixDInstance(ReactiveDiscoveryClient decouverte,
                          LoadBalancerClientFactory fabriqueDEquilibrage) {
        this.decouverte = decouverte;
        this.fabriqueDEquilibrage = fabriqueDEquilibrage;
    }

    public Mono<ServiceInstance> pour(String nomDuService) {
        var equilibreur = fabriqueDEquilibrage.getInstance(nomDuService);
        if (equilibreur == null) {
            log.warn("Aucun equilibreur pour {} : repli sur la premiere instance, "
                    + "le trafic n'est PAS reparti", nomDuService);
            return decouverte.getInstances(nomDuService).next()
                    .switchIfEmpty(Mono.error(aucuneInstance(nomDuService)));
        }
        return Mono.from(equilibreur.choose())
                .flatMap(reponse -> reponse.hasServer()
                        ? Mono.just((ServiceInstance) reponse.getServer())
                        : Mono.error(aucuneInstance(nomDuService)))
                .switchIfEmpty(Mono.error(aucuneInstance(nomDuService)));
    }

    private static IllegalStateException aucuneInstance(String nomDuService) {
        return new IllegalStateException("Aucune instance disponible pour " + nomDuService);
    }
}
