package com.abdatytch.interface_service.mapper;

import com.abdatytch.interface_service.dto.request.RegisterRequest;
import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.entity.User;
import org.springframework.stereotype.Component;

/**
 * Mapper pour les entités User
 * Interface-service se contente de la gestion des utilisateurs
 */
@Component
public class UserMapper {

    /**
     * Convertit une RegisterRequest en entité User
     */
    public User toEntity(RegisterRequest request) {
        if (request == null) {
            return null;
        }

        User user = new User();
        user.generateId();
        user.setUsername(request.getEmail()); // Email comme username par défaut
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhoneNumber(request.getPhoneNumber());
        user.setMarketingConsent(request.getAcceptMarketing());
        // Le mot de passe sera hashé par le service
        
        return user;
    }

    /**
     * Convertit une entité User en UserResponse
     */
    public UserResponse toResponse(User user) {
        if (user == null) {
            return null;
        }

        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setFirstName(user.getFirstName());
        response.setLastName(user.getLastName());
        response.setEmailVerified(user.getEmailVerified());
        response.setPhoneNumber(user.getPhoneNumber());
        response.setPhoneVerified(user.getPhoneVerified());
        response.setLastLoginAt(user.getLastLoginAt());
        response.setCreatedAt(user.getCreatedAt());
        response.setUpdatedAt(user.getUpdatedAt());
        response.setVersion(user.getVersion());
        return response;
    }
}
