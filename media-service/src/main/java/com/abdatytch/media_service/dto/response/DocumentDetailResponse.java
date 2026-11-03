package com.abdatytch.media_service.dto.response;

import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO pour les détails complets d'un document
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDetailResponse {
    
    private String id;
    private String userId;
    private UserRole userRole;
    private String documentTypeCode;
    
    private String fileName;
    private String originalFileName;
    private String mimeType;
    private Long fileSize;
    
    private DocumentStatus status;
    private Boolean isMandatory;
    
    private LocalDate issueDate;
    private LocalDate expiryDate;
    
    private Map<String, Object> metadata;
    
    private Boolean securityCheckPassed;
    private List<String> securityFlags;
    private Double qualityScore;
    private Boolean isReadable;
    
    private String rejectionReason;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    private String downloadUrl;
}
