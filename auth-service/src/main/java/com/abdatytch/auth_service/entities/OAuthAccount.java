package com.abdatytch.auth_service.entities;

import com.abdatytch.auth_service.enums.OAuthProvider;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Entité représentant un compte OAuth2 lié à un utilisateur
 * Permet à un utilisateur d'avoir plusieurs comptes OAuth (Google, Facebook, etc.)
 */
@Table("oauth_accounts")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAccount extends BaseEntity {

    /** R2DBC ne gère pas les relations : clé étrangère nue vers users.id. */
    @Column("user_id")
    private String userId;

    @Column("provider")
    private OAuthProvider provider;
    
    @Column("provider_user_id")
    private String providerUserId;
    
    @Column("email")
    private String email;
    
    @Column("name")
    private String name;
    
    @Column("picture_url")
    private String pictureUrl;
    
    @Column("access_token")
    private String accessToken;
    
    @Column("refresh_token")
    private String refreshToken;
    
    @Column("token_expires_at")
    private Long tokenExpiresAt;
}
