package com.abdatytch.interface_service.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Entité de base pour toutes les entités du système
 * Fournit les champs d'audit et de versioning
 */
public abstract class BaseEntity {

    @Id
    private UUID id;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @Column("is_active")
    private Boolean isActive = true;

    // Getters
    public UUID getId() { return id; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public Boolean getIsActive() { return isActive; }

    // Setters
    public void setId(UUID id) { this.id = id; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setVersion(Long version) { this.version = version; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    /**
     * Génère un nouvel ID UUID si non présent
     */
    public void generateId() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
    }

    /**
     * Marque l'entité comme supprimée (soft delete)
     */
    public void markAsDeleted() {
        this.isActive = false;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Vérifie si l'entité est active
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(this.isActive);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BaseEntity that = (BaseEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
