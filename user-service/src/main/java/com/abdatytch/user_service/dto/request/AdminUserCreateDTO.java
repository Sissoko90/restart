package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminUserCreateDTO(
    @NotBlank(message = ValidationMessages.PHONE_REQUIRED)
    @Pattern(regexp = ValidationMessages.REGEX_PHONE, message = ValidationMessages.PHONE_INVALID)
    String phoneNumber,
    
    @NotBlank(message = ValidationMessages.COUNTRY_CODE_REQUIRED)
    @Size(min = 2, max = 5, message = ValidationMessages.COUNTRY_CODE_SIZE)
    String countryCode,
    
    @Email(message = ValidationMessages.EMAIL_INVALID)
    String email,
    
    @NotBlank(message = ValidationMessages.FIRST_NAME_REQUIRED)
    @Size(min = 2, max = 50, message = ValidationMessages.FIRST_NAME_SIZE_SHORT)
    String firstName,
    
    @NotBlank(message = ValidationMessages.LAST_NAME_REQUIRED)
    @Size(min = 2, max = 50, message = ValidationMessages.LAST_NAME_SIZE_SHORT)
    String lastName,
    
    @NotBlank(message = ValidationMessages.PASSWORD_REQUIRED)
    @Size(min = 8, message = ValidationMessages.PASSWORD_MIN_SIZE)
    String password,
    
    @NotBlank(message = ValidationMessages.ROLE_REQUIRED)
    String role
) {}
