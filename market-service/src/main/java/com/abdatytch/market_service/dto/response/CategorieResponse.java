package com.abdatytch.market_service.dto.response;

import java.util.UUID;

/** Un noeud de l'arbre du catalogue. */
public record CategorieResponse(
        UUID id, UUID parentId, String nom, String slug,
        Integer position, Boolean actif) {
}
