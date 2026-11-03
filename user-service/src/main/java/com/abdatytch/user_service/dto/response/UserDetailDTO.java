package com.abdatytch.user_service.dto.response;

import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record UserDetailDTO(
    UUID id,
    String authUserId,
    String email,
    String phoneNumber,
    String countryCode,
    String firstName,
    String lastName,
    LocalDate dateOfBirth,
    String profilePictureUrl,
    UserStatus status,
    UserRole role,
    String bio,
    String address,
    String city,
    String country,
    String postalCode,
    Map<String, String> preferences,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime lastLoginAt
) {}
