package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * DTO de réponse contenant les informations d'un utilisateur avec ses rôles
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserWithRolesResponse {
    
    private String userId;
    private String email;
    private String phoneNumber;
    private String countryCode;
    private String firstName;
    private String lastName;
    private Set<UserRole> roles;
    private UserRole primaryRole;
    private Boolean isPhoneVerified;
    private Boolean isEmailVerified;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime lastLogin;
}
