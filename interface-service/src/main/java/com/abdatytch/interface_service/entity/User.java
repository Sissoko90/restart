package com.abdatytch.interface_service.entity;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Entité représentant un utilisateur développeur
 */
@Table("users")
public class User extends BaseEntity {

    @NotBlank(message = "Le nom d'utilisateur est obligatoire")
    @Size(min = 3, max = 50, message = "Le nom d'utilisateur doit contenir entre 3 et 50 caractères")
    @Column("username")
    private String username;

    @NotBlank(message = "L'email est obligatoire")
    @Email(message = "Format d'email invalide")
    @Column("email")
    private String email;

    @NotBlank(message = "Le mot de passe est obligatoire")
    @Column("password_hash")
    private String passwordHash;

    @Size(max = 100, message = "Le prénom ne peut pas dépasser 100 caractères")
    @Column("first_name")
    private String firstName;

    @Size(max = 100, message = "Le nom ne peut pas dépasser 100 caractères")
    @Column("last_name")
    private String lastName;

    @Column("email_verified")
    private Boolean emailVerified = false;

    @Column("phone_number")
    private String phoneNumber;

    @Column("phone_verified")
    private Boolean phoneVerified = false;

    @Column("last_login_at")
    private LocalDateTime lastLoginAt;

    @Column("failed_login_attempts")
    private Integer failedLoginAttempts = 0;

    @Column("account_locked_until")
    private LocalDateTime accountLockedUntil;

    @Column("password_reset_token")
    private String passwordResetToken;

    @Column("password_reset_expires_at")
    private LocalDateTime passwordResetExpiresAt;

    /**
     * Empreinte SHA-256 du jeton de rafraichissement, 64 caracteres hexadecimaux.
     *
     * <p>Jamais la valeur presentee par le client. Le champ
     * {@code refreshToken}, qui portait cette valeur EN CLAIR, a ete retire
     * avec sa colonne par la migration V9 : deux colonnes coexistaient pour le
     * meme etat — celle-ci, creee par V1, et la version en clair ajoutee par
     * V3 avec un index dessus — et c'est la dangereuse que le code employait.
     *
     * <p>Voir {@code EmpreinteDeSecret} dans platform-commons pour le calcul,
     * et la migration V9 pour la raison.
     */
    @Column("refresh_token_hash")
    private String refreshTokenHash;

