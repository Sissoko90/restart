package com.abdatytch.interface_service.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Événement publié lors de la suppression d'un projet.
 *
 * <p>Il porte le {@code userId} : sans lui, quotas-service ne peut pas savoir
 * à quel utilisateur restituer le quota. L'ancienne version publiait uniquement
 * l'UUID du projet, ce qui rendait l'événement inexploitable.
 */
public class ProjectDeletedEvent {

    private UUID projectId;
    private UUID userId;
    private String projectName;
    private String eventId;
    private LocalDateTime eventTimestamp;

    public ProjectDeletedEvent() {
    }

    public static ProjectDeletedEvent create(UUID projectId, UUID userId, String projectName) {
        ProjectDeletedEvent event = new ProjectDeletedEvent();
        event.setProjectId(projectId);
        event.setUserId(userId);
        event.setProjectName(projectName);
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTimestamp(LocalDateTime.now());
        return event;
    }

    public UUID getProjectId() { return projectId; }
    public UUID getUserId() { return userId; }
    public String getProjectName() { return projectName; }
    public String getEventId() { return eventId; }
    public LocalDateTime getEventTimestamp() { return eventTimestamp; }

    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setEventTimestamp(LocalDateTime eventTimestamp) { this.eventTimestamp = eventTimestamp; }
}
