package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record AdminActionDTO(
    @NotBlank(message = ValidationMessages.REASON_REQUIRED)
    @Size(min = 10, max = 500, message = ValidationMessages.REASON_MAX_LENGTH)
    String reason,
    
    LocalDateTime until  // For suspension
) {}
