package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForgotPasswordRequest {
    
    @NotBlank(message = AppConstants.PHONE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.PHONE_PATTERN, message = AppConstants.PHONE_INVALID_MESSAGE)
    private String phoneNumber;
}
