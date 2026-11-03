package com.abdatytch.auth_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponseDTO {
    private String id;
    private String authUserId;
    private String email;
    private String phoneNumber;
    private String firstName;
    private String lastName;
    private String status;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}
