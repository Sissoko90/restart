package com.abdatytch.auth_service.event;

import com.abdatytch.auth_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Événement publié lorsqu'un utilisateur est créé dans auth-service
 * User-service écoute cet événement pour créer le profil utilisateur
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreatedEvent implements Serializable {
    
    private String authUserId;
    private String email;
    private String phoneNumber;
    private String firstName;
    private String lastName;
    private UserRole role;
    private LocalDateTime createdAt;
    private String eventId;
    
    @Override
    public String toString() {
        return "UserCreatedEvent{" +
                "authUserId='" + authUserId + '\'' +
                ", email='" + email + '\'' +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", role=" + role +
                ", createdAt=" + createdAt +
                '}';
    }
}
