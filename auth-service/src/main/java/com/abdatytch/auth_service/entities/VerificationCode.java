package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.VerificationCodeType;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Table("verification_codes")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerificationCode extends BaseEntity {
    
    @Column("phone_number")
    private String phoneNumber;
    
    @Column("email")
    private String email;
    
    @Column("code")
    private String code;
    
    // Données temporaires pour l'enregistrement
    @Column("first_name")
    private String firstName;
    
    @Column("last_name")
    private String lastName;
    
    @Column("country_code")
    private String countryCode;
    @Column("type")
    private VerificationCodeType type;
    
    @Column("is_used")
    @Builder.Default
    private Boolean isUsed = false;
    
    @Column("attempts")
    @Builder.Default
    private Integer attempts = 0;
    
    @Column("expires_at")
    private LocalDateTime expiresAt;
    
    @Column("used_at")
    private LocalDateTime usedAt;
    
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    public boolean isValid() {
        return !isUsed && !isExpired() && attempts < 3;
    }
}
