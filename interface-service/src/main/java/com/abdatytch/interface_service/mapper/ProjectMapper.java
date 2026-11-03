package com.abdatytch.interface_service.mapper;

import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import com.abdatytch.interface_service.entity.Project;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mapper pour les entités Project
 * Interface-service se contente de la création de base des projets
 */
@Component
public class ProjectMapper {

    /**
     * Convertit une CreateProjectRequest en entité Project
     */
    public Project toEntity(CreateProjectRequest request, UUID userId) {
        if (request == null) {
            return null;
        }

        Project project = new Project();
        project.generateId();
        project.setName(request.getProjectName());
        project.setDescription(request.getDescription());
        project.setUserId(userId);
        
        return project;
    }

    /**
     * Convertit une entité Project en ProjectResponse
     */
    public ProjectResponse toResponse(Project project) {
        if (project == null) {
            return null;
        }

        ProjectResponse response = new ProjectResponse();
        response.setId(project.getId());
        response.setName(project.getName());
        response.setDescription(project.getDescription());
        response.setUserId(project.getUserId());
        response.setIsActive(project.getIsActive());
        response.setCreatedAt(project.getCreatedAt());
        response.setUpdatedAt(project.getUpdatedAt());
        response.setVersion(project.getVersion());
        response.setSettingsSynced(false); // Sera mis à jour par setting-service
        response.setQuotasSynced(false);   // Sera mis à jour par quotas-service
        return response;
    }
}
