package com.abdatytch.setting_service.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Nouvelle valeur d'un paramètre. La valeur est transmise en texte et validée
 * contre le type déclaré au catalogue.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateSettingRequest {

    @NotNull(message = "La valeur est obligatoire")
    private String value;
}
