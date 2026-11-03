package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de génération de thumbnails pour aperçu rapide
 */
public interface ThumbnailService {

    /**
     * Génère un thumbnail d'une image
     * 
     * @param imageContent Contenu de l'image originale
     * @param width Largeur du thumbnail
     * @param height Hauteur du thumbnail
     * @return Thumbnail généré
     */
    Mono<byte[]> generateThumbnail(byte[] imageContent, int width, int height);

    /**
     * Génère un thumbnail avec dimensions par défaut (300x300)
     * 
     * @param imageContent Contenu de l'image
     * @return Thumbnail généré
     */
    Mono<byte[]> generateThumbnail(byte[] imageContent);
}
