package com.abdatytch.quotas_service.web;

import com.abdatytch.quotas_service.constant.QuotasConstants;
import com.abdatytch.quotas_service.dto.request.UpdateTierRequest;
import com.abdatytch.quotas_service.dto.response.QuotaStatusResponse;
import com.abdatytch.quotas_service.service.IQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**

 * Administration des quotas : changement de palier, remise à zéro.
 * Réservé au rôle ADMIN.
 */
@RestController
@RequestMapping(QuotasConstants.ADMIN_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Quotas Admin", description = "Administration des paliers et de la consommation")
public class AdminQuotaController {

    private final IQuotaService quotaService;

    @PutMapping("/{userId}/tier")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Changer le palier d'un utilisateur")
    public Mono<QuotaStatusResponse> updateTier(
            @PathVariable String userId,
            @Valid @RequestBody UpdateTierRequest request) {
        return quotaService.updateTier(userId, request.getTier());
    }

    @PostMapping("/{userId}/reset")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remettre à zéro la consommation d'un utilisateur")
    public Mono<QuotaStatusResponse> reset(@PathVariable String userId) {
        log.warn(" Remise à zéro de la consommation demandée pour l'utilisateur {}", userId);
        return quotaService.resetConsumption(userId);
    }
}
