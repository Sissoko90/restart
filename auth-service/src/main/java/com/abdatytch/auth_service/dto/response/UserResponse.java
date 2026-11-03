package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.enums.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {
    
    private String id;
    private String phoneNumber;
    private String countryCode;
    private String email;
    private String firstName;
    private String lastName;
    private UserRole role;
    private Boolean isPhoneVerified;
    private Boolean isEmailVerified;
    private Boolean isActive;
    private LocalDateTime lastLogin;
    private LocalDateTime createdAt;
}
