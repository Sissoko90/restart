package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de compression d'images pour optimiser le stockage
 */
public interface ImageCompressionService {

    /**
     * Compresse une image en réduisant la qualité
     * 
     * @param imageContent Contenu de l'image
     * @param quality Qualité (0.0 à 1.0)
     * @return Image compressée
     */
    Mono<byte[]> compressImage(byte[] imageContent, float quality);

    /**
     * Compresse une image avec qualité par défaut (0.85)
     * 
     * @param imageContent Contenu de l'image
     * @return Image compressée
     */
    Mono<byte[]> compressImage(byte[] imageContent);

    /**
     * Redimensionne une image si elle dépasse les dimensions max
     * 
     * @param imageContent Contenu de l'image
     * @param maxWidth Largeur maximale
     * @param maxHeight Hauteur maximale
     * @return Image redimensionnée
     */
    Mono<byte[]> resizeIfNeeded(byte[] imageContent, int maxWidth, int maxHeight);
}
