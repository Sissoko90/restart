package com.abdatytch.auth_service.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Événement publié lorsqu'un utilisateur se connecte
 * User-service écoute cet événement pour mettre à jour lastLoginAt
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoggedInEvent implements Serializable {
    
    private String authUserId;
    private LocalDateTime loginAt;
    private String ipAddress;
    private String userAgent;
    private String eventId;
    
    @Override
    public String toString() {
        return "UserLoggedInEvent{" +
                "authUserId='" + authUserId + '\'' +
                ", loginAt=" + loginAt +
                ", ipAddress='" + ipAddress + '\'' +
                '}';
    }
}
