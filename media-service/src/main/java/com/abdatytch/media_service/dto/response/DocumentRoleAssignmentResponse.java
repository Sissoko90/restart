package com.abdatytch.media_service.dto.response;

import com.abdatytch.media_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRoleAssignmentResponse {
    
    private String id;
    private String documentTypeCode;
    private String documentTypeName;
    private UserRole userRole;
    private Boolean mandatory;
    private Boolean isActive;
    private Integer displayOrder;
    private LocalDateTime createdAt;
}
