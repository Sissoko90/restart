package com.abdatytch.media_service.dto.request;

import com.abdatytch.media_service.enums.DocumentCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDocumentTypeRequest {
    
    private String code;
    private String displayName;
    private String description;
    private DocumentCategory category;
    
    @Builder.Default
    private Boolean requiresNumber = false;
    
    @Builder.Default
    private Boolean requiresExpirationDate = false;
    
    @Builder.Default
    private Boolean requiresCategory = false;
    
    private List<String> availableCategories;
    private List<String> categoriesWithoutExpiration;
    
    @Builder.Default
    private Integer defaultValidityYears = 0;
    
    private List<String> acceptedMimeTypes;
    
    @Builder.Default
    private Integer maxFileSizeMB = 50;
}
