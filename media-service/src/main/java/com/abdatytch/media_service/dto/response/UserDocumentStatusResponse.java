package com.abdatytch.media_service.dto.response;

import com.abdatytch.media_service.enums.DocumentCategory;
import com.abdatytch.media_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO pour le statut de complétion des documents d'un utilisateur
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDocumentStatusResponse {
    
    private String userId;
    private UserRole userRole;
    
    private Integer totalRequired;
    private Integer completed;
    private Integer pending;
    
    private List<String> missingMandatory;
    private List<DocumentRequirementDetail> requirements;
    
    private Boolean isComplete;
    private Double completionPercentage;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentRequirementDetail {

        private String documentTypeCode;
        private String documentTypeName;
        private DocumentCategory category;
        
        private Boolean mandatory;
        private Boolean completed;
        
        private Boolean requiresNumber;
        private Boolean requiresExpirationDate;
        private Boolean requiresCategory;
        private List<String> availableCategories;
        
        private String currentDocumentId;
        private String documentNumber;
        private String documentCategory;
        
        private Integer uploadAttempts;
        private Integer rejectionCount;
    }
}
