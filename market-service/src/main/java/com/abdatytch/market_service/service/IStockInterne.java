package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.request.ReservationStockRequest;
import com.abdatytch.market_service.dto.request.RestitutionStockRequest;
import com.abdatytch.market_service.dto.response.LigneReserveeResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Le stock, vu par order-service.
 *
 * <p>Ce service est le SEUL point par lequel un autre module touche au stock.
 * Le stock vit chez market-service parce que c'est lui qui detient le
 * catalogue ; le dupliquer ailleurs ferait deux verites qui divergent au
 * premier incident.
 */
public interface IStockInterne {

    /**
     * Reserve le stock de plusieurs articles, en TOUT ou RIEN.
     *
     * @return l'instantane de chaque ligne reservee : nom, prix, couleur,
     *         taille, boutique. order-service s'en sert pour figer ses lignes
     *         de commande sans avoir a relire
     * @throws com.abdatytch.market_service.exception.MarketException si un
     *         article est invisible pour cet acheteur, inactif, ou en stock
     *         insuffisant. Rien n'est alors reserve
     */
    Mono<List<LigneReserveeResponse>> reserver(ReservationStockRequest demande);

    /**
     * Rend du stock : commande impayee annulee, ou retour recu.
     *
     * @return le nombre de lignes effectivement creditees. Un
     *         {@code Mono<Void>} ne distinguerait pas « tout rendu » de « rien
     *         rendu », et c'est precisement ce qu'il faut savoir ici
     */
    Mono<Integer> rendre(RestitutionStockRequest demande);
}
