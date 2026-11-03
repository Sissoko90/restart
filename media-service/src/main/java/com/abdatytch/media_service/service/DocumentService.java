package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.dto.response.DocumentDetailResponse;
import com.abdatytch.media_service.dto.response.DocumentListResponse;
import com.abdatytch.media_service.dto.response.DocumentUploadResponse;
import com.abdatytch.media_service.dto.response.UserDocumentStatusResponse;
import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.UserRole;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service principal de gestion des documents utilisateur
 * Intègre validation par rôle, sécurité et qualité
 */
public interface DocumentService {
    
    /**
     * Upload d'un document avec validation complète.
     *
     * @param type le type déjà <b>résolu</b> contre le catalogue et le rôle de
     *             l'appelant. Prenait une valeur d'énumération compilée, ce qui
     *             rendait inutilisable tout type créé par l'API
     *             d'administration ; la résolution appartient désormais à
     *             {@link DocumentTypeValidationService#resoudre}
     */
    Mono<DocumentUploadResponse> uploadDocument(
        String userId,
        UserRole userRole,
        TypeDeDocumentResolu type,
        FilePart filePart,
        DocumentUploadRequest request,
        String ipAddress,
        String userAgent
    );
    
    /**
     * Téléchargement d'un document
     */
    Mono<Flux<DataBuffer>> downloadDocument(String documentId, String userId);
    
    /**
     * Récupérer les détails d'un document
     */
    Mono<DocumentDetailResponse> getDocumentDetails(String documentId, String userId);
    
    /**
     * Lister tous les documents d'un utilisateur
     */
    Flux<DocumentListResponse> getUserDocuments(String userId);
    
    /**
     * Lister les documents par statut
     */
    Flux<DocumentListResponse> getUserDocumentsByStatus(String userId, DocumentStatus status);
    
    /**
     * Supprimer un document
     */
    Mono<Void> deleteDocument(String documentId, String userId);
    
    /**
     * Approuver un document (admin)
     */
    Mono<Void> approveDocument(String documentId, String adminId);
    
    /**
     * Rejeter un document (admin)
     */
    Mono<Void> rejectDocument(String documentId, String adminId, String reason);
    
    /**
     * Récupérer le statut de complétion des documents pour un utilisateur
     * Retourne tous les documents requis avec leur statut
     */
    Mono<UserDocumentStatusResponse> getUserDocumentStatus(String userId, UserRole userRole);
    
    /**
     * Récupérer uniquement les documents manquants/non uploadés pour un utilisateur
     * Retourne seulement les documents qui n'ont pas encore été uploadés ou approuvés
     */
    Mono<UserDocumentStatusResponse> getMissingDocuments(String userId, UserRole userRole);
    
    /**
     * Vérifier les documents expirés et mettre à jour leur statut
     */
    Mono<Long> checkAndUpdateExpiredDocuments();
    
    /**
     * Récupérer tous les documents en attente de validation (admin)
     */
    Flux<DocumentListResponse> getPendingDocuments();
    
    /**
     * Récupérer tous les documents avec filtres optionnels (admin)
     */
    Flux<DocumentListResponse> getAllDocuments(String status, String userId);
    
    /**
     * Récupérer les statistiques des documents (admin)
     */
    Mono<java.util.Map<String, Object>> getDocumentStats();
    
    /**
     * Récupérer les détails d'un document (admin - sans restriction userId)
     */
    Mono<DocumentDetailResponse> getDocumentDetailsAdmin(String documentId);
    
    /**
     * Télécharger un document (admin - sans restriction userId)
     */
    Mono<Flux<org.springframework.core.io.buffer.DataBuffer>> downloadDocumentAdmin(String documentId);
    
    /**
     * Supprimer un document (admin - sans restriction userId)
     */
    Mono<Void> deleteDocumentAdmin(String documentId, String adminId);
}
