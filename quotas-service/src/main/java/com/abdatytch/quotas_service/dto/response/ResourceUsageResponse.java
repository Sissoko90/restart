package com.abdatytch.quotas_service.dto.response;

import com.abdatytch.quotas_service.enums.QuotaResource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Consommation d'une ressource unique.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceUsageResponse {

    private QuotaResource resource;

    private long used;

    private long limit;

    private long remaining;

    private int usagePercent;

    private boolean warning;

    private boolean exceeded;
}
