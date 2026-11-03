package com.abdatytch.interface_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO pour l'inscription utilisateur
 */
@Schema(description = "Requête d'inscription utilisateur")
public class RegisterRequest {
    
    @Schema(description = "Prénom de l'utilisateur", example = "John")
    @NotBlank(message = "Le prénom est obligatoire")
    @Size(min = 2, max = 50, message = "Le prénom doit contenir entre 2 et 50 caractères")
    @Pattern(regexp = "^[a-zA-ZÀ-ÿ\\s'-]+$", message = "Le prénom ne peut contenir que des lettres, espaces, apostrophes et tirets")
    private String firstName;
    
    @Schema(description = "Nom de famille de l'utilisateur", example = "Doe")
    @NotBlank(message = "Le nom de famille est obligatoire")
    @Size(min = 2, max = 50, message = "Le nom de famille doit contenir entre 2 et 50 caractères")
    @Pattern(regexp = "^[a-zA-ZÀ-ÿ\\s'-]+$", message = "Le nom de famille ne peut contenir que des lettres, espaces, apostrophes et tirets")
    private String lastName;
    
    @Schema(description = "Adresse email de l'utilisateur", example = "john.doe@example.com")
    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "Format d'email invalide")
    @Size(max = 100, message = "L'email ne peut dépasser 100 caractères")
    private String email;
    
    @Schema(description = "Numéro de téléphone de l'utilisateur", example = "+22300000000")
    @NotBlank(message = "Le numéro de téléphone est obligatoire")
    @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "Format de téléphone invalide (format international requis)")
    private String phoneNumber;
    
    @Schema(description = "Mot de passe de l'utilisateur", example = "SecurePass123!")
    @NotBlank(message = "Le mot de passe est obligatoire")
    @Size(min = 8, max = 100, message = "Le mot de passe doit contenir entre 8 et 100 caractères")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$", 
             message = "Le mot de passe doit contenir au moins une minuscule, une majuscule, un chiffre et un caractère spécial")
    private String password;
    
    @Schema(description = "Confirmation du mot de passe", example = "SecurePass123!")
    @NotBlank(message = "La confirmation du mot de passe est obligatoire")
    private String confirmPassword;
    
    @Schema(description = "Acceptation des conditions d'utilisation", example = "true")
    private Boolean acceptTerms = false;
    
    @Schema(description = "Acceptation de recevoir des emails marketing", example = "false")
    private Boolean acceptMarketing = false;

    // Constructeurs
    public RegisterRequest() {}

    public RegisterRequest(String firstName, String lastName, String email, String phoneNumber, 
                          String password, String confirmPassword, Boolean acceptTerms, Boolean acceptMarketing) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.password = password;
        this.confirmPassword = confirmPassword;
        this.acceptTerms = acceptTerms;
        this.acceptMarketing = acceptMarketing;
    }

    // Getters
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getPhoneNumber() { return phoneNumber; }
    public String getPassword() { return password; }
    public String getConfirmPassword() { return confirmPassword; }
    public Boolean getAcceptTerms() { return acceptTerms; }
    public Boolean getAcceptMarketing() { return acceptMarketing; }

    // Setters
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public void setEmail(String email) { this.email = email; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public void setPassword(String password) { this.password = password; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
    public void setAcceptTerms(Boolean acceptTerms) { this.acceptTerms = acceptTerms; }
    public void setAcceptMarketing(Boolean acceptMarketing) { this.acceptMarketing = acceptMarketing; }
}
