package com.abdatytch.interface_service.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Événement publié lors de la création d'un projet
 * Contient uniquement les informations de base - les configurations seront gérées par setting-service
 */
public class ProjectCreatedEvent {

    private UUID projectId;
    private String projectName;
    private String description;
    private UUID userId;
    private String userEmail;
    private String userName;
    private LocalDateTime createdAt;
    private String eventId;
    private LocalDateTime eventTimestamp;

    // Constructeurs
    public ProjectCreatedEvent() {}

    public ProjectCreatedEvent(UUID projectId, String projectName, String description, UUID userId,
                              String userEmail, String userName, LocalDateTime createdAt,
                              String eventId, LocalDateTime eventTimestamp) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.description = description;
        this.userId = userId;
        this.userEmail = userEmail;
        this.userName = userName;
        this.createdAt = createdAt;
        this.eventId = eventId;
        this.eventTimestamp = eventTimestamp;
    }

    // Getters
    public UUID getProjectId() { return projectId; }
    public String getProjectName() { return projectName; }
    public String getDescription() { return description; }
    public UUID getUserId() { return userId; }
    public String getUserEmail() { return userEmail; }
    public String getUserName() { return userName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getEventId() { return eventId; }
    public LocalDateTime getEventTimestamp() { return eventTimestamp; }

    // Setters
    public void setProjectId(UUID projectId) { this.projectId = projectId; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public void setDescription(String description) { this.description = description; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public void setUserName(String userName) { this.userName = userName; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public void setEventTimestamp(LocalDateTime eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    /**
     * Crée un événement avec un ID et timestamp automatiques
     */
    public static ProjectCreatedEvent create(UUID projectId, String projectName, String description, 
                                           UUID userId, String userEmail, String userName, 
                                           LocalDateTime createdAt) {
        ProjectCreatedEvent event = new ProjectCreatedEvent();
        event.setProjectId(projectId);
        event.setProjectName(projectName);
        event.setDescription(description);
        event.setUserId(userId);
        event.setUserEmail(userEmail);
        event.setUserName(userName);
        event.setCreatedAt(createdAt);
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTimestamp(LocalDateTime.now());
        return event;
    }
}
