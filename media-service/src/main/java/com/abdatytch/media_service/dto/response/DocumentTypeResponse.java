package com.abdatytch.media_service.dto.response;

import com.abdatytch.media_service.enums.DocumentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTypeResponse {
    
    private String id;
    private String code;
    private String displayName;
    private String description;
    private DocumentCategory category;
    
    private Boolean requiresNumber;
    private Boolean requiresExpirationDate;
    private Boolean requiresCategory;
    
    private List<String> availableCategories;
    private List<String> categoriesWithoutExpiration;
    
    private Integer defaultValidityYears;
    private List<String> acceptedMimeTypes;
    private Integer maxFileSizeMB;
    
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
}
