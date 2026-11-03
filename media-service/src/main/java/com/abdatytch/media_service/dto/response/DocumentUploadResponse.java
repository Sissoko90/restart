package com.abdatytch.media_service.dto.response;

import com.abdatytch.media_service.enums.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO pour la réponse d'upload de document
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadResponse {
    
    private String documentId;
    private String documentTypeCode;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private DocumentStatus status;
    
    private Boolean securityCheckPassed;
    private List<String> securityFlags;
    
    private Double qualityScore;
    private Boolean isReadable;
    
    private String message;
    private LocalDateTime uploadedAt;
}
