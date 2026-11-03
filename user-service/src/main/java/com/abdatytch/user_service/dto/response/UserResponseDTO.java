package com.abdatytch.user_service.dto.response;

import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponseDTO(
    UUID id,
    String authUserId,
    String email,
    String phoneNumber,
    String firstName,
    String lastName,
    UserStatus status,
    UserRole role,
    LocalDateTime createdAt,
    LocalDateTime lastLoginAt
) {}
