package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.enums.UserRole;
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
public class EmailRegisterVerifyRequest {
    
    @NotBlank(message = AppConstants.EMAIL_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.EMAIL_PATTERN, message = AppConstants.EMAIL_INVALID_MESSAGE)
    @Size(max = AppConstants.EMAIL_MAX_LENGTH, message = AppConstants.EMAIL_TOO_LONG_MESSAGE)
    private String email;
    
    @NotBlank(message = AppConstants.CODE_REQUIRED_MESSAGE)
    @Size(min = AppConstants.VERIFICATION_CODE_LENGTH, max = AppConstants.VERIFICATION_CODE_LENGTH, 
          message = AppConstants.CODE_SIZE_MESSAGE)
    @Pattern(regexp = AppConstants.CODE_NUMERIC_PATTERN, message = AppConstants.CODE_PATTERN_MESSAGE)
    private String otp;
    
    @NotBlank(message = AppConstants.FIRSTNAME_REQUIRED_MESSAGE)
    @Size(max = 50, message = AppConstants.FIRSTNAME_TOO_LONG_MESSAGE)
    private String firstName;
    
    @NotBlank(message = AppConstants.NAME_REQUIRED_MESSAGE)
    @Size(max = 50, message = AppConstants.NAME_TOO_LONG_MESSAGE)
    private String lastName;
    
    @NotBlank(message = AppConstants.PHONE_REQUIRED_MESSAGE)
    @Pattern(regexp = AppConstants.PHONE_PATTERN, message = AppConstants.PHONE_INVALID_MESSAGE)
    @Size(max = AppConstants.PHONE_MAX_LENGTH, message = AppConstants.PHONE_TOO_LONG_MESSAGE)
    private String phoneNumber;
    
    @NotBlank(message = AppConstants.COUNTRY_CODE_REQUIRED_MESSAGE)
    @Size(min = 2, max = 5, message = AppConstants.COUNTRY_CODE_INVALID_MESSAGE)
    private String countryCode;
    
    @NotBlank(message = AppConstants.PASSWORD_REQUIRED_MESSAGE)
    @Size(min = AppConstants.PASSWORD_MIN_LENGTH, max = AppConstants.PASSWORD_MAX_LENGTH, 
          message = AppConstants.PASSWORD_SIZE_MESSAGE)
    @Pattern(regexp = AppConstants.PASSWORD_PATTERN, 
             message = AppConstants.PASSWORD_PATTERN_MESSAGE)
    private String password;
    
    /**
     * Rôle souhaité par l'utilisateur lors de l'inscription
     * Si null ou non fourni, ROLE_USER sera attribué par défaut
     * Valeurs possibles: USER, DRIVER, MERCHANT, PHARMACY, RESTAURANT, SUPERMARKET, DISPATCHER
     * Note: ADMIN ne peut être attribué que par un administrateur existant
     */
    private UserRole desiredRole;
}
