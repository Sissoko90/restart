package com.abdatytch.market_service.web;

import com.abdatytch.market_service.dto.response.PorteeDuCompteResponse;
import com.abdatytch.market_service.service.IStockInterne;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Ce que market-service sait d'un compte, pour un autre service.
 *
 * <h2>Un controleur distinct de celui du stock, et pourquoi</h2>
 *
 * Ce n'est pas du stock. La premiere version l'avait greffe sur
 * {@code InternalStockController} avec un chemin {@code "/../comptes/..."} pour
 * remonter d'un cran — ce qui n'a aucun sens dans une route, et qui est
 * precisement le genre de segment que le filtre anti-traversee de la passerelle
 * rejette.
 *
 * <p>Deux responsabilites, deux controleurs, deux prefixes propres.
 *
 * <h2>Pourquoi un GET ici</h2>
 *
 * Un seul identifiant, qui tient dans l'URL, et une lecture sans effet de bord
 * donc rejouable. Les endpoints de stock sont des POST parce qu'ils portent une
 * liste d'articles et, pour l'un des deux, parce qu'il ECRIT.
 */
@RestController
@RequestMapping("/internal/market/comptes")
public class InternalCompteController {

    private final IStockInterne service;

    public InternalCompteController(IStockInterne service) {
        this.service = service;
    }

    /**
     * La boutique d'un compte, sa qualite de professionnel, son pays.
     *
     * <p>Rend une portee a boutique NULLE pour un compte sans boutique, ce qui
     * est le cas le plus courant — et non un 404 : l'absence de boutique est
     * une reponse, pas une erreur.
     */
    @GetMapping("/{authUserId}/portee")
    public Mono<ResponseEntity<PorteeDuCompteResponse>> portee(
            @PathVariable String authUserId) {
        return service.porteeDuCompte(authUserId).map(ResponseEntity::ok);
    }
}
