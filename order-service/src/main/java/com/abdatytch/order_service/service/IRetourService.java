package com.abdatytch.order_service.service;

import com.abdatytch.order_service.dto.request.DecisionDeRetourRequest;
import com.abdatytch.order_service.dto.request.DemandeDeRetourRequest;
import com.abdatytch.order_service.dto.response.RetourResponse;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Les retours, arbitres par Sankadi.
 *
 * <h2>L'acheteur demande, Sankadi decide, la boutique subit</h2>
 *
 * C'est tout l'interet de l'intermediaire : un retour ne depend pas du bon
 * vouloir du vendeur. La boutique n'a aucune methode ici — elle est informee,
 * pas consultee.
 *
 * <h2>Quatre etapes, et aucune n'est sautable</h2>
 *
 * Demande, arbitrage, RECEPTION, remboursement. La reception est une etape a
 * part entiere parce que « accepter un retour » et « avoir recu l'article » sont
 * deux faits differents : les confondre fait rembourser un colis qui n'est
 * jamais revenu, et c'est l'erreur la plus couteuse d'une place de marche parce
 * qu'elle est industrialisable.
 */
public interface IRetourService {

    /**
     * L'acheteur demande un retour.
     *
     * @throws com.abdatytch.order_service.exception.OrderException 404 si la
     *         commande n'est pas la sienne, 409 si son etat ne s'y prete pas ou
     *         si la fenetre est passee, 409 si un retour est deja ouvert
     */
    Mono<RetourResponse> demander(PorteeDeLAcheteur portee, UUID commandeId,
                                  DemandeDeRetourRequest demande);

    /** Les retours de l'acheteur sur une de ses commandes. */
    Flux<RetourResponse> mesRetours(PorteeDeLAcheteur portee, UUID commandeId);

    /** Sankadi accepte ou refuse. Un refus exige un motif. */
    Mono<RetourResponse> arbitrer(UUID retourId, DecisionDeRetourRequest decision,
                                  String decidePar);

    /**
     * Sankadi constate que l'article est revenu, et REND LE STOCK.
     *
     * <p>C'est cette etape qui recredite la boutique : rendre le stock a
     * l'acceptation l'aurait recredite d'un article qui n'est jamais reparti.
     */
    Mono<RetourResponse> marquerRecu(UUID retourId, String parQui);

    /**
     * Sankadi constate le remboursement.
     *
     * <p>Aucun argent ne circule ici : le montant et la date enregistrent ce
     * que Sankadi DOIT a l'acheteur. payment-service le versera, et c'est cet
     * etat qu'il attendra.
     */
    Mono<RetourResponse> rembourser(UUID retourId, String parQui);

    /** La file d'arbitrage de Sankadi : les demandes non tranchees. */
    Flux<RetourResponse> enAttenteDArbitrage();
}
