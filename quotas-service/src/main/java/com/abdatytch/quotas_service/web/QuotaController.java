package com.abdatytch.quotas_service.web;

import com.abdatytch.commons.security.ResourceOwnershipGuard;
import com.abdatytch.quotas_service.exception.QuotaNotFoundException;
import com.abdatytch.quotas_service.constant.QuotasConstants;
import com.abdatytch.quotas_service.dto.request.ConsumeQuotaRequest;
import com.abdatytch.quotas_service.dto.response.QuotaCheckResponse;
import com.abdatytch.quotas_service.dto.response.QuotaStatusResponse;
import com.abdatytch.quotas_service.enums.QuotaResource;
import com.abdatytch.quotas_service.service.IQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * API de quotas consommée par les autres services de la plateforme.
 *
 * <p>Exposée sous {@code /quotas/**} ; le Gateway y route {@code /api/v1/quotas/**}.
 */
@RestController
@RequestMapping(QuotasConstants.BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Quotas", description = "Consultation et consommation des quotas utilisateur")
public class QuotaController {

    private final IQuotaService quotaService;

    /**
     * Refus indistinguable d'une ressource absente.
     *
     * <p>Un 403 confirmerait que le porteur existe : sur une suite
     * d'identifiants, cela permet d'enumerer les comptes sans jamais lire un
     * quota. Le refus est donc traduit en 404, comme une ressource introuvable.
     */
    private Throwable refus(String userId) {
        return new QuotaNotFoundException("Aucun quota pour " + userId);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "État des quotas",
            description = "Palier et consommation de chaque ressource pour l'utilisateur")
    public Mono<QuotaStatusResponse> getStatus(@PathVariable String userId) {
        // L'identifiant vient du chemin : sans cette verification, tout porteur
        // d'un jeton valide lit la consommation de n'importe qui.
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                quotaService.getStatus(userId));
    }

    @GetMapping("/{userId}/check")
    @Operation(summary = "Vérifier un quota",
            description = "Indique si la quantité demandée peut être consommée. Ne modifie aucun compteur.")
    public Mono<QuotaCheckResponse> check(
            @PathVariable String userId,
            @RequestParam QuotaResource resource,
            @RequestParam(defaultValue = "1") long amount) {
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                quotaService.check(userId, resource, amount));
    }

    @PostMapping("/{userId}/consume")
    @Operation(summary = "Consommer un quota",
            description = "Incrémente la consommation. Renvoie 429 si le quota serait dépassé.")
    public Mono<QuotaCheckResponse> consume(
            @PathVariable String userId,
            @Valid @RequestBody ConsumeQuotaRequest request) {
        // Sans verification, un tiers epuisait le quota d'un client payant :
        // un deni de service sur son compte, a son insu.
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                quotaService.consume(userId, request.getResource(), request.getAmount()));
    }

    @PostMapping("/{userId}/release")
    @Operation(summary = "Libérer un quota",
            description = "Décrémente la consommation (suppression d'une ressource). Ne descend jamais sous zéro.")
    public Mono<QuotaCheckResponse> release(
            @PathVariable String userId,
            @Valid @RequestBody ConsumeQuotaRequest request) {
        // Liberer le quota d'autrui corrompt ses compteurs de facturation.
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                quotaService.release(userId, request.getResource(), request.getAmount()));
    }
}
