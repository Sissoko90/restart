package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.response.CategorieResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** L'arbre du catalogue, lu par tous et ecrit par la moderation. */
public interface ICategorieService {

    /** Les enfants d'un noeud ; {@code null} pour les racines. */
    Flux<CategorieResponse> enfantsDe(UUID parentId);

    Mono<CategorieResponse> creer(String nom, UUID parentId, Integer position);

    Mono<CategorieResponse> renommer(UUID id, String nom, Integer position, Boolean actif);

    /**
     * Supprime une categorie.
     *
     * <p>La base refuse si des enfants ou des produits s'y rattachent
     * ({@code ON DELETE RESTRICT}). Le refus est traduit en message lisible :
     * une violation de contrainte brute ne dirait pas quoi faire.
     */
    Mono<Void> supprimer(UUID id);
}
