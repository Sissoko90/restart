package com.abdatytch.order_service.web;

import com.abdatytch.order_service.constants.CheminsDesCommandes;
import com.abdatytch.order_service.dto.request.DecisionDeRetourRequest;
import com.abdatytch.order_service.dto.response.RetourResponse;
import com.abdatytch.order_service.security.ResolveurDePortee;
import com.abdatytch.order_service.service.IRetourService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * L'ARBITRAGE DES RETOURS, par Sankadi.
 *
 * <h2>Un controleur distinct de celui des commandes, et pourquoi</h2>
 *
 * La premiere version greffait ces routes sur {@code AdminOrderController} avec
 * un prefixe {@code "/../retours"} pour remonter d'un cran. Un {@code ..} dans
 * une route n'a aucun sens, et c'est exactement le segment que le filtre
 * anti-traversee de la passerelle rejette.
 *
 * <p>La meme faute avait ete faite une heure plus tot chez market-service.
 * Deux responsabilites, deux controleurs, deux prefixes propres.
 *
 * <h2>La boutique n'a aucune route ici, et c'est le principe</h2>
 *
 * Sankadi arbitre. Un retour accepte ne se refuse pas : la boutique en est
 * informee, pas consultee. C'est tout l'interet de l'intermediaire, et le
 * rendre possible aurait suffi a le vider de son sens.
 *
 * <h2>Quatre etapes, dans l'ordre, et aucune n'est sautable</h2>
 *
 * Decision, reception, remboursement. La reception est une etape a part entiere
 * parce qu'« accepter un retour » et « avoir recu l'article » sont deux faits
 * differents : les confondre fait rembourser un colis qui n'est jamais revenu,
 * et c'est industrialisable.
 */
@RestController
@RequestMapping(CheminsDesCommandes.ADMIN_RETOURS)
public class AdminRetourController {

    private final IRetourService retours;
    private final ResolveurDePortee portees;

    public AdminRetourController(IRetourService retours, ResolveurDePortee portees) {
        this.retours = retours;
        this.portees = portees;
    }

    /**
     * La file d'arbitrage : les demandes non tranchees.
     *
     * <p>C'est la file dont l'alerte {@code FileDArbitrageFigee} surveille
     * l'age. Une file qui monte sans redescendre signifie que plus personne
     * n'arbitre, et rien d'autre ne le signale : le service repond
     * normalement, les acheteurs demandent, et leurs dossiers ne bougent pas.
     */
    @GetMapping
    public Flux<RetourResponse> fileDArbitrage() {
        return retours.enAttenteDArbitrage();
    }

    /** Sankadi accepte ou refuse. Un refus exige un motif. */
    @PostMapping("/{retourId}/decision")
    public Mono<RetourResponse> arbitrer(@PathVariable UUID retourId,
                                         @Valid @RequestBody DecisionDeRetourRequest decision) {
        return portees.courante()
                .flatMap(portee -> retours.arbitrer(retourId, decision, portee.authUserId()));
    }

    /**
     * Sankadi constate que l'article est revenu. LE STOCK EST RENDU ICI.
     *
     * <p>Et non a l'acceptation : a l'acceptation, l'article n'etait pas encore
     * reparti, et recrediter la boutique l'aurait fait vendre deux fois le meme
     * exemplaire.
     */
    @PostMapping("/{retourId}/recu")
    public Mono<RetourResponse> constaterLaReception(@PathVariable UUID retourId) {
        return portees.courante()
                .flatMap(portee -> retours.marquerRecu(retourId, portee.authUserId()));
    }

    /**
     * Sankadi constate le remboursement.
     *
     * <p>Aucun argent ne circule ici : cet appel enregistre ce que Sankadi DOIT
     * a l'acheteur. payment-service le versera, et c'est cet etat qu'il
     * attendra.
     */
    @PostMapping("/{retourId}/rembourse")
    public Mono<RetourResponse> constaterLeRemboursement(@PathVariable UUID retourId) {
        return portees.courante()
                .flatMap(portee -> retours.rembourser(retourId, portee.authUserId()));
    }
}
