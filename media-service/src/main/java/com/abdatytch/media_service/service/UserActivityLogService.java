package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Service de logging complet de l'activité utilisateur
 * Enregistre TOUS les événements avec contexte complet (IP, géolocalisation, device, browser)
 */
public interface UserActivityLogService {
    
    /**
     * Logger un événement d'upload de document avec contexte complet
     */
    Mono<Void> logDocumentUpload(
        String userId,
        String documentId,
        String documentTypeCode,
        String fileName,
        Long fileSize,
        String ipAddress,
        String userAgent,
        String requestId,
        Map<String, Object> additionalContext
    );
    
    /**
     * Logger un événement de téléchargement
     */
    Mono<Void> logDocumentDownload(
        String userId,
        String documentId,
        String ipAddress,
        String userAgent,
        String requestId
    );
    
    /**
     * Logger un événement de suppression
     */
    Mono<Void> logDocumentDeletion(
        String userId,
        String documentId,
        String reason,
        String ipAddress,
        String userAgent,
        String requestId
    );
    
    /**
     * Logger une approbation/rejet par admin
     */
    Mono<Void> logDocumentReview(
        String adminId,
        String documentId,
        String userId,
        String action,
        String reason,
        String ipAddress,
        String userAgent,
        String requestId
    );
    
    /**
     * Logger un incident de sécurité
     */
    Mono<Void> logSecurityIncident(
        String userId,
        String documentId,
        String threatType,
        String severity,
        String description,
        String ipAddress,
        String userAgent,
        String requestId
    );
    
    /**
     * Logger une tentative d'accès non autorisé
     */
    Mono<Void> logUnauthorizedAccess(
        String userId,
        String documentId,
        String attemptedAction,
        String ipAddress,
        String userAgent,
        String requestId
    );
    
    /**
     * Logger un échec de validation
     */
    Mono<Void> logValidationFailure(
        String userId,
        String documentTypeCode,
        String failureReason,
        String ipAddress,
        String userAgent,
        String requestId
    );
}
