package com.abdatytch.auth_service.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.abdatytch.auth_service.constants.AppConstants;

/**
 * DTO pour la complétion du profil utilisateur après authentification OAuth2
 * Google et Facebook ne fournissent pas toutes les informations requises
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileCompletionRequest {
    
    @NotBlank(message = AppConstants.OAUTH2_PHONE_REQUIRED)
    @Pattern(regexp = AppConstants.OAUTH2_PHONE_PATTERN, message = AppConstants.OAUTH2_PHONE_INVALID_FORMAT)
    private String phoneNumber;
    
    @NotBlank(message = AppConstants.OAUTH2_COUNTRY_CODE_REQUIRED)
    @Size(min = 2, max = 5, message = AppConstants.OAUTH2_COUNTRY_CODE_SIZE)
    private String countryCode;
    
    @Email(message = AppConstants.EMAIL_INVALID_MESSAGE)
    private String email; // Optionnel - utilisé si le provider OAuth2 ne fournit pas d'email
    
    /**
     * Jeton temporaire OAuth2 — <b>plus exigé dans le corps</b>.
     *
     * <p>Il arrive désormais par {@code Authorization: Bearer}, et le
     * contrôleur le recopie ici pour le service. Le {@code @NotBlank} a été
     * retiré : avec lui, une requête conforme au nouveau contrat — jeton dans
     * l'en-tête, absent du corps — aurait été rejetée en 400 par la validation,
     * avant même d'atteindre le contrôleur qui le renseigne.
     */
    private String tempToken;
}
