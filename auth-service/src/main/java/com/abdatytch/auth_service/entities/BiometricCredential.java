package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.BiometricType;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entité pour stocker les credentials biométriques
 * Stocke uniquement les clés publiques (WebAuthn/FIDO2)
 * JAMAIS de données biométriques brutes
 */
@Table("biometric_credentials")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiometricCredential extends BaseEntity {
    
    @Column("user_id")
    private String userId;
    @Column("biometric_type")
    private BiometricType biometricType;
    
    @Column("credential_id")
    private String credentialId;
    
    @Column("public_key")
    private String publicKey;
    
    @Column("device_id")
    private String deviceId;
    
    @Column("device_name")
    private String deviceName;
    
    @Column("device_os")
    private String deviceOs;
    
    @Column("is_active")
    @Builder.Default
    private Boolean isActive = true;
    
    @Column("counter")
    @Builder.Default
    private Long counter = 0L;
    
    @Column("last_used_at")
    private LocalDateTime lastUsedAt;
    
    @Column("registered_at")
    private LocalDateTime registeredAt;
    
    @Column("aaguid")
    private String aaguid;
    
    @Column("attestation_format")
    private String attestationFormat;
}
