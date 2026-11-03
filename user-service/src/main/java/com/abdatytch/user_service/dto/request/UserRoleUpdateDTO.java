package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import jakarta.validation.constraints.NotBlank;

public record UserRoleUpdateDTO(
    @NotBlank(message = ValidationMessages.ROLE_REQUIRED)
    String role,
    
    String reason
) {}
