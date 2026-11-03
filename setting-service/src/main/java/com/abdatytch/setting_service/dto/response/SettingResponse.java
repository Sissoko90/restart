package com.abdatytch.setting_service.dto.response;

import com.abdatytch.setting_service.enums.SettingCategory;
import com.abdatytch.setting_service.enums.SettingType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Un paramètre système et son état courant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettingResponse {

    private String key;

    private String value;

    private SettingType type;

    private SettingCategory category;

    /** Valeur définie en configuration, utilisée tant qu'aucune surcharge n'existe. */
    private String defaultValue;

    /** true si la valeur courante provient d'une surcharge en base. */
    private boolean overridden;

    private String updatedBy;

    private Instant updatedAt;
}
