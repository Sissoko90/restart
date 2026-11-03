package com.abdatytch.quotas_service.entities;

import com.abdatytch.quotas_service.enums.QuotaTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * Palier commercial d'un utilisateur.
 *
 * <p>Ne stocke que le palier : les limites chiffrées associées vivent dans la
 * configuration ({@code quotas.default|premium|enterprise}), afin qu'une évolution
 * tarifaire ne demande pas de migration de données.
 */
@Table("user_quota")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserQuota {

    @PrimaryKey("user_id")
    private String userId;

    private QuotaTier tier;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * Quota par défaut pour un utilisateur encore inconnu du service.
     */
    public static UserQuota defaultFor(String userId) {
        Instant now = Instant.now();
        return UserQuota.builder()
                .userId(userId)
                .tier(QuotaTier.defaultTier())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