    @Column("refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Column("email_verification_token")
    private String emailVerificationToken;

    @Column("email_otp_code")
    private String emailOtpCode;

    @Column("email_otp_expires_at")
    private LocalDateTime emailOtpExpiresAt;

    @Column("phone_otp_code")
    private String phoneOtpCode;

    @Column("phone_otp_expires_at")
    private LocalDateTime phoneOtpExpiresAt;

    @Column("password_reset_otp_code")
    private String passwordResetOtpCode;

    @Column("password_reset_otp_expires_at")
    private LocalDateTime passwordResetOtpExpiresAt;

    @Column("marketing_consent")
    private Boolean marketingConsent = false;

    @Column("totp_secret")
    private String totpSecret;

    @Column("role")
    private String role = "DEVELOPER"; // Rôle par défaut

    @Column("totp_enabled")
    private Boolean totpEnabled = false;

    // Champs pour la confirmation admin
    @Column("admin_confirmation_pending")
    private Boolean adminConfirmationPending = false;

    @Column("admin_confirmation_token")
    private String adminConfirmationToken;

    @Column("admin_confirmation_expires_at")
    private LocalDateTime adminConfirmationExpiresAt;

    @Column("admin_confirmation_ip")
    private String adminConfirmationIp;

    // Champs pour les tentatives après confirmation (couche critique)
    @Column("post_confirmation_attempts")
    private Integer postConfirmationAttempts = 0;

    @Column("critical_alert_sent")
    private Boolean criticalAlertSent = false;

    @Column("critical_maintenance_token")
    private String criticalMaintenanceToken;

    @Column("critical_maintenance_expires_at")
    private LocalDateTime criticalMaintenanceExpiresAt;

    // Champ pour le recovery secret (mot de passe initial pour récupération d'accès)
    @Column("recovery_secret_hash")
    private String recoverySecretHash;

    // Getters
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public Boolean getEmailVerified() { return emailVerified; }
    public String getPhoneNumber() { return phoneNumber; }
    public Boolean getPhoneVerified() { return phoneVerified; }
    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public Integer getFailedLoginAttempts() { return failedLoginAttempts; }
    public LocalDateTime getAccountLockedUntil() { return accountLockedUntil; }
    public String getPasswordResetToken() { return passwordResetToken; }
    public LocalDateTime getPasswordResetExpiresAt() { return passwordResetExpiresAt; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public LocalDateTime getRefreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public String getEmailVerificationToken() { return emailVerificationToken; }
    public String getEmailOtpCode() { return emailOtpCode; }
    public LocalDateTime getEmailOtpExpiresAt() { return emailOtpExpiresAt; }
    public String getPhoneOtpCode() { return phoneOtpCode; }
    public LocalDateTime getPhoneOtpExpiresAt() { return phoneOtpExpiresAt; }
    public String getPasswordResetOtpCode() { return passwordResetOtpCode; }
    public LocalDateTime getPasswordResetOtpExpiresAt() { return passwordResetOtpExpiresAt; }
    public Boolean getMarketingConsent() { return marketingConsent; }
    public String getTotpSecret() { return totpSecret; }
    public Boolean getTotpEnabled() { return totpEnabled; }
    public String getRole() { return role; }

    // Setters
    public void setUsername(String username) { this.username = username; }
    public void setEmail(String email) { this.email = email; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public void setPhoneVerified(Boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public void setFailedLoginAttempts(Integer failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public void setAccountLockedUntil(LocalDateTime accountLockedUntil) { this.accountLockedUntil = accountLockedUntil; }
    public void setPasswordResetToken(String passwordResetToken) { this.passwordResetToken = passwordResetToken; }
    public void setPasswordResetExpiresAt(LocalDateTime passwordResetExpiresAt) { this.passwordResetExpiresAt = passwordResetExpiresAt; }
    public void setRefreshTokenHash(String refreshTokenHash) { this.refreshTokenHash = refreshTokenHash; }
    public void setRefreshTokenExpiresAt(LocalDateTime refreshTokenExpiresAt) { this.refreshTokenExpiresAt = refreshTokenExpiresAt; }
    public void setEmailVerificationToken(String emailVerificationToken) { this.emailVerificationToken = emailVerificationToken; }
    public void setEmailOtpCode(String emailOtpCode) { this.emailOtpCode = emailOtpCode; }
    public void setEmailOtpExpiresAt(LocalDateTime emailOtpExpiresAt) { this.emailOtpExpiresAt = emailOtpExpiresAt; }
    public void setPhoneOtpCode(String phoneOtpCode) { this.phoneOtpCode = phoneOtpCode; }
    public void setPhoneOtpExpiresAt(LocalDateTime phoneOtpExpiresAt) { this.phoneOtpExpiresAt = phoneOtpExpiresAt; }
    public void setPasswordResetOtpCode(String passwordResetOtpCode) { this.passwordResetOtpCode = passwordResetOtpCode; }
    public void setPasswordResetOtpExpiresAt(LocalDateTime passwordResetOtpExpiresAt) { this.passwordResetOtpExpiresAt = passwordResetOtpExpiresAt; }
    public void setMarketingConsent(Boolean marketingConsent) { this.marketingConsent = marketingConsent; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public void setTotpEnabled(Boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public void setRole(String role) { this.role = role; }

    // Getters/Setters pour la confirmation admin
    public Boolean isAdminConfirmationPending() { return adminConfirmationPending; }
    public void setAdminConfirmationPending(Boolean adminConfirmationPending) { this.adminConfirmationPending = adminConfirmationPending; }
    public String getAdminConfirmationToken() { return adminConfirmationToken; }
    public void setAdminConfirmationToken(String adminConfirmationToken) { this.adminConfirmationToken = adminConfirmationToken; }
    public LocalDateTime getAdminConfirmationExpiresAt() { return adminConfirmationExpiresAt; }
    public void setAdminConfirmationExpiresAt(LocalDateTime adminConfirmationExpiresAt) { this.adminConfirmationExpiresAt = adminConfirmationExpiresAt; }
    public String getAdminConfirmationIp() { return adminConfirmationIp; }
    public void setAdminConfirmationIp(String adminConfirmationIp) { this.adminConfirmationIp = adminConfirmationIp; }

    // Getters/Setters pour les tentatives après confirmation
    public Integer getPostConfirmationAttempts() { return postConfirmationAttempts; }
    public void setPostConfirmationAttempts(Integer postConfirmationAttempts) { this.postConfirmationAttempts = postConfirmationAttempts; }
    public Boolean getCriticalAlertSent() { return criticalAlertSent; }
    public void setCriticalAlertSent(Boolean criticalAlertSent) { this.criticalAlertSent = criticalAlertSent; }
    public String getCriticalMaintenanceToken() { return criticalMaintenanceToken; }
    public void setCriticalMaintenanceToken(String criticalMaintenanceToken) { this.criticalMaintenanceToken = criticalMaintenanceToken; }
    public LocalDateTime getCriticalMaintenanceExpiresAt() { return criticalMaintenanceExpiresAt; }
    public void setCriticalMaintenanceExpiresAt(LocalDateTime criticalMaintenanceExpiresAt) { this.criticalMaintenanceExpiresAt = criticalMaintenanceExpiresAt; }

    // Getters/Setters pour recovery secret
    public String getRecoverySecretHash() { return recoverySecretHash; }
    public void setRecoverySecretHash(String recoverySecretHash) { this.recoverySecretHash = recoverySecretHash; }

    /**
     * Vérifie si le compte est verrouillé
     */
    public boolean isAccountLocked() {
        return accountLockedUntil != null && accountLockedUntil.isAfter(LocalDateTime.now());
    }

    /**
     * Incrémente le nombre de tentatives de connexion échouées
     */
    public void incrementFailedLoginAttempts() {
        this.failedLoginAttempts = (this.failedLoginAttempts == null) ? 1 : this.failedLoginAttempts + 1;
    }

    /**
     * Remet à zéro les tentatives de connexion échouées
     */
    public void resetFailedLoginAttempts() {
        this.failedLoginAttempts = 0;
        this.accountLockedUntil = null;
    }

    /**
     * Verrouille le compte pour une durée donnée
     */
    public void lockAccount(int minutes) {
        this.accountLockedUntil = LocalDateTime.now().plusMinutes(minutes);
    }

    /**
     * Incrémente le nombre de tentatives après confirmation
     */
    public void incrementPostConfirmationAttempts() {
        this.postConfirmationAttempts = (this.postConfirmationAttempts == null) ? 1 : this.postConfirmationAttempts + 1;
    }

    /**
     * Remet à zéro les tentatives après confirmation
     */
    public void resetPostConfirmationAttempts() {
        this.postConfirmationAttempts = 0;
        this.criticalAlertSent = false;
    }
}
