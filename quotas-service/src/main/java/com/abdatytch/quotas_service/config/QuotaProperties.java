package com.abdatytch.quotas_service.config;

import com.abdatytch.quotas_service.enums.QuotaResource;
import com.abdatytch.quotas_service.enums.QuotaTier;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Limites par palier, lues depuis {@code config-repo/dev/quotas-service-dev.yml}.
 *
 * <p>Les limites ne sont pas stockées en base : une évolution tarifaire est un
 * changement de configuration, pas une migration de données.
 *
 * <pre>
 * quotas:
 *   free:       { storage-mb: 1024,   api-calls-per-hour: 1000,   projects-max: 10,   collaborators-max: 5 }
 *   premium:    { storage-mb: 10240,  api-calls-per-hour: 10000,  projects-max: 100,  collaborators-max: 50 }
 *   enterprise: { storage-mb: 102400, api-calls-per-hour: 100000, projects-max: 1000, collaborators-max: 500 }
 *   check:      { warning-threshold: 0.8, block-threshold: 1.0 }
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "quotas")
@Data
public class QuotaProperties {

    /** Limites du palier FREE (clé `quotas.free`). */
    private TierLimits free = new TierLimits(1024, 1000, 10, 5);

    private TierLimits premium = new TierLimits(10240, 10000, 100, 50);

    private TierLimits enterprise = new TierLimits(102400, 100000, 1000, 500);

    private Check check = new Check();

    /**
     * Retourne les limites du palier demandé.
     */
    public TierLimits forTier(QuotaTier tier) {
        return switch (tier) {
            case PREMIUM -> premium;
            case ENTERPRISE -> enterprise;
            case FREE -> free;
        };
    }

    /**
     * Retourne la limite d'une ressource donnée pour un palier donné.
     */
    public long limitFor(QuotaTier tier, QuotaResource resource) {
        TierLimits limits = forTier(tier);
        return switch (resource) {
            case PROJECTS -> limits.getProjectsMax();
            case COLLABORATORS -> limits.getCollaboratorsMax();
            case STORAGE_MB -> limits.getStorageMb();
            case API_CALLS -> limits.getApiCallsPerHour();
        };
    }

    @Data
    public static class TierLimits {
        private long storageMb;
        private long apiCallsPerHour;
        private long projectsMax;
        private long collaboratorsMax;

        public TierLimits() {
        }

        public TierLimits(long storageMb, long apiCallsPerHour, long projectsMax, long collaboratorsMax) {
            this.storageMb = storageMb;
            this.apiCallsPerHour = apiCallsPerHour;
            this.projectsMax = projectsMax;
            this.collaboratorsMax = collaboratorsMax;
        }
    }

    @Data
    public static class Check {
        /** Fraction du quota à partir de laquelle on émet une alerte (0.8 = 80 %). */
        private double warningThreshold = 0.8;

        /** Fraction du quota à partir de laquelle on bloque (1.0 = 100 %). */
        private double blockThreshold = 1.0;

        private int intervalSeconds = 300;
    }
}
