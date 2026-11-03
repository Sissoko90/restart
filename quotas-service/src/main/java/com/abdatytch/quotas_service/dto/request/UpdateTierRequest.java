package com.abdatytch.quotas_service.dto.request;

import com.abdatytch.quotas_service.enums.QuotaTier;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Changement de palier commercial d'un utilisateur (réservé aux administrateurs).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTierRequest {

    @NotNull(message = "Le palier est obligatoire")
    private QuotaTier tier;
}
