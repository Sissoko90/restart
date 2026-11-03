package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import com.abdatytch.user_service.enums.ActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO pour la création d'un log d'audit
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogCreateDTO {
    
    private UUID userId;
    
    @NotBlank(message = ValidationMessages.ADMIN_ID_REQUIRED)
    private String adminId;
    
    @NotNull(message = ValidationMessages.ACTION_TYPE_REQUIRED)
    private ActionType actionType;
    
    @NotBlank(message = ValidationMessages.ENTITY_TYPE_REQUIRED)
    private String entityType;
    
    private String entityId;
    
    private String actionDetails;
    
    private String previousValue;
    
    private String newValue;
    
    private String ipAddress;
    
    private String userAgent;
    
    private String status;
    
    private String errorMessage;
}
