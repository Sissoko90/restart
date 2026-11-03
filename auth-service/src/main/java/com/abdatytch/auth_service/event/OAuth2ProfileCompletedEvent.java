package com.abdatytch.auth_service.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Événement publié lorsqu'un utilisateur complète son profil OAuth2
 * User-service écoute cet événement pour mettre à jour les informations supplémentaires
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuth2ProfileCompletedEvent implements Serializable {
    
    private String authUserId;
    private String phoneNumber;
    private String countryCode;
    private LocalDate dateOfBirth;
    private String address;
    private String bio;
    private String profilePicture;
    private LocalDateTime completedAt;
    private String eventId;
    
    @Override
    public String toString() {
        return "OAuth2ProfileCompletedEvent{" +
                "authUserId='" + authUserId + '\'' +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", completedAt=" + completedAt +
                '}';
    }
}
