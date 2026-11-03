package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de watermarking pour traçabilité des documents
 */
public interface WatermarkService {

    /**
     * Ajoute un watermark invisible à une image
     * 
     * @param imageContent Contenu de l'image
     * @param userId ID utilisateur (inclus dans le watermark)
     * @param documentId ID du document
     * @return Image avec watermark
     */
    Mono<byte[]> addWatermark(byte[] imageContent, String userId, String documentId);

    /**
     * Vérifie si une image contient un watermark
     * 
     * @param imageContent Contenu de l'image
     * @return true si watermark détecté
     */
    Mono<Boolean> hasWatermark(byte[] imageContent);
}
