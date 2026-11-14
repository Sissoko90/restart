package com.abdatytch.order_service.service;

import com.abdatytch.order_service.dto.response.CommandeResponse;
import com.abdatytch.order_service.enums.RoleDeTransition;
import com.abdatytch.order_service.enums.StatutCommande;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Les commandes. */
public interface ICommandeService {

    /**
     * Transforme le panier en commandes : UNE PAR BOUTIQUE.
     *
     * <p>Un panier de trois boutiques en produit trois. C'est la seule decoupe
     * qui tienne : chaque boutique expedie, est payee et subit un retour
     * separement.
     *
     * <p>Le stock est reserve chez market-service AVANT l'ecriture, en tout ou
     * rien. Si l'ecriture echoue ensuite, le stock est rendu par compensation.
     *
     * @throws com.abdatytch.order_service.exception.OrderException 409 si un
     *         article n'est plus disponible, 503 si le pays de l'acheteur n'a
     *         pas pu etre resolu, 400 si le panier est vide
     */
    Mono<List<CommandeResponse>> passerCommande(PorteeDeLAcheteur portee);

    /** Les commandes de l'acheteur, la plus recente d'abord. */
    Flux<CommandeResponse> mesCommandes(PorteeDeLAcheteur portee);

    /**
     * Une commande, si le demandeur a le droit de la voir.
     *
     * <p>Rend 404 et non 403 quand il ne l'a pas : un 403 confirmerait que la
     * commande existe, et une reference confirmee s'enumere.
     */
    Mono<CommandeResponse> detail(PorteeDeLAcheteur portee, UUID commandeId);

    /** Les commandes recues par une boutique. */
    Flux<CommandeResponse> commandesDeLaBoutique(UUID boutiqueId);

    /**
     * L'acheteur annule, tant que rien n'est encaisse.
     *
     * <p>Au-dela, c'est a Sankadi d'arbitrer : une commande payee engage la
     * boutique, qui a peut-etre deja prepare le colis.
     */
    Mono<CommandeResponse> annulerParLAcheteur(PorteeDeLAcheteur portee, UUID commandeId,
                                               String motif);

    /**
     * Une transition decidee par Sankadi ou par la boutique.
     *
     * <p>Le point d'entree UNIQUE des transitions pilotees de l'exterieur.
     * C'est par lui que payment-service constatera l'encaissement et
     * logistics-service la livraison, le jour ou ils existeront : la couture
     * est deja la, et c'est l'administration Sankadi qui s'en sert aujourd'hui.
     */
    Mono<CommandeResponse> fairePasserA(UUID commandeId, StatutCommande vers,
                                        String parQui, RoleDeTransition role, String motif);

    /** Les commandes en attente d'arbitrage ou d'action, pour l'administration. */
    Flux<CommandeResponse> parStatut(StatutCommande statut);
}
