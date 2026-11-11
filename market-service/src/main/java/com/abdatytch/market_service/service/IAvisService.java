package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.request.AvisRequest;
import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Les avis clients : depot, lecture, et le sien. */
public interface IAvisService {

    /**
     * Depose ou REMPLACE son avis.
     *
     * <p>La base n'en accepte qu'un par personne et par produit. Un second
     * envoi modifie le premier plutot que d'echouer sur une contrainte dont le
     * message ne dirait rien a personne.
     */
    Mono<AvisResponse> deposer(PorteeDuCatalogue portee, UUID produitId, AvisRequest demande);

    /** Les avis PUBLIES d'un produit visible. */
    Flux<AvisResponse> publies(PorteeDuCatalogue portee, UUID produitId, int page, int taille);

    /** Son propre avis, meme en attente ou rejete. */
    Mono<AvisResponse> mien(String authUserId, UUID produitId);
}
