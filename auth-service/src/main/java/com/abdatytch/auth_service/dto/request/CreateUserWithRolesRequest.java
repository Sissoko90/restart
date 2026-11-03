package com.abdatytch.auth_service.dto.request;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * DTO pour la création d'un utilisateur avec plusieurs rôles
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserWithRolesRequest {
    
    @Email(message = AppConstants.EMAIL_INVALID_MESSAGE)
    @NotBlank(message = AppConstants.EMAIL_REQUIRED_MESSAGE)
    private String email;
    
    @Pattern(regexp = AppConstants.PHONE_PATTERN, message = AppConstants.PHONE_INVALID_MESSAGE)
    private String phoneNumber;
    
    @Pattern(regexp = "^\\+?[1-9]\\d{0,3}$", message = AppConstants.COUNTRY_CODE_INVALID_MESSAGE)
    private String countryCode;
    
    @NotBlank(message = AppConstants.FIRSTNAME_REQUIRED_MESSAGE)
    @Size(min = 2, max = 50, message = AppConstants.FIRSTNAME_TOO_LONG_MESSAGE)
    private String firstName;
    
    @NotBlank(message = AppConstants.NAME_REQUIRED_MESSAGE)
    @Size(min = 2, max = 50, message = AppConstants.NAME_TOO_LONG_MESSAGE)
    private String lastName;
    
    @NotBlank(message = AppConstants.PASSWORD_REQUIRED_MESSAGE)
    @Size(min = AppConstants.PASSWORD_MIN_LENGTH, max = AppConstants.PASSWORD_MAX_LENGTH, message = AppConstants.PASSWORD_SIZE_MESSAGE)
    @Pattern(regexp = AppConstants.PASSWORD_PATTERN, message = AppConstants.PASSWORD_PATTERN_MESSAGE)
    private String password;
    
    @NotEmpty(message = "Au moins un rôle doit être spécifié")
    private Set<UserRole> roles;
    
    private UserRole primaryRole;
}
