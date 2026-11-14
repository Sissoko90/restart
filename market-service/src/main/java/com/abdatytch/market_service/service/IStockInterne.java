package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.request.ReservationStockRequest;
import com.abdatytch.market_service.dto.request.RestitutionStockRequest;
import com.abdatytch.market_service.dto.response.LigneDecriteResponse;
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
     * Decrit des articles SANS rien reserver.
     *
     * <p>Le pendant en lecture de {@link #reserver}, pour qu'order-service
     * puisse afficher un panier. Reserver pour afficher retiendrait le stock a
     * chaque consultation.
     *
     * <p>La disponibilite rendue est un INSTANTANE indicatif : entre cette
     * lecture et le passage en caisse, un autre acheteur peut prendre le
     * dernier article. Seule la reservation conditionnelle garantit quelque
     * chose.
     *
     * <p>N'echoue PAS sur un article invisible ou epuise : il est rendu avec
     * {@code disponible = false} et un motif. Echouer sur la premiere ligne
     * indisponible empecherait d'afficher le reste du panier, et l'acheteur ne
     * saurait pas quoi retirer.
     */
    Mono<List<LigneDecriteResponse>> decrire(ReservationStockRequest demande);

    /**
     * Ce que ce service sait d'un compte : sa boutique, sa qualite, son pays.
     *
     * <p>Pour qu'order-service puisse servir « les commandes de MA boutique ».
     * La boutique d'un compte vit ici, avec son dossier de validation.
     */
    Mono<com.abdatytch.market_service.dto.response.PorteeDuCompteResponse> porteeDuCompte(
            String authUserId);

    /**
     * Rend du stock : commande impayee annulee, ou retour recu.
     *
     * @return le nombre de lignes effectivement creditees. Un
     *         {@code Mono<Void>} ne distinguerait pas « tout rendu » de « rien
     *         rendu », et c'est precisement ce qu'il faut savoir ici
     */
    Mono<Integer> rendre(RestitutionStockRequest demande);
}
