package com.abdatytch.auth_service.mappers;

import com.abdatytch.auth_service.dto.response.UserWithRolesResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.UserRole;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Mapper pour convertir User + Roles en UserWithRolesResponse
 * Respecte la couche de séparation DTO/Entity
 */
@Component
public class UserRoleMapper {
    
    /**
     * Convertit une entité User avec ses rôles en UserWithRolesResponse
     * @param user L'entité User
     * @param roles L'ensemble des rôles de l'utilisateur
     * @param primaryRole Le rôle principal de l'utilisateur
     * @return UserWithRolesResponse
     */
    public UserWithRolesResponse toUserWithRolesResponse(User user, Set<UserRole> roles, UserRole primaryRole) {
        if (user == null) {
            return null;
        }
        
        return UserWithRolesResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .countryCode(user.getCountryCode())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .roles(roles)
                .primaryRole(primaryRole)
                .isPhoneVerified(user.getIsPhoneVerified())
                .isEmailVerified(user.getIsEmailVerified())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt())
                .lastLogin(user.getLastLogin())
                .build();
    }
}
