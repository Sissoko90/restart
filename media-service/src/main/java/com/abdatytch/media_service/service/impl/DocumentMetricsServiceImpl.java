package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.service.DocumentMetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Implémentation du service de métriques Prometheus.
 *
 * <p>Voir {@link DocumentMetricsService} pour la raison du retrait du paramètre
 * de type : il était ignoré, et le convertir en étiquette donnerait une
 * cardinalité non bornée depuis que le catalogue vit en base.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentMetricsServiceImpl implements DocumentMetricsService {

    private final Counter documentUploadCounter;
    private final Counter documentApprovedCounter;
    private final Counter documentRejectedCounter;
    private final Counter documentDeletedCounter;
    private final Timer documentUploadTimer;
    private final Timer documentDownloadTimer;

    /** Nécessaire pour construire le compteur de menaces étiqueté par type. */
    private final MeterRegistry registry;

    @Override
    public void incrementUploadCounter() {
        documentUploadCounter.increment();
    }

    @Override
    public void incrementApprovedCounter() {
        documentApprovedCounter.increment();
    }

    @Override
    public void incrementRejectedCounter() {
        documentRejectedCounter.increment();
    }

    @Override
    public void incrementDeletedCounter() {
        documentDeletedCounter.increment();
    }

    /**
     * Compte la menace <b>par type</b>.
     *
     * <h3>Le défaut corrigé</h3>
     *
     * Le paramètre était reçu et <b>ignoré</b>, comme les autres. Mais celui-ci
     * est le seul qui doive être conservé : savoir que des menaces ont été
     * détectées sans savoir lesquelles n'oriente aucune décision. Une signature
     * malware connue et une taille de fichier anormale ne se traitent pas de la
     * même façon.
     *
     * <p>L'étiquette est sûre parce que {@code SecurityThreatType} est
     * <b>borné</b> à dix valeurs. C'est précisément ce qui manque au code de
     * type de document, devenu dynamique, et que ce service explique ne pas
     * étiqueter.
     *
     * <p>{@code Counter.builder(...).register(...)} est idempotent : Micrometer
     * met le compteur en cache par nom et jeu d'étiquettes, donc cet appel ne
     * crée pas une instance par menace détectée.
     */
    @Override
    public void incrementSecurityThreatCounter(String threatType) {
        Counter.builder("documents.security.threats.total")
            .description("Total number of security threats detected")
            .tag("type", threatType != null ? threatType : "INCONNU")
            .register(registry)
            .increment();
    }

    /**
     * Compte le rejet qualite <b>par raison</b>.
     *
     * <p>Deux valeurs possibles, donc cardinalite bornee. Les distinguer est
     * utile : un flot de {@code ILLISIBLE} accuse le client qui envoie, un flot
     * de {@code SCORE_INSUFFISANT} accuse d'abord le seuil.
     */
    @Override
    public void incrementQualityRejectedCounter(String raison) {
        Counter.builder("documents.quality.rejected.total")
            .description("Documents rejected by the quality analysis")
            .tag("raison", raison != null ? raison : "INCONNUE")
            .register(registry)
            .increment();
    }

    @Override
    public void incrementStorageFailureCounter(String operation) {
        Counter.builder("documents.storage.failures.total")
            .description("Failed object storage operations")
            .tag("operation", operation != null ? operation : "INCONNUE")
            .register(registry)
            .increment();
    }

    @Override
    public void incrementEcritureSecondaireEchouee(String etape) {
        Counter.builder("documents.secondary.writes.failed.total")
            .description("Secondary upload writes that failed without failing the upload")
            .tag("etape", etape != null ? etape : "INCONNUE")
            .register(registry)
            .increment();
    }

    @Override
    public void recordUploadDuration(long durationMs) {
        documentUploadTimer.record(Duration.ofMillis(durationMs));
    }

    @Override
    public void recordDownloadDuration(long durationMs) {
        documentDownloadTimer.record(Duration.ofMillis(durationMs));
    }
}
