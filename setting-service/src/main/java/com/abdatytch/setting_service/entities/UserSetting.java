package com.abdatytch.setting_service.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Préférence propre à un utilisateur.
 *
 * <p>Contrairement aux paramètres système, les clés ne sont pas contraintes : un
 * client peut y stocker ses préférences (langue, thème, notifications...).
 */
@Table("user_setting")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSetting implements Persistable<String> {

    @Id
    private String id;

    @Column("user_id")
    private String userId;

    @Column("setting_key")
    private String settingKey;

    @Column("setting_value")
    private String settingValue;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public void markNotNew() {
        this.newEntity = false;
    }

    public void generateIdIfMissing() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }
}
