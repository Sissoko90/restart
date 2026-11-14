package com.abdatytch.order_service.service;

import com.abdatytch.order_service.dto.request.AjoutAuPanierRequest;
import com.abdatytch.order_service.dto.response.PanierResponse;
import com.abdatytch.order_service.security.PorteeDeLAcheteur;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Le panier de l'acheteur courant. */
public interface IPanierService {

    /**
     * Le panier, enrichi des prix et de la disponibilite du jour.
     *
     * <p>Rend un panier VIDE et non une erreur si l'acheteur n'en a pas :
     * demander son panier quand on n'en a pas n'est pas une faute, et un 404
     * obligerait chaque client a traiter ce cas a part.
     */
    Mono<PanierResponse> afficher(PorteeDeLAcheteur portee);

    /** Ajoute un article, ou augmente sa quantite si la variante y est deja. */
    Mono<PanierResponse> ajouter(PorteeDeLAcheteur portee, AjoutAuPanierRequest demande);

    /**
     * Fixe la quantite d'une ligne. Une quantite nulle retire la ligne.
     *
     * <p>Fixer plutot qu'incrementer : un client qui rejoue sa requete apres un
     * delai reseau doublerait la quantite avec un incrementeur, et c'est
     * precisement ce qui arrive sur un reseau mobile.
     */
    Mono<PanierResponse> fixerQuantite(PorteeDeLAcheteur portee, UUID ligneId, int quantite);

    /** Vide le panier sans le fermer : l'acheteur continue avec le meme panier. */
    Mono<PanierResponse> vider(PorteeDeLAcheteur portee);
}
