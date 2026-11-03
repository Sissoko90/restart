package com.abdatytch.quotas_service.service.impl;

import com.abdatytch.quotas_service.config.QuotaProperties;
import com.abdatytch.quotas_service.constant.QuotasConstants;
import com.abdatytch.quotas_service.dto.response.QuotaCheckResponse;
import com.abdatytch.quotas_service.dto.response.QuotaStatusResponse;
import com.abdatytch.quotas_service.dto.response.ResourceUsageResponse;
import com.abdatytch.quotas_service.entities.UserConsumption;
import com.abdatytch.quotas_service.entities.UserQuota;
import com.abdatytch.quotas_service.enums.QuotaResource;
import com.abdatytch.quotas_service.enums.QuotaTier;
import com.abdatytch.quotas_service.exception.QuotaExceededException;
import com.abdatytch.quotas_service.repositories.UserConsumptionRepository;
import com.abdatytch.quotas_service.repositories.UserQuotaRepository;
import com.abdatytch.quotas_service.service.IQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Implémentation réactive de la gestion des quotas (Cassandra).
 *
 * <p>Note sur la concurrence : Cassandra n'offre pas de transaction multi-lignes.
 * Deux consommations simultanées du même utilisateur peuvent donc, dans une fenêtre
 * très courte, dépasser légèrement la limite. C'est un compromis assumé : la
 * vérification synchrone bloque le cas courant, et la ressource protégée
 * (nombre de projets) tolère un écart transitoire de 1. Pour une garantie stricte,
 * il faudrait des LWT Cassandra (IF ... ) au prix d'un coût de latence important.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotaServiceImpl implements IQuotaService {

    private final UserQuotaRepository quotaRepository;
    private final UserConsumptionRepository consumptionRepository;
    private final QuotaProperties quotaProperties;

    @Override
    public Mono<QuotaStatusResponse> getStatus(String userId) {
        return Mono.zip(loadOrCreateQuota(userId), loadOrCreateConsumption(userId))
                .map(tuple -> toStatus(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<QuotaCheckResponse> check(String userId, QuotaResource resource, long amount) {
        return requirePositive(amount)
                .then(Mono.defer(() -> Mono.zip(loadOrCreateQuota(userId), loadOrCreateConsumption(userId))
                .map(tuple -> {
                    UserConsumption consumption = tuple.getT2();
                    consumption.rollApiWindowIfNeeded();
                    return buildCheck(userId, tuple.getT1().getTier(), resource, consumption, amount);
                })));
    }

    @Override
    public Mono<QuotaCheckResponse> consume(String userId, QuotaResource resource, long amount) {
        return requirePositive(amount)
                .then(Mono.defer(() -> Mono.zip(loadOrCreateQuota(userId), loadOrCreateConsumption(userId))))
                .flatMap(tuple -> {
                    QuotaTier tier = tuple.getT1().getTier();
                    UserConsumption consumption = tuple.getT2();
                    consumption.rollApiWindowIfNeeded();

                    QuotaCheckResponse check = buildCheck(userId, tier, resource, consumption, amount);
                    if (!check.isAllowed()) {
                        log.warn(QuotasConstants.LOG_QUOTA_EXCEEDED,
                                resource, userId, check.getUsed(), check.getLimit(), amount);
                        return Mono.error(new QuotaExceededException(
                                resource, check.getUsed(), check.getLimit(), amount));
                    }

                    setUsed(consumption, resource, currentUsage(consumption, resource) + amount);
                    consumption.setUpdatedAt(Instant.now());

                    return consumptionRepository.save(consumption)
                            .map(saved -> {
                                QuotaCheckResponse result =
                                        buildCheck(userId, tier, resource, saved, 0);
                                log.info(QuotasConstants.LOG_CONSUME, resource, userId, amount,
                                        result.getUsed(), result.getLimit());
                                warnIfThresholdReached(userId, result);
                                return result;
                            });
                });
    }

    @Override
    public Mono<QuotaCheckResponse> release(String userId, QuotaResource resource, long amount) {
        return requirePositive(amount)
                .then(Mono.defer(() -> Mono.zip(loadOrCreateQuota(userId), loadOrCreateConsumption(userId))))
                .flatMap(tuple -> {
                    QuotaTier tier = tuple.getT1().getTier();
                    UserConsumption consumption = tuple.getT2();
                    consumption.rollApiWindowIfNeeded();

                    // Le compteur ne descend jamais sous zéro : un événement de
                    // suppression rejoué ne doit pas créer de crédit fictif.
                    long updated = Math.max(0, currentUsage(consumption, resource) - amount);
                    setUsed(consumption, resource, updated);
                    consumption.setUpdatedAt(Instant.now());

                    return consumptionRepository.save(consumption)
                            .map(saved -> {
                                QuotaCheckResponse result =
                                        buildCheck(userId, tier, resource, saved, 0);
                                log.info(QuotasConstants.LOG_RELEASE, resource, userId, amount,
                                        result.getUsed(), result.getLimit());
                                return result;
                            });
                });
    }

    @Override
    public Mono<QuotaStatusResponse> updateTier(String userId, QuotaTier tier) {
        return loadOrCreateQuota(userId)
                .flatMap(quota -> {
                    QuotaTier previous = quota.getTier();
                    quota.setTier(tier);
                    quota.setUpdatedAt(Instant.now());

                    return quotaRepository.save(quota)
                            .doOnSuccess(saved -> log.info(QuotasConstants.LOG_TIER_UPDATED,
                                    userId, previous, tier));
                })
                .flatMap(quota -> loadOrCreateConsumption(userId)
                        .map(consumption -> toStatus(quota, consumption)));
    }

    @Override
    public Mono<QuotaStatusResponse> resetConsumption(String userId) {
        return loadOrCreateQuota(userId)
                .flatMap(quota -> consumptionRepository.save(UserConsumption.emptyFor(userId))
                        .map(consumption -> toStatus(quota, consumption)));
    }

    // ==========================================================
    // Chargement
    // ==========================================================

    /**
     * Charge le palier de l'utilisateur, ou en crée un au palier par défaut.
     * Un utilisateur inconnu n'est pas une erreur : c'est un nouvel arrivant.
     */
    private Mono<UserQuota> loadOrCreateQuota(String userId) {
        return quotaRepository.findById(userId)
                .switchIfEmpty(Mono.defer(() -> quotaRepository.save(UserQuota.defaultFor(userId))));
    }

    private Mono<UserConsumption> loadOrCreateConsumption(String userId) {
        return consumptionRepository.findById(userId)
                .switchIfEmpty(Mono.defer(() ->
                        consumptionRepository.save(UserConsumption.emptyFor(userId))));
    }

    // ==========================================================
    // Calculs
    // ==========================================================

    private QuotaCheckResponse buildCheck(String userId, QuotaTier tier, QuotaResource resource,
                                          UserConsumption consumption, long requested) {
        long used = currentUsage(consumption, resource);
        long limit = quotaProperties.limitFor(tier, resource);
        long blockAt = Math.round(limit * quotaProperties.getCheck().getBlockThreshold());

        boolean allowed = used + requested <= blockAt;
        long remaining = Math.max(0, blockAt - used - (allowed ? requested : 0));
        int percent = percentOf(used, limit);

        return QuotaCheckResponse.builder()
                .userId(userId)
                .resource(resource)
                .allowed(allowed)
                .used(used)
                .limit(limit)
                .requested(requested)
                .remaining(remaining)
                .usagePercent(percent)
                .warning(percent >= quotaProperties.getCheck().getWarningThreshold() * 100)
                .build();
    }

    private QuotaStatusResponse toStatus(UserQuota quota, UserConsumption consumption) {
        consumption.rollApiWindowIfNeeded();

        List<ResourceUsageResponse> resources = Arrays.stream(QuotaResource.values())
                .map(resource -> {
                    long used = currentUsage(consumption, resource);
                    long limit = quotaProperties.limitFor(quota.getTier(), resource);
                    int percent = percentOf(used, limit);

                    return ResourceUsageResponse.builder()
                            .resource(resource)
                            .used(used)
                            .limit(limit)
                            .remaining(Math.max(0, limit - used))
                            .usagePercent(percent)
                            .warning(percent >= quotaProperties.getCheck().getWarningThreshold() * 100)
                            .exceeded(used >= limit)
                            .build();
                })
                .toList();

        return QuotaStatusResponse.builder()
                .userId(quota.getUserId())
                .tier(quota.getTier())
                .resources(resources)
                .apiCallsWindowStart(consumption.getApiCallsWindowStart())
                .updatedAt(consumption.getUpdatedAt())
                .build();
    }

    private long currentUsage(UserConsumption consumption, QuotaResource resource) {
        return switch (resource) {
            case PROJECTS -> consumption.getProjectsCount();
            case COLLABORATORS -> consumption.getCollaboratorsCount();
            case STORAGE_MB -> consumption.getStorageMbUsed();
            case API_CALLS -> consumption.getApiCallsCount();
        };
    }

    private void setUsed(UserConsumption consumption, QuotaResource resource, long value) {
        switch (resource) {
            case PROJECTS -> consumption.setProjectsCount(value);
            case COLLABORATORS -> consumption.setCollaboratorsCount(value);
            case STORAGE_MB -> consumption.setStorageMbUsed(value);
            case API_CALLS -> consumption.setApiCallsCount(value);
        }
    }

    private int percentOf(long used, long limit) {
        return limit <= 0 ? 100 : (int) Math.min(100, Math.round(used * 100.0 / limit));
    }

    private void warnIfThresholdReached(String userId, QuotaCheckResponse result) {
        if (result.isWarning()) {
            log.warn(QuotasConstants.LOG_WARNING_THRESHOLD,
                    result.getResource(), userId, result.getUsagePercent());
        }
    }

    /**
     * Valide la quantité demandée.
     *
     * <p>Renvoie un {@code Mono.error} plutôt que de lever directement : une API
     * réactive ne doit pas lever à l'assemblage du flux, sinon l'exception échappe
     * à {@code onErrorResume} et à tout traitement d'erreur en aval.
     */
    private Mono<Void> requirePositive(long amount) {
        return amount > 0
                ? Mono.empty()
                : Mono.error(new IllegalArgumentException(QuotasConstants.MSG_AMOUNT_MUST_BE_POSITIVE));
    }
}
