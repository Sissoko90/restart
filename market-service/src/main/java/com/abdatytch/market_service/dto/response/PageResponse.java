package com.abdatytch.market_service.dto.response;

import java.util.List;

/**
 * Une page de resultats.
 *
 * <h2>{@code total} est rendu, et il coute une requete de plus</h2>
 *
 * Sans lui, l'ecran ne sait pas combien de pages il reste et ne peut afficher
 * qu'un bouton « suivant » — ce qui est acceptable pour un fil, pas pour un
 * catalogue ou l'on saute a la page 7. Le COUNT porte la meme clause de
 * visibilite que la liste, sans quoi il compterait des articles que l'appelant
 * n'a pas le droit de voir et annoncerait des pages vides.
 */
public record PageResponse<T>(List<T> elements, long total, int page, int taille) {

    public static <T> PageResponse<T> de(List<T> elements, long total, int page, int taille) {
        return new PageResponse<>(elements, total, page, taille);
    }
}
