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
public class PhoneAuthVerifyRequest {
    
    @NotBlank(message = AppConstants.PHONE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.PHONE_PATTERN, message = AppConstants.PHONE_INVALID_MESSAGE)
    private String phoneNumber;
    
    @NotBlank(message = AppConstants.CODE_REQUIRED_MESSAGE)
    @Size(min = AppConstants.VERIFICATION_CODE_LENGTH, max = AppConstants.VERIFICATION_CODE_LENGTH, 
          message = AppConstants.CODE_SIZE_MESSAGE)
    @Pattern(regexp = AppConstants.CODE_NUMERIC_PATTERN, message = AppConstants.CODE_PATTERN_MESSAGE)
    private String otp;
    
    @NotBlank(message = AppConstants.PASSWORD_REQUIRED_MESSAGE)
    @Size(min = AppConstants.PASSWORD_MIN_LENGTH, max = AppConstants.PASSWORD_MAX_LENGTH, 
          message = AppConstants.PASSWORD_SIZE_MESSAGE)
    @Pattern(regexp = AppConstants.PASSWORD_PATTERN, 
             message = AppConstants.PASSWORD_PATTERN_MESSAGE)
    private String password;
}
