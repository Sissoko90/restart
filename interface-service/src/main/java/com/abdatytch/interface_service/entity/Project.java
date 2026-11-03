package com.abdatytch.interface_service.entity;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Entité représentant un projet développeur
 */
@Table("projects")
public class Project extends BaseEntity {

    @NotBlank(message = "Le nom du projet est obligatoire")
    @Size(min = 3, max = 100, message = "Le nom du projet doit contenir entre 3 et 100 caractères")
    @Column("name")
    private String name;

    @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères")
    @Column("description")
    private String description;

    @NotNull(message = "L'ID utilisateur est obligatoire")
    @Column("user_id")
    private UUID userId;

    // Getters
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getUserId() { return userId; }

    // Setters
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setUserId(UUID userId) { this.userId = userId; }
}
