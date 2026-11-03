package com.abdatytch.auth_service.entities;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;

@Table("authentication_logs")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthenticationLog extends BaseEntity {
    
    @Column("user_id")
    private String userId;
    
    @Column("phone_number")
    private String phoneNumber;
    @Column("event_type")
    private EventType eventType;
    @Column("status")
    private Status status;
    
    @Column("ip_address")
    private String ipAddress;
    
    @Column("user_agent")
    private String userAgent;
    
    // Device Information (détaillé)
    @Column("device_info")
    private String deviceInfo;
    
    @Column("browser_family")
    private String browserFamily;
    
    @Column("browser_version")
    private String browserVersion;
    
    @Column("os_family")
    private String osFamily;
    
    @Column("os_version")
    private String osVersion;
    
    @Column("device_family")
    private String deviceFamily;
    
    @Column("device_type")
    private String deviceType;
    
    // Geolocation Information (détaillé)
    @Column("location")
    private String location;
    
    @Column("country")
    private String country;
    
    @Column("country_code")
    private String countryCode;
    
    @Column("region")
    private String region;
    
    @Column("city")
    private String city;
    
    @Column("latitude")
    private Double latitude;
    
    @Column("longitude")
    private Double longitude;
    
    @Column("timezone")
    private String timezone;
    
    @Column("isp")
    private String isp;
    
    @Column("organization")
    private String organization;
    
    @Column("asn")
    private String asn;
    
    @Column("error_message")
    private String errorMessage;
    
    @Column("timestamp")
    private LocalDateTime timestamp;

    /**
     * Remplace l'ancien {@code @PrePersist} : R2DBC n'a pas ce cycle de vie.
     */
    @Override
    public void prepareForSave() {
        super.prepareForSave();
        if (timestamp == null) {
            timestamp = LocalDateTime.now();
        }
    }
}
