package com.abdatytch.media_service.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration des métriques Prometheus
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter documentUploadCounter(MeterRegistry registry) {
        return Counter.builder("documents.uploaded.total")
            .description("Total number of documents uploaded")
            .register(registry);
    }

    @Bean
    public Counter documentApprovedCounter(MeterRegistry registry) {
        return Counter.builder("documents.approved.total")
            .description("Total number of documents approved")
            .register(registry);
    }

    @Bean
    public Counter documentRejectedCounter(MeterRegistry registry) {
        return Counter.builder("documents.rejected.total")
            .description("Total number of documents rejected")
            .register(registry);
    }

    @Bean
    public Counter documentDeletedCounter(MeterRegistry registry) {
        return Counter.builder("documents.deleted.total")
            .description("Total number of documents deleted")
            .register(registry);
    }

    // Le bean `securityThreatsCounter` a ete RETIRE.
    //
    // Il produisait une serie SANS etiquette : on comptait les menaces sans
    // jamais savoir lesquelles, alors que c'est la seule chose qui compte quand
    // le compteur monte. L'etiquette est desormais posee par
    // DocumentMetricsServiceImpl, qui construit le compteur par type.
    //
    // Les deux ne peuvent pas coexister : Micrometer exige des cles
    // d'etiquettes coherentes pour un meme nom de metrique, et melanger une
    // serie nue avec des series etiquetees leve une exception.
    //
    // L'etiquette est sure ici parce que SecurityThreatType est BORNE a dix
    // valeurs — a l'inverse du code de type de document, desormais dynamique,
    // que DocumentMetricsService explique ne pas etiqueter.

    /**
     * Durée d'envoi, <b>avec ses buckets</b>.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * Un {@code Timer} Micrometer ne publie par défaut que {@code _count},
     * {@code _sum} et {@code _max}. Or le tableau de bord interroge
     * {@code documents_upload_duration_bucket} par {@code histogram_quantile} :
     * ses deux panneaux P95 et P50 étaient donc <b>vides depuis toujours</b>,
     * faute de séries à agréger.
     *
     * <p>{@code publishPercentileHistogram} publie les buckets. Le coût est une
     * série par bucket, borné et standard — c'est le prix d'un quantile calculé
     * côté Prometheus, et donc agrégeable entre instances, ce qu'un percentile
     * calculé dans la JVM ne permet pas.
     */
    @Bean
    public Timer documentUploadTimer(MeterRegistry registry) {
        return Timer.builder("documents.upload.duration")
            .description("Time taken to upload documents")
            .publishPercentileHistogram()
            .register(registry);
    }

    @Bean
    public Timer documentDownloadTimer(MeterRegistry registry) {
        return Timer.builder("documents.download.duration")
            .description("Time taken to download documents")
            .publishPercentileHistogram()
            .register(registry);
    }
}
