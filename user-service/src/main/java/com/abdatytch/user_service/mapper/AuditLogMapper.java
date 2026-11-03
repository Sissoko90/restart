package com.abdatytch.user_service.mapper;

import com.abdatytch.user_service.dto.request.AuditLogCreateDTO;
import com.abdatytch.user_service.dto.response.AuditLogDTO;
import com.abdatytch.user_service.entities.AuditLog;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Mapper pour convertir entre AuditLog entity et DTOs
 */
@Component
public class AuditLogMapper {
    
    /**
     * Convertit une entité AuditLog en DTO de réponse
     */
    public AuditLogDTO toDTO(AuditLog auditLog) {
        if (auditLog == null) {
            return null;
        }
        
        return AuditLogDTO.builder()
                .id(auditLog.getId())
                .userId(auditLog.getUserId())
                .adminId(auditLog.getAdminId())
                .actionType(auditLog.getActionType())
                .entityType(auditLog.getEntityType())
                .entityId(auditLog.getEntityId())
                .actionDetails(auditLog.getActionDetails())
                .previousValue(auditLog.getPreviousValue())
                .newValue(auditLog.getNewValue())
                .ipAddress(auditLog.getIpAddress())
                .userAgent(auditLog.getUserAgent())
                .timestamp(auditLog.getTimestamp())
                .status(auditLog.getStatus())
                .errorMessage(auditLog.getErrorMessage())
                .createdAt(auditLog.getCreatedAt())
                .updatedAt(auditLog.getUpdatedAt())
                .build();
    }
    
    /**
     * Convertit un DTO de création en entité AuditLog
     */
    public AuditLog toEntity(AuditLogCreateDTO dto) {
        if (dto == null) {
            return null;
        }
        
        return AuditLog.builder()
                .userId(dto.getUserId())
                .adminId(dto.getAdminId())
                .actionType(dto.getActionType())
                .entityType(dto.getEntityType())
                .entityId(dto.getEntityId())
                .actionDetails(dto.getActionDetails())
                .previousValue(dto.getPreviousValue())
                .newValue(dto.getNewValue())
                .ipAddress(dto.getIpAddress())
                .userAgent(dto.getUserAgent())
                .timestamp(LocalDateTime.now())
                .status(dto.getStatus())
                .errorMessage(dto.getErrorMessage())
                .build();
    }
}
