package com.abdatytch.user_service.dto.request;

import com.abdatytch.user_service.constants.ValidationMessages;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * DTO pour mise à jour utilisateur par admin
 * Tous les champs sont optionnels (update partiel)
 * Email est optionnel (peut être null)
 */
public record AdminUserUpdateDTO(
    @Email(message = ValidationMessages.EMAIL_INVALID)
    String email,
    
    @Size(min = 2, max = 100, message = ValidationMessages.FIRST_NAME_SIZE)
    String firstName,
    
    @Size(min = 2, max = 100, message = ValidationMessages.LAST_NAME_SIZE)
    String lastName,
    
    @Past(message = ValidationMessages.DATE_OF_BIRTH_PAST)
    LocalDate dateOfBirth,
    
    @Size(max = 1000, message = ValidationMessages.BIO_MAX_SIZE)
    String bio,
    
    @Size(max = 500, message = ValidationMessages.ADDRESS_MAX_SIZE)
    String address,
    
    @Size(max = 100, message = ValidationMessages.CITY_MAX_SIZE)
    String city,
    
    @Size(max = 100, message = ValidationMessages.COUNTRY_MAX_SIZE)
    String country,
    
    @Pattern(regexp = ValidationMessages.REGEX_POSTAL_CODE, message = ValidationMessages.POSTAL_CODE_PATTERN)
    String postalCode,
    
    @Pattern(regexp = ValidationMessages.REGEX_ROLE, message = ValidationMessages.ROLE_PATTERN)
    String role,
    
    Boolean isActive,
    
    Boolean isLocked
) {}
