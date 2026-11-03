package com.abdatytch.setting_service.entities;

import com.abdatytch.setting_service.enums.SettingCategory;
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

/**
 * Surcharge d'un paramètre système.
 *
 * <p>La base ne contient que les paramètres <b>modifiés</b> : les valeurs par
 * défaut vivent dans {@code config-repo/dev/setting-service-dev.yml}. Une remise à
 * zéro consiste donc à supprimer la ligne, pas à réécrire la valeur d'origine.
 *
 * <p>{@link Persistable} est implémenté parce que la clé primaire est assignée par
 * l'application : sans cela, Spring Data R2DBC émettrait un UPDATE pour une ligne
 * jamais insérée, et le {@code save()} serait silencieusement sans effet.
 */
@Table("system_setting")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemSetting implements Persistable<String> {

    /** Clé pleinement qualifiée, ex. {@code system.maintenance-mode}. */
    @Id
    @Column("setting_key")
    private String settingKey;

    @Column("setting_value")
    private String settingValue;

    private SettingCategory category;

    @Column("updated_by")
    private String updatedBy;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    @Builder.Default
    private boolean newEntity = true;

    @Override
    public String getId() {
        return settingKey;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public void markNotNew() {
        this.newEntity = false;
    }
}
