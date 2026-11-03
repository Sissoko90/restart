package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.response.UserDocumentStatusResponse;
import com.abdatytch.media_service.enums.UserRole;
import reactor.core.publisher.Mono;

/**
 * Service pour gérer les exigences de documents basées sur le système dynamique
 */
public interface DocumentRequirementService {
    
    /**
     * Récupérer le statut de complétion des documents pour un utilisateur
     * Utilise le système dynamique DocumentTypeConfig et DocumentRoleRequirement
     */
    Mono<UserDocumentStatusResponse> getUserDocumentStatus(String userId, UserRole userRole);
    
    /**
     * Récupérer uniquement les documents manquants pour un utilisateur
     */
    Mono<UserDocumentStatusResponse> getMissingDocuments(String userId, UserRole userRole);
    
    /**
     * Initialiser les exigences de documents pour un utilisateur
     */
    Mono<Void> initializeUserDocumentRequirements(String userId, UserRole userRole);
}
