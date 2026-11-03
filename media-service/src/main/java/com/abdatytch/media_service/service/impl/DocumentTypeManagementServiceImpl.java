package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.dto.request.CreateDocumentTypeRequest;
import com.abdatytch.media_service.dto.response.DocumentRoleAssignmentResponse;
import com.abdatytch.media_service.dto.response.DocumentTypeResponse;
import com.abdatytch.media_service.entity.DocumentRoleRequirement;
import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.enums.DocumentCategory;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.exception.MediaNotFoundException;
import com.abdatytch.media_service.repository.DocumentRoleRequirementRepository;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import com.abdatytch.media_service.service.DocumentTypeManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTypeManagementServiceImpl implements DocumentTypeManagementService {
    
    private final DocumentTypeConfigRepository documentTypeConfigRepository;
    private final DocumentRoleRequirementRepository roleRequirementRepository;
    
    @Override
    public Mono<DocumentTypeResponse> createDocumentType(CreateDocumentTypeRequest request, String adminId) {
        log.info(" Admin {} creating document type: {}", adminId, request.getCode());
        
        return documentTypeConfigRepository.existsByCode(request.getCode())
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new IllegalArgumentException("Document type with code " + request.getCode() + " already exists"));
                }
                
                DocumentTypeConfig config = DocumentTypeConfig.builder()
                    .code(request.getCode())
                    .displayName(request.getDisplayName())
                    .description(request.getDescription())
                    .category(request.getCategory())
                    .requiresNumber(request.getRequiresNumber())
                    .requiresExpirationDate(request.getRequiresExpirationDate())
                    .requiresCategory(request.getRequiresCategory())
                    .availableCategories(request.getAvailableCategories())
                    .categoriesWithoutExpiration(request.getCategoriesWithoutExpiration())
                    .defaultValidityYears(request.getDefaultValidityYears())
                    .acceptedMimeTypes(request.getAcceptedMimeTypes())
                    .maxFileSizeMB(request.getMaxFileSizeMB())
                    .isActive(true)
                    .build();
                
                config.setCreatedBy(adminId);
                
                return documentTypeConfigRepository.save(config)
                    .doOnSuccess(saved -> log.info(" Document type created: {}", saved.getCode()))
                    .map(this::toResponse);
            });
    }
    
    @Override
    public Mono<DocumentTypeResponse> updateDocumentType(String id, CreateDocumentTypeRequest request, String adminId) {
        log.info(" Admin {} updating document type: {}", adminId, id);
        
        return documentTypeConfigRepository.findById(id)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document type not found")))
            .flatMap(config -> {
                config.setDisplayName(request.getDisplayName());
                config.setDescription(request.getDescription());
                config.setCategory(request.getCategory());
                config.setRequiresNumber(request.getRequiresNumber());
                config.setRequiresExpirationDate(request.getRequiresExpirationDate());
                config.setRequiresCategory(request.getRequiresCategory());
                config.setAvailableCategories(request.getAvailableCategories());
                config.setCategoriesWithoutExpiration(request.getCategoriesWithoutExpiration());
                config.setDefaultValidityYears(request.getDefaultValidityYears());
                config.setAcceptedMimeTypes(request.getAcceptedMimeTypes());
                config.setMaxFileSizeMB(request.getMaxFileSizeMB());
                config.setLastModifiedBy(adminId);
                
                return documentTypeConfigRepository.save(config)
                    .doOnSuccess(saved -> log.info(" Document type updated: {}", saved.getCode()))
                    .map(this::toResponse);
            });
    }
    
    @Override
    public Mono<Void> deleteDocumentType(String id, String adminId) {
        log.info(" Admin {} deleting document type: {}", adminId, id);
        
        return documentTypeConfigRepository.findById(id)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document type not found")))
            .flatMap(config -> {
                config.setIsActive(false);
                config.setLastModifiedBy(adminId);
                
                return documentTypeConfigRepository.save(config)
                    .doOnSuccess(saved -> log.info(" Document type deactivated: {}", saved.getCode()))
                    .then();
            });
    }
    
    @Override
    public Mono<DocumentTypeResponse> getDocumentTypeById(String id) {
        return documentTypeConfigRepository.findById(id)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document type not found")))
            .map(this::toResponse);
    }
    
    @Override
    public Mono<DocumentTypeResponse> getDocumentTypeByCode(String code) {
        return documentTypeConfigRepository.findByCode(code)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document type not found")))
            .map(this::toResponse);
    }
    
    @Override
    public Flux<DocumentTypeResponse> getAllDocumentTypes() {
        return documentTypeConfigRepository.findAll()
            .map(this::toResponse);
    }
    
    @Override
    public Flux<DocumentTypeResponse> getActiveDocumentTypes() {
        return documentTypeConfigRepository.findByIsActiveTrue()
            .map(this::toResponse);
    }
    
    @Override
    public Flux<DocumentTypeResponse> getDocumentTypesByCategory(DocumentCategory category) {
        return documentTypeConfigRepository.findByCategoryAndIsActiveTrue(category, true)
            .map(this::toResponse);
    }
    
    @Override
    public Mono<DocumentRoleAssignmentResponse> assignDocumentToRole(
            String documentTypeId,
            UserRole role,
            Boolean mandatory,
            String adminId) {
        
        log.info("Admin {} assigning document {} to role {} (mandatory: {})", 
            adminId, documentTypeId, role, mandatory);
        
        return documentTypeConfigRepository.findById(documentTypeId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document type not found")))
            .flatMap(docType -> 
                roleRequirementRepository.existsByDocumentTypeCodeAndUserRole(docType.getCode(), role)
                    .flatMap(exists -> {
                        if (exists) {
                            return Mono.error(new IllegalArgumentException(
                                "Document already assigned to this role. Use update instead."));
                        }
                        
                        DocumentRoleRequirement requirement = DocumentRoleRequirement.builder()
                            .documentTypeCode(docType.getCode())
                            .userRole(role)
                            .mandatory(mandatory != null ? mandatory : false)
                            .displayOrder(0)
                            .isActive(true)
                            .build();
                        
                        requirement.setCreatedBy(adminId);
                        
                        return roleRequirementRepository.save(requirement)
                            .doOnSuccess(saved -> log.info("Document assigned to role"))
                            .map(saved -> toAssignmentResponse(saved, docType.getDisplayName()));
                    })
            );
    }

    @Override
    public Mono<DocumentRoleAssignmentResponse> updateRoleAssignment(
            String documentTypeCode,
            UserRole userRole,
            Boolean mandatory,
            Integer displayOrder,
            String adminId) {

        log.info("Admin {} updating assignment: {} for role {}", adminId, documentTypeCode, userRole);

        return roleRequirementRepository.findByDocumentTypeCodeAndUserRole(documentTypeCode, userRole)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Assignment not found")))
            .flatMap(requirement -> {
                requirement.setMandatory(mandatory != null ? mandatory : requirement.getMandatory());
                requirement.setDisplayOrder(displayOrder != null ? displayOrder : requirement.getDisplayOrder());
                requirement.setLastModifiedBy(adminId);

                return roleRequirementRepository.save(requirement)
                    .flatMap(saved ->
                        documentTypeConfigRepository.findByCode(documentTypeCode)
                            .map(DocumentTypeConfig::getDisplayName)
                            // Le `defaultIfEmpty` porte sur le Mono TYPE, jamais sur
                            // la chaine finale. Sans lui, un type absent du catalogue
                            // vidait tout le Mono : l'affectation etait pourtant
                            // ENREGISTREE, et l'appelant recevait un 200 au corps
                            // vide, donc concluait a un echec. Le libelle retombe sur
                            // le code, qui est toujours connu ici.
                            .defaultIfEmpty(saved.getDocumentTypeCode())
                            .map(libelle -> toAssignmentResponse(saved, libelle))
                    );
            });
    }

    /**
     * Retire une affectation de rôle — sauf si c'est la dernière active.
     *
     * <h3>L'inversion d'autorisation que ce refus ferme</h3>
     *
     * {@code DocumentTypeValidationServiceImpl} applique la règle « un type sans
     * affectation <b>active</b> est universel », retenue délibérément lors de la
     * migration pour ne pas rendre d'un coup inutilisables les types que le
     * seeder n'assigne pas.
     *
     * <p>Combinée à cette méthode, elle produisait une <b>inversion</b> :
     * l'action d'administration « retirer ce document du rôle CHAUFFEUR »,
     * appliquée à la dernière affectation, ouvrait le dépôt à <b>tous les
     * rôles</b> au lieu de le fermer. Le geste le plus restrictif de l'interface
     * produisait l'élargissement maximal, sans aucun avertissement.
     *
     * <p>Les deux règles restent donc vraies, et aucune n'est renversée : un
     * type sans affectation est universel, <b>et</b> on ne peut plus arriver à
     * cet état par retrait. Fermer un type se fait en le <b>désactivant</b>
     * ({@link #deleteDocumentType}), chemin par lequel {@code resoudre} refuse
     * bien le dépôt puisqu'il vérifie {@code isActive}.
     *
     * <p>Le refus nomme ce qu'il faut faire à la place : un refus qui n'indique
     * pas la sortie se contourne par le chemin le plus court, et ici le chemin
     * le plus court serait de recréer l'affectation ailleurs.
     */
    @Override
    public Mono<Void> removeDocumentFromRole(String documentTypeCode, UserRole userRole, String adminId) {
        log.info("Admin {} removing document {} from role {}", adminId, documentTypeCode, userRole);

        return roleRequirementRepository.findByDocumentTypeCode(documentTypeCode)
            .filter(affectation -> Boolean.TRUE.equals(affectation.getIsActive()))
            .collectList()
            .flatMap(actives -> {
                boolean cibleActive = actives.stream()
                    .anyMatch(affectation -> affectation.getUserRole() == userRole);

                if (cibleActive && actives.size() == 1) {
                    log.warn("Retrait refuse : {} est la derniere affectation active de {}",
                        userRole, documentTypeCode);
                    return Mono.error(new IllegalArgumentException(
                        "Retirer la dernière affectation active rendrait le type '"
                            + documentTypeCode + "' déposable par TOUS les rôles. "
                            + "Pour fermer ce type, le désactiver au lieu de le délier."));
                }

                return roleRequirementRepository
                    .deleteByDocumentTypeCodeAndUserRole(documentTypeCode, userRole);
            })
            .doOnSuccess(v -> log.info(" Document removed from role"));
    }
    
    @Override
    public Flux<DocumentRoleAssignmentResponse> getDocumentsByRole(UserRole userRole) {
        return roleRequirementRepository.findByUserRoleAndIsActiveTrue(userRole, true)
            .flatMap(requirement -> 
                documentTypeConfigRepository.findByCode(requirement.getDocumentTypeCode())
                    .map(docType -> toAssignmentResponse(requirement, docType.getDisplayName()))
            )
            .sort((a, b) -> a.getDisplayOrder().compareTo(b.getDisplayOrder()));
    }
    
    @Override
    public Flux<DocumentRoleAssignmentResponse> getMandatoryDocumentsByRole(UserRole userRole) {
        return roleRequirementRepository.findByUserRoleAndMandatoryAndIsActiveTrue(userRole, true, true)
            .flatMap(requirement -> 
                documentTypeConfigRepository.findByCode(requirement.getDocumentTypeCode())
                    .map(docType -> toAssignmentResponse(requirement, docType.getDisplayName()))
            )
            .sort((a, b) -> a.getDisplayOrder().compareTo(b.getDisplayOrder()));
    }
    
    @Override
    public Flux<DocumentRoleAssignmentResponse> getOptionalDocumentsByRole(UserRole userRole) {
        return roleRequirementRepository.findByUserRoleAndMandatoryAndIsActiveTrue(userRole, false, true)
            .flatMap(requirement -> 
                documentTypeConfigRepository.findByCode(requirement.getDocumentTypeCode())
                    .map(docType -> toAssignmentResponse(requirement, docType.getDisplayName()))
            )
            .sort((a, b) -> a.getDisplayOrder().compareTo(b.getDisplayOrder()));
    }
    
    @Override
    public Flux<DocumentRoleAssignmentResponse> getRolesByDocumentType(String documentTypeCode) {
        return roleRequirementRepository.findByDocumentTypeCode(documentTypeCode)
            .flatMap(requirement -> 
                documentTypeConfigRepository.findByCode(requirement.getDocumentTypeCode())
                    .map(docType -> toAssignmentResponse(requirement, docType.getDisplayName()))
            );
    }
    
    private DocumentTypeResponse toResponse(DocumentTypeConfig config) {
        return DocumentTypeResponse.builder()
            .id(config.getId())
            .code(config.getCode())
            .displayName(config.getDisplayName())
            .description(config.getDescription())
            .category(config.getCategory())
            .requiresNumber(config.getRequiresNumber())
            .requiresExpirationDate(config.getRequiresExpirationDate())
            .requiresCategory(config.getRequiresCategory())
            .availableCategories(config.getAvailableCategories())
            .categoriesWithoutExpiration(config.getCategoriesWithoutExpiration())
            .defaultValidityYears(config.getDefaultValidityYears())
            .acceptedMimeTypes(config.getAcceptedMimeTypes())
            .maxFileSizeMB(config.getMaxFileSizeMB())
            .isActive(config.getIsActive())
            .createdAt(config.getCreatedAt())
            .updatedAt(config.getUpdatedAt())
            .createdBy(config.getCreatedBy())
            .build();
    }
    
    private DocumentRoleAssignmentResponse toAssignmentResponse(DocumentRoleRequirement requirement, String documentTypeName) {
        return DocumentRoleAssignmentResponse.builder()
            .id(requirement.getId())
            .documentTypeCode(requirement.getDocumentTypeCode())
            .documentTypeName(documentTypeName)
            .userRole(requirement.getUserRole())
            .mandatory(requirement.getMandatory())
            .isActive(requirement.getIsActive())
            .displayOrder(requirement.getDisplayOrder())
            .createdAt(requirement.getCreatedAt())
            .build();
    }
}
