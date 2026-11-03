package com.abdatytch.media_service.dto.response;

import com.abdatytch.media_service.enums.DocumentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO pour la liste des documents (vue simplifiée)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListResponse {
    
    private String id;
    private String userId;
    private String documentTypeCode;
    private String fileName;
    private Long fileSize;
    private DocumentStatus status;
    private Boolean isMandatory;
    private LocalDate expiryDate;
    private Boolean isExpired;
    private LocalDateTime uploadedAt;
}
