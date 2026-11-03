package com.abdatytch.user_service.entities;

import com.abdatytch.user_service.enums.ActionType;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Table d'audit pour tracer toutes les actions administratives sur les utilisateurs
 * Entité R2DBC PostgreSQL
 */
@Table("audit_logs")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog extends BaseEntity {
    
    @Column("user_id")
    private UUID userId;
    
    @Column("admin_id")
    private String adminId;  // authUserId de l'admin qui effectue l'action
    
    @Column("action_type")
    private ActionType actionType;
    
    @Column("entity_type")
    private String entityType;  // USER, PROFILE, etc.
    
    @Column("entity_id")
    private String entityId;
    
    @Column("action_details")
    private String actionDetails;  // JSON ou texte décrivant l'action
    
    @Column("previous_value")
    private String previousValue;  // Valeur avant modification
    
    @Column("new_value")
    private String newValue;  // Valeur après modification
    
    @Column("ip_address")
    private String ipAddress;
    
    @Column("user_agent")
    private String userAgent;
    
    @Column("timestamp")
    private LocalDateTime timestamp;
    
    @Column("status")
    private String status;
    
    @Column("error_message")
    private String errorMessage;
    
    // Note: @PrePersist n'existe pas en R2DBC
    // Utilisez @CreatedDate ou initialisez dans le constructeur/builder
}
