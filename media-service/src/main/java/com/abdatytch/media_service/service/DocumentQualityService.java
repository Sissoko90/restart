package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de validation de qualité des documents
 * Vérifie que les documents sont lisibles et de qualité suffisante
 * Utilise des heuristiques pour détecter:
 * - Documents flous ou illisibles
 * - Résolution insuffisante
 * - Contraste trop faible
 * - Documents tronqués ou incomplets
 */
public interface DocumentQualityService {
    
    /**
     * Analyse la qualité d'un document image
     * @return Mono avec score de qualité (0-100) et lisibilité
     */
    Mono<QualityAnalysisResult> analyzeImageQuality(byte[] imageData, String mimeType);
    
    /**
     * Analyse la qualité d'un document PDF
     * @return Mono avec score de qualité et nombre de pages détectées
     */
    Mono<QualityAnalysisResult> analyzePdfQuality(byte[] pdfData);
    
    /**
     * Résultat de l'analyse de qualité
     */
    record QualityAnalysisResult(
        double qualityScore, // 0-100
        boolean isReadable,
        String rejectionReason, // Si qualityScore < seuil
        int width,
        int height,
        int pageCount // Pour PDFs
    ) {}
}
