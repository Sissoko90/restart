package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.dto.response.UserDocumentStatusResponse;
import com.abdatytch.media_service.entity.DocumentTypeConfig;
import com.abdatytch.media_service.entity.UserDocumentRequirement;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.repository.DocumentRoleRequirementRepository;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.repository.UserDocumentRequirementRepository;
import com.abdatytch.media_service.service.DocumentRequirementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentRequirementServiceImpl implements DocumentRequirementService {
    
    private final DocumentRoleRequirementRepository roleRequirementRepository;
    private final DocumentTypeConfigRepository documentTypeConfigRepository;
    private final UserDocumentRequirementRepository userDocumentRequirementRepository;
    private final MediaFileRepository mediaFileRepository;
    
    @Override
    public Mono<UserDocumentStatusResponse> getUserDocumentStatus(String userId, UserRole userRole) {
        log.info("Getting document status for user {} with role {}", userId, userRole);
        
        return initializeUserDocumentRequirements(userId, userRole)
            .then(buildDocumentStatusResponse(userId, userRole, false));
    }
    
    @Override
    public Mono<UserDocumentStatusResponse> getMissingDocuments(String userId, UserRole userRole) {
        log.info("Getting missing documents for user {} with role {}", userId, userRole);
        
        return initializeUserDocumentRequirements(userId, userRole)
            .then(buildDocumentStatusResponse(userId, userRole, true));
    }
    
    @Override
    public Mono<Void> initializeUserDocumentRequirements(String userId, UserRole userRole) {
        return roleRequirementRepository.findByUserRoleAndIsActiveTrue(userRole, true)
            .flatMap(roleReq -> 
                userDocumentRequirementRepository.findByUserIdAndDocumentTypeCode(userId, roleReq.getDocumentTypeCode())
                    .switchIfEmpty(Mono.defer(() -> {
                        UserDocumentRequirement newReq = UserDocumentRequirement.builder()
                            .userId(userId)
                            .userRole(userRole)
                            .documentTypeCode(roleReq.getDocumentTypeCode())
                            .mandatory(roleReq.getMandatory())
                            .completed(false)
                            .uploadAttempts(0)
                            .rejectionCount(0)
                            .build();
                        
                        newReq.setCreatedBy(userId);
                        
                        return userDocumentRequirementRepository.save(newReq)
                            .doOnSuccess(saved -> log.debug("Initialized requirement: {}", saved.getDocumentTypeCode()));
                    }))
            )
            .then();
    }
    
    private Mono<UserDocumentStatusResponse> buildDocumentStatusResponse(String userId, UserRole userRole, boolean onlyMissing) {
        return userDocumentRequirementRepository.findByUserIdAndUserRole(userId, userRole)
            .collectList()
            .flatMap(requirements -> {
                if (requirements.isEmpty()) {
                    return Mono.just(UserDocumentStatusResponse.builder()
                        .userId(userId)
                        .userRole(userRole)
                        .totalRequired(0)
                        .completed(0)
                        .pending(0)
                        .missingMandatory(List.of())
                        .requirements(List.of())
                        .isComplete(true)
                        .completionPercentage(100.0)
                        .build());
                }
                
                List<UserDocumentRequirement> filteredReqs = onlyMissing 
                    ? requirements.stream().filter(r -> !r.getCompleted()).collect(Collectors.toList())
                    : requirements;
                
                return Flux.fromIterable(filteredReqs)
                    .flatMap(req -> 
                        documentTypeConfigRepository.findByCode(req.getDocumentTypeCode())
                            .map(docType -> buildRequirementDetail(req, docType))
                    )
                    .collectList()
                    .map(details -> {
                        long totalRequired = requirements.stream().filter(UserDocumentRequirement::getMandatory).count();
                        long completed = requirements.stream().filter(UserDocumentRequirement::getCompleted).count();
                        long pending = totalRequired - completed;
                        
                        List<String> missingMandatory = requirements.stream()
                            .filter(r -> r.getMandatory() && !r.getCompleted())
                            .map(UserDocumentRequirement::getDocumentTypeCode)
                            .collect(Collectors.toList());
                        
                        double completionPercentage = totalRequired > 0 
                            ? (completed * 100.0) / totalRequired 
                            : 100.0;
                        
                        return UserDocumentStatusResponse.builder()
                            .userId(userId)
                            .userRole(userRole)
                            .totalRequired((int) totalRequired)
                            .completed((int) completed)
                            .pending((int) pending)
                            .missingMandatory(missingMandatory)
                            .requirements(details)
                            .isComplete(missingMandatory.isEmpty())
                            .completionPercentage(completionPercentage)
                            .build();
                    });
            });
    }
    
    private UserDocumentStatusResponse.DocumentRequirementDetail buildRequirementDetail(
            UserDocumentRequirement req, DocumentTypeConfig docType) {
        
        return UserDocumentStatusResponse.DocumentRequirementDetail.builder()
            .documentTypeCode(req.getDocumentTypeCode())
            .documentTypeName(docType.getDisplayName())
            .category(docType.getCategory())
            .mandatory(req.getMandatory())
            .completed(req.getCompleted())
            .requiresNumber(docType.getRequiresNumber())
            .requiresExpirationDate(docType.getRequiresExpirationDate())
            .requiresCategory(docType.getRequiresCategory())
            .availableCategories(docType.getAvailableCategories())
            .currentDocumentId(req.getCurrentDocumentId())
            .documentNumber(req.getDocumentNumber())
            .documentCategory(req.getDocumentCategory())
            .uploadAttempts(req.getUploadAttempts())
            .rejectionCount(req.getRejectionCount())
            .build();
    }
}
