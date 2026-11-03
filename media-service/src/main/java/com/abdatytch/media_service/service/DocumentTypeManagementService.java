package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.request.CreateDocumentTypeRequest;
import com.abdatytch.media_service.dto.response.DocumentRoleAssignmentResponse;
import com.abdatytch.media_service.dto.response.DocumentTypeResponse;
import com.abdatytch.media_service.enums.DocumentCategory;
import com.abdatytch.media_service.enums.UserRole;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service de gestion des types de documents et leurs affectations aux rôles
 */
public interface DocumentTypeManagementService {
    
    /**
     * Créer un nouveau type de document
     */
    Mono<DocumentTypeResponse> createDocumentType(CreateDocumentTypeRequest request, String adminId);
    
    /**
     * Mettre à jour un type de document
     */
    Mono<DocumentTypeResponse> updateDocumentType(String id, CreateDocumentTypeRequest request, String adminId);
    
    /**
     * Supprimer un type de document (soft delete)
     */
    Mono<Void> deleteDocumentType(String id, String adminId);
    
    /**
     * Récupérer un type de document par ID
     */
    Mono<DocumentTypeResponse> getDocumentTypeById(String id);
    
    /**
     * Récupérer un type de document par code
     */
    Mono<DocumentTypeResponse> getDocumentTypeByCode(String code);
    
    /**
     * Lister tous les types de documents
     */
    Flux<DocumentTypeResponse> getAllDocumentTypes();
    
    /**
     * Lister les types de documents actifs
     */
    Flux<DocumentTypeResponse> getActiveDocumentTypes();
    
    /**
     * Lister les types de documents par catégorie
     */
    Flux<DocumentTypeResponse> getDocumentTypesByCategory(DocumentCategory category);
    
    /**
     * Assigner un type de document à un rôle
     */
    Mono<DocumentRoleAssignmentResponse> assignDocumentToRole(
        String documentTypeId,
        UserRole role,
        Boolean mandatory,
        String adminId
    );
    
    /**
     * Mettre à jour l'affectation d'un document à un rôle
     */
    Mono<DocumentRoleAssignmentResponse> updateRoleAssignment(
        String documentTypeCode, 
        UserRole userRole, 
        Boolean mandatory,
        Integer displayOrder,
        String adminId
    );
    
    /**
     * Retirer un document d'un rôle
     */
    Mono<Void> removeDocumentFromRole(String documentTypeCode, UserRole userRole, String adminId);
    
    /**
     * Récupérer tous les documents assignés à un rôle
     */
    Flux<DocumentRoleAssignmentResponse> getDocumentsByRole(UserRole userRole);
    
    /**
     * Récupérer les documents obligatoires pour un rôle
     */
    Flux<DocumentRoleAssignmentResponse> getMandatoryDocumentsByRole(UserRole userRole);
    
    /**
     * Récupérer les documents optionnels pour un rôle
     */
    Flux<DocumentRoleAssignmentResponse> getOptionalDocumentsByRole(UserRole userRole);
    
    /**
     * Récupérer tous les rôles assignés à un type de document
     */
    Flux<DocumentRoleAssignmentResponse> getRolesByDocumentType(String documentTypeCode);
}
