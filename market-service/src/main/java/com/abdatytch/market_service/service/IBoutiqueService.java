package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.request.CreationBoutiqueRequest;
import com.abdatytch.market_service.dto.request.PieceJustificativeRequest;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Le cycle de vie d'une boutique, du depot du dossier a sa vitrine. */
public interface IBoutiqueService {

    /**
     * Ouvre une boutique pour le compte courant.
     *
     * <p>Le pays est lu chez user-service, jamais dans la demande. Un compte
     * qui en a deja une recoit un refus explique plutot qu'une violation de
     * contrainte.
     */
    Mono<BoutiqueResponse> ouvrir(String authUserId, CreationBoutiqueRequest demande);

    /** La boutique du compte courant, quel que soit son etat. */
    Mono<BoutiqueResponse> mienne(String authUserId);

    /**
     * Met a jour la boutique du compte courant.
     *
     * <p>Modifier le RCCM ou le NIF d'une boutique approuvee la RENVOIE en
     * moderation : ce sont les pieces sur lesquelles la validation a porte.
     */
    Mono<BoutiqueResponse> modifier(String authUserId, CreationBoutiqueRequest demande);

    /** Rattache une piece au dossier. Renvoie la boutique en moderation. */
    Mono<Void> rattacherUnePiece(String authUserId, PieceJustificativeRequest demande);

    /** La vitrine d'une boutique approuvee, sans son dossier administratif. */
    Mono<BoutiqueResponse> vitrine(UUID boutiqueId);

    /** La file de moderation. */
    Flux<BoutiqueResponse> aModerer(int page, int taille);
}
