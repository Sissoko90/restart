package com.abdatytch.market_service.web;

import com.abdatytch.market_service.dto.request.ReservationStockRequest;
import com.abdatytch.market_service.dto.request.RestitutionStockRequest;
import com.abdatytch.market_service.dto.response.LigneReserveeResponse;
import com.abdatytch.market_service.service.IStockInterne;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Le stock, pour order-service seulement.
 *
 * <h2>Pourquoi {@code /internal} et non la surface publique</h2>
 *
 * Aucune passerelle ne route {@code /internal/**} — c'est ce qui le rend
 * injoignable de l'exterieur. La chaine de securite de ce module y ajoute
 * {@code AutorisationInterne}, qui exige le secret partage, et
 * {@code GatewayAccessWebFilter} exempte ces chemins du controle de passerelle
 * APRES avoir verifie que l'appel vient du reseau prive.
 *
 * <p>Trois controles donc, et aucun ne suffit seul : le routage, le reseau, le
 * secret.
 *
 * <h2>Il n'y a pas d'endpoint de LECTURE du stock</h2>
 *
 * Et c'est delibere. order-service n'a aucune raison de connaitre le stock : il
 * le reserve ou il echoue. Un endpoint de lecture inviterait a « verifier
 * d'abord, reserver ensuite » — exactement la fenetre que la reservation
 * conditionnelle existe pour supprimer.
 *
 * <p>Le stock affichable fait partie de la fiche produit, que le catalogue sert
 * deja.
 */
@RestController
@RequestMapping("/internal/market/stock")
public class InternalStockController {

    private final IStockInterne stock;

    public InternalStockController(IStockInterne stock) {
        this.stock = stock;
    }

    /**
     * Reserve le stock d'un panier, en tout ou rien.
     *
     * <p>Rend l'instantane de chaque ligne : nom, prix, couleur, taille,
     * boutique. order-service fige ses lignes de commande avec ces valeurs,
     * sans relire — une relecture laisserait le prix changer entre la
     * reservation et l'enregistrement.
     *
     * <p>En cas d'indisponibilite : 409, et RIEN n'est reserve.
     */
    @PostMapping("/reserver")
    public Mono<ResponseEntity<List<LigneReserveeResponse>>> reserver(
            @Valid @RequestBody ReservationStockRequest demande) {
        return stock.reserver(demande).map(ResponseEntity::ok);
    }

    /**
     * Rend du stock : commande impayee annulee, ou retour recu.
     *
     * <p>Rend le nombre de lignes creditees, et non un corps vide : un 204 ne
     * distinguerait pas « tout rendu » de « rien rendu », et c'est exactement
     * ce que l'appelant doit savoir pour journaliser ce qu'il a recupere.
     */
    @PostMapping("/rendre")
    public Mono<ResponseEntity<Integer>> rendre(
            @Valid @RequestBody RestitutionStockRequest demande) {
        return stock.rendre(demande)
                .map(rendues -> ResponseEntity.status(HttpStatus.OK).body(rendues));
    }
}
