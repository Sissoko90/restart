package com.abdatytch.interface_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * DTO de réponse pour l'authentification
 */
@Schema(description = "Réponse d'authentification avec tokens JWT")
public class AuthResponse {
    
    @Schema(description = "Token d'accès JWT", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
    private String accessToken;
    
    @Schema(description = "Token de rafraîchissement", example = "550e8400-e29b-41d4-a716-446655440000")
    private String refreshToken;
    
    @Schema(description = "Type de token", example = "Bearer")
    private String tokenType = "Bearer";
    
    @Schema(description = "Durée de validité du token en secondes", example = "3600")
    private Long expiresIn;
    
    @Schema(description = "Informations de l'utilisateur connecté")
    private UserInfo user;
    
    @Schema(description = "Date et heure de connexion")
    private LocalDateTime loginTime;

    // Constructeurs
    public AuthResponse() {}

    // Getters
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getTokenType() { return tokenType; }
    public Long getExpiresIn() { return expiresIn; }
    public UserInfo getUser() { return user; }
    public LocalDateTime getLoginTime() { return loginTime; }

    // Setters
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    public void setExpiresIn(Long expiresIn) { this.expiresIn = expiresIn; }
    public void setUser(UserInfo user) { this.user = user; }
    public void setLoginTime(LocalDateTime loginTime) { this.loginTime = loginTime; }
    
    /**
     * Informations utilisateur dans la réponse d'auth
     */
    @Schema(description = "Informations utilisateur")
    public static class UserInfo {
        
        @Schema(description = "Identifiant unique de l'utilisateur", example = "user_123456789")
        private String id;
        
        @Schema(description = "Prénom de l'utilisateur", example = "John")
        private String firstName;
        
        @Schema(description = "Nom de famille de l'utilisateur", example = "Doe")
        private String lastName;
        
        @Schema(description = "Adresse email de l'utilisateur", example = "john.doe@example.com")
        private String email;
        
        @Schema(description = "Numéro de téléphone de l'utilisateur", example = "+22300000000")
        private String phoneNumber;
        
        @Schema(description = "Rôles de l'utilisateur", example = "USER,PROJECT_OWNER")
        private String roles;
        
        @Schema(description = "Statut du compte", example = "ACTIVE")
        private String status;
        
        @Schema(description = "Indique si le profil est complet", example = "true")
        private Boolean profileComplete;
        
        @Schema(description = "Date de création du compte")
        private LocalDateTime createdAt;
        
        @Schema(description = "Dernière connexion")
        private LocalDateTime lastLogin;

        // Constructeurs
        public UserInfo() {}

        // Getters
        public String getId() { return id; }
        public String getFirstName() { return firstName; }
        public String getLastName() { return lastName; }
        public String getEmail() { return email; }
        public String getPhoneNumber() { return phoneNumber; }
        public String getRoles() { return roles; }
        public String getStatus() { return status; }
        public Boolean getProfileComplete() { return profileComplete; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getLastLogin() { return lastLogin; }

        // Setters
        public void setId(String id) { this.id = id; }
        public void setFirstName(String firstName) { this.firstName = firstName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
        public void setEmail(String email) { this.email = email; }
        public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
        public void setRoles(String roles) { this.roles = roles; }
        public void setStatus(String status) { this.status = status; }
        public void setProfileComplete(Boolean profileComplete) { this.profileComplete = profileComplete; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }
    }
}
