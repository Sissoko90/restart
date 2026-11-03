package com.abdatytch.interface_service.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO de réponse pour un projet
 */
public class ProjectResponse {
    
    private UUID id;
    private String name;
    private String description;
    private UUID userId;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
    
    // Statuts de synchronisation avec autres services
    private Boolean settingsSynced;
    private Boolean quotasSynced;

    // Constructeurs
    public ProjectResponse() {}

    // Getters
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getUserId() { return userId; }
    public Boolean getIsActive() { return isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public Boolean getSettingsSynced() { return settingsSynced; }
    public Boolean getQuotasSynced() { return quotasSynced; }

    // Setters
    public void setId(UUID id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public void setVersion(Long version) { this.version = version; }
    public void setSettingsSynced(Boolean settingsSynced) { this.settingsSynced = settingsSynced; }
    public void setQuotasSynced(Boolean quotasSynced) { this.quotasSynced = quotasSynced; }
}
