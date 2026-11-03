package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import jakarta.validation.constraints.NotBlank;
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
public class PhoneAuthRequest {
    
    @NotBlank(message = AppConstants.PHONE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.PHONE_PATTERN, message = AppConstants.PHONE_INVALID_MESSAGE)
    @Size(max = AppConstants.PHONE_MAX_LENGTH, message = AppConstants.PHONE_TOO_LONG_MESSAGE)
    private String phoneNumber;
    
    @NotBlank(message = AppConstants.COUNTRY_CODE_REQUIRED_MESSAGE)
    @Size(min = 2, max = 5, message = AppConstants.COUNTRY_CODE_INVALID_MESSAGE)
    private String countryCode;
}
