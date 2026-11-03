package com.abdatytch.user_service.entities;

import com.abdatytch.user_service.enums.UserStatus;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entité User pour R2DBC PostgreSQL
 * Représente un utilisateur dans le système
 */
@Table("users")
public class User extends BaseEntity {
    
    // ==========================================
    // RÉFÉRENCE VERS AUTH-SERVICE
    // ==========================================
    @Column("auth_user_id")
    private String authUserId;
    
    // ==========================================
    // DONNÉES DE PROFIL (source de vérité)
    // ==========================================
    @Column("date_of_birth")
    private LocalDate dateOfBirth;
    
    @Column("profile_picture_url")
    private String profilePictureUrl;
    
    @Column("status")
    private UserStatus status = UserStatus.ACTIVE;
    
    // Note: email, phoneNumber, firstName, lastName, role, lastLogin
    // sont gérés par auth-service (source de vérité pour l'identité)
    
    @Column("blocked_reason")
    private String blockedReason;
    
    @Column("blocked_at")
    private LocalDateTime blockedAt;
    
    @Column("suspended_until")
    private LocalDateTime suspendedUntil;
    
    @Column("deleted_at")
    private LocalDateTime deletedAt;
    
    // Note: Relations OneToOne/ManyToOne ne sont pas supportées en R2DBC
    // Les relations doivent être gérées manuellement via des requêtes séparées
    // private UserProfile profile; // Géré manuellement
    
    // Constructors
    public User() {
        this.status = UserStatus.ACTIVE;
    }
    
    // Getters and Setters
    public String getAuthUserId() { return authUserId; }
    public void setAuthUserId(String authUserId) { this.authUserId = authUserId; }
    
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    
    public String getProfilePictureUrl() { return profilePictureUrl; }
    public void setProfilePictureUrl(String profilePictureUrl) { this.profilePictureUrl = profilePictureUrl; }
    
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    
    public LocalDateTime getBlockedAt() { return blockedAt; }
    public void setBlockedAt(LocalDateTime blockedAt) { this.blockedAt = blockedAt; }
    
    public LocalDateTime getSuspendedUntil() { return suspendedUntil; }
    public void setSuspendedUntil(LocalDateTime suspendedUntil) { this.suspendedUntil = suspendedUntil; }
    
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}
