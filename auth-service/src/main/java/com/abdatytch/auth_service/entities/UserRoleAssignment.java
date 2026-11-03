package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.UserRole;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entité représentant l'affectation d'un rôle à un utilisateur
 * Permet à un utilisateur d'avoir plusieurs rôles
 * Gère l'unicité : un email/téléphone peut exister pour différents rôles
 */
@Table("user_role_assignments")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRoleAssignment extends BaseEntity {
    
    @Column("user_id")
    private String userId;
    
    @Column("email")
    private String email;
    
    @Column("phone_number")
    private String phoneNumber;
    @Column("role")
    private UserRole role;
    
    @Column("is_primary")
    @Builder.Default
    private Boolean isPrimary = false;
    
    @Column("is_active")
    @Builder.Default
    private Boolean isActive = true;
    
    @Column("assigned_at")
    @Builder.Default
    private LocalDateTime assignedAt = LocalDateTime.now();
    
    @Column("assigned_by")
    private String assignedBy;
}
