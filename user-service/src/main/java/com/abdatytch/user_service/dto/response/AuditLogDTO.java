package com.abdatytch.user_service.dto.response;

import com.abdatytch.user_service.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO de réponse pour un log d'audit
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogDTO {
    
    private UUID id;
    private UUID userId;
    private String adminId;
    private ActionType actionType;
    private String entityType;
    private String entityId;
    private String actionDetails;
    private String previousValue;
    private String newValue;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime timestamp;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
