package com.abdatytch.interface_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO pour la création d'un nouveau projet
 */
public class CreateProjectRequest {
    
    @NotBlank(message = "Le nom du projet est obligatoire")
    @Size(min = 3, max = 100, message = "Le nom doit contenir entre 3 et 100 caractères")
    private String projectName;
    
    @NotBlank(message = "L'ID du projet est obligatoire")
    @Size(min = 3, max = 30, message = "L'ID doit contenir entre 3 et 30 caractères")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "L'ID ne peut contenir que des lettres minuscules, chiffres et tirets")
    private String projectId;
    
    private Boolean abdaAnalyticsEnabled = false;
    
    @Size(max = 500, message = "La description ne peut pas dépasser 500 caractères")
    private String description;

    // Constructeurs
    public CreateProjectRequest() {}

    public CreateProjectRequest(String projectName, String projectId, Boolean abdaAnalyticsEnabled, String description) {
        this.projectName = projectName;
        this.projectId = projectId;
        this.abdaAnalyticsEnabled = abdaAnalyticsEnabled;
        this.description = description;
    }

    // Getters
    public String getProjectName() { return projectName; }
    public String getProjectId() { return projectId; }
    public Boolean getAbdaAnalyticsEnabled() { return abdaAnalyticsEnabled; }
    public String getDescription() { return description; }

    // Setters
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public void setAbdaAnalyticsEnabled(Boolean abdaAnalyticsEnabled) { this.abdaAnalyticsEnabled = abdaAnalyticsEnabled; }
    public void setDescription(String description) { this.description = description; }
}
