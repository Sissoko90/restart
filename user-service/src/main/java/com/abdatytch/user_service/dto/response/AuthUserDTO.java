package com.abdatytch.user_service.dto.response;

import com.abdatytch.user_service.enums.UserRole;

import java.time.LocalDateTime;

/**
 * DTO pour récupérer les données d'identité depuis auth-service
 * Représente les informations gérées par auth-service (source de vérité)
 */
public record AuthUserDTO(
    String id,
    String email,
    String phoneNumber,
    String countryCode,
    String firstName,
    String lastName,
    UserRole role,
    Boolean isActive,
    Boolean isPhoneVerified,
    Boolean isEmailVerified,
    LocalDateTime lastLogin,
    LocalDateTime createdAt
) {}
