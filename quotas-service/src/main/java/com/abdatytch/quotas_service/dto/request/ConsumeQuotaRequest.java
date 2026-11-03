package com.abdatytch.quotas_service.dto.request;

import com.abdatytch.quotas_service.enums.QuotaResource;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Demande de consommation (ou de libération) d'une ressource soumise à quota.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeQuotaRequest {

    @NotNull(message = "La ressource est obligatoire")
    private QuotaResource resource;

    @Positive(message = "La quantité demandée doit être strictement positive")
    private long amount = 1;
}
