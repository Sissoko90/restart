package com.abdatytch.user_service.entities;

import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Entité UserProfile pour R2DBC PostgreSQL
 * Stocke les informations de profil étendues de l'utilisateur
 */
@Table("user_profiles")
public class UserProfile extends BaseEntity {
    
    @Column("user_id")
    private UUID userId;
    
    @Column("bio")
    private String bio;
    
    @Column("address")
    private String address;
    
    @Column("city")
    private String city;
    
    @Column("country")
    private String country;
    
    @Column("postal_code")
    private String postalCode;
    
    // Note: En R2DBC, les Map sont stockées en JSONB dans PostgreSQL
    // Spring Data R2DBC convertit automatiquement Map <-> JSONB
    @Column("preferences")
    private Map<String, String> preferences = new HashMap<>();
    
    // Constructors
    public UserProfile() {
        this.preferences = new HashMap<>();
    }
    
    // Getters and Setters
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    
    public Map<String, String> getPreferences() { return preferences; }
    public void setPreferences(Map<String, String> preferences) { this.preferences = preferences; }
}
