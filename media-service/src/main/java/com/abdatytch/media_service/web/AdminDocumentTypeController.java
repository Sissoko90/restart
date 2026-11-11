package com.abdatytch.media_service.web;

import com.abdatytch.media_service.dto.request.CreateDocumentTypeRequest;
import com.abdatytch.media_service.dto.response.DocumentRoleAssignmentResponse;
import com.abdatytch.media_service.dto.response.DocumentTypeResponse;
import com.abdatytch.media_service.enums.DocumentCategory;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.service.DocumentTypeManagementService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Controller admin pour la gestion des types de documents
 */
@Slf4j
@RestController
@RequestMapping("/admin/document-types")
@RequiredArgsConstructor
public class AdminDocumentTypeController {
    
    private final DocumentTypeManagementService documentTypeManagementService;
    
    /**
     * Créer un nouveau type de document
     */
    @PostMapping
    public Mono<DocumentTypeResponse> createDocumentType(
            @Valid @RequestBody CreateDocumentTypeRequest request,
            ServerWebExchange exchange) {
        
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} creating document type: {}", adminId, request.getCode());
        
        return documentTypeManagementService.createDocumentType(request, adminId);
    }
    
    /**
     * Mettre à jour un type de document
     */
    @PutMapping("/{id}")
    public Mono<DocumentTypeResponse> updateDocumentType(
            @PathVariable String id,
            @Valid @RequestBody CreateDocumentTypeRequest request,
            ServerWebExchange exchange) {
        
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} updating document type: {}", adminId, id);
        
        return documentTypeManagementService.updateDocumentType(id, request, adminId);
    }
    
    /**
     * Supprimer un type de document
     */
    @DeleteMapping("/{id}")
    public Mono<Void> deleteDocumentType(
            @PathVariable String id,
            ServerWebExchange exchange) {
        
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} deleting document type: {}", adminId, id);
        
        return documentTypeManagementService.deleteDocumentType(id, adminId);
    }
    
    /**
     * Récupérer un type de document par ID
     */
    @GetMapping("/{id}")
    public Mono<DocumentTypeResponse> getDocumentTypeById(@PathVariable String id) {
        return documentTypeManagementService.getDocumentTypeById(id);
    }
    
    /**
     * Récupérer un type de document par code
     */
    @GetMapping("/code/{code}")
    public Mono<DocumentTypeResponse> getDocumentTypeByCode(@PathVariable String code) {
        return documentTypeManagementService.getDocumentTypeByCode(code);
    }
    
    /**
     * Lister tous les types de documents
     */
    @GetMapping
    public Flux<DocumentTypeResponse> getAllDocumentTypes(
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) DocumentCategory category) {
        
        if (category != null) {
            return documentTypeManagementService.getDocumentTypesByCategory(category);
        }
        
        if (Boolean.TRUE.equals(activeOnly)) {
            return documentTypeManagementService.getActiveDocumentTypes();
        }
        
        return documentTypeManagementService.getAllDocumentTypes();
    }
    
    /**
     * Assigner un type de document à un rôle
     */
    @PostMapping("/{documentTypeId}/assign-role")
    public Mono<DocumentRoleAssignmentResponse> assignDocumentToRole(
            @PathVariable String documentTypeId,
            @RequestParam UserRole role,
            @RequestParam Boolean mandatory,
            ServerWebExchange exchange) {
        
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} assigning document type {} to role {}", adminId, documentTypeId, role);
        
        return documentTypeManagementService.assignDocumentToRole(documentTypeId, role, mandatory, adminId);
    }
    
    /**
     * Mettre à jour l'affectation d'un document à un rôle
     */
    @PutMapping("/{code}/roles/{role}")
    public Mono<DocumentRoleAssignmentResponse> updateRoleAssignment(
            @PathVariable String code,
            @PathVariable UserRole role,
            @RequestParam Boolean mandatory,
            @RequestParam(required = false) Integer displayOrder,
            ServerWebExchange exchange) {
        
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} updating assignment: {} for role {}", adminId, code, role);
        
        return documentTypeManagementService.updateRoleAssignment(code, role, mandatory, displayOrder, adminId);
    }
    
    /**
     * Retirer un document d'un rôle
     */
    @DeleteMapping("/{documentTypeId}/unassign-role")
    public Mono<Void> unassignDocumentFromRole(
            @PathVariable String documentTypeId,
            @RequestParam UserRole role,
            ServerWebExchange exchange) {
        
        String adminId = exchange.getAttribute("userId");
        log.info("Admin {} removing document {} from role {}", adminId, documentTypeId, role);
        
        return documentTypeManagementService.removeDocumentFromRole(documentTypeId, role, adminId);
    }
    
    /**
     * Récupérer tous les documents d'un rôle
     */
    @GetMapping("/roles/{role}")
    public Flux<DocumentRoleAssignmentResponse> getDocumentsByRole(
            @PathVariable UserRole role,
            @RequestParam(required = false) Boolean mandatoryOnly,
            @RequestParam(required = false) Boolean optionalOnly) {
        
        if (Boolean.TRUE.equals(mandatoryOnly)) {
            return documentTypeManagementService.getMandatoryDocumentsByRole(role);
        }
        
        if (Boolean.TRUE.equals(optionalOnly)) {
            return documentTypeManagementService.getOptionalDocumentsByRole(role);
        }
        
        return documentTypeManagementService.getDocumentsByRole(role);
    }
    
    /**
     * Récupérer tous les rôles assignés à un type de document
     */
    @GetMapping("/{code}/roles")
    public Flux<DocumentRoleAssignmentResponse> getRolesByDocumentType(@PathVariable String code) {
        return documentTypeManagementService.getRolesByDocumentType(code);
    }
}
