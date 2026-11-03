package com.abdatytch.auth_service.mappers;

import com.abdatytch.auth_service.dto.response.UserResponse;
import com.abdatytch.auth_service.dto.response.UserResponseDTO;
import com.abdatytch.auth_service.entities.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {
    
    public UserResponse toResponse(User user) {
        if (user == null) {
            return null;
        }
        
        return UserResponse.builder()
                .id(user.getId())
                .phoneNumber(user.getPhoneNumber())
                .countryCode(user.getCountryCode())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole())
                .isPhoneVerified(user.getIsPhoneVerified())
                .isEmailVerified(user.getIsEmailVerified())
                .isActive(user.getIsActive())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .build();
    }
    
    /**
     * Convertit une entité User en UserResponseDTO pour les réponses OAuth2
     */
    public UserResponseDTO toResponseDTO(User user) {
        if (user == null) {
            return null;
        }
        
        return UserResponseDTO.builder()
                .id(user.getId())
                .authUserId(user.getId())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .status(user.getIsActive() ? "ACTIVE" : "INACTIVE")
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .lastLoginAt(user.getLastLogin())
                .build();
    }
}
