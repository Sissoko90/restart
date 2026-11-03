package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {
    
    @Size(max = 50, message = AppConstants.FIRSTNAME_TOO_LONG_MESSAGE)
    private String firstName;
    
    @Size(max = 50, message = AppConstants.NAME_TOO_LONG_MESSAGE)
    private String lastName;
    
    @Pattern(regexp = AppConstants.EMAIL_PATTERN, message = AppConstants.EMAIL_INVALID_MESSAGE)
    @Size(max = AppConstants.EMAIL_MAX_LENGTH, message = AppConstants.EMAIL_TOO_LONG_MESSAGE)
    private String email;
}
