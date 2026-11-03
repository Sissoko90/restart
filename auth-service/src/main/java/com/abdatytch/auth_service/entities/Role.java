package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.UserRole;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Entité représentant un rôle utilisateur
 * Permet la gestion des rôles multiples par utilisateur
 */
@Table("roles")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role extends BaseEntity {
    @Column("name")
    private UserRole name;
    
    @Column("description")
    private String description;
    
    @Column("is_active")
    @Builder.Default
    private Boolean isActive = true;
}
