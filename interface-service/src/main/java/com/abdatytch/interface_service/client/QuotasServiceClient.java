package com.abdatytch.interface_service.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**

 * Client de quotas-service : vérification synchrone avant création d'une ressource.
 *
 * <p>Le compteur, lui, est incrémenté de façon asynchrone par quotas-service à la
 * réception de {@code project.created} : on ne consomme donc pas ici, on se contente
 * de demander l'autorisation.
 *
 * <p><b>Comportement en cas de panne</b> : si quotas-service est indisponible (circuit
 * ouvert, timeout), on autorise la création (fail-open) plutôt que de bloquer tout le
 * portail développeur. Le compteur se resynchronise ensuite via Kafka. Ce choix est
 * volontaire : dépasser temporairement un quota est moins grave que d'empêcher tous
 * les utilisateurs de travailler.
 */
@Component
public class QuotasServiceClient {

    private static final Logger log = LoggerFactory.getLogger(QuotasServiceClient.class);

    private static final String RESOURCE_PROJECTS = "PROJECTS";

    private final WebClient webClient;

    @Value("${services.quotas-service.url:http://localhost:8089}")
    private String quotasServiceUrl;

    @Value("${services.quotas-service.timeout-ms:2000}")
    private long timeoutMs;

    public QuotasServiceClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**

     * Demande à quotas-service si l'utilisateur peut créer un projet de plus.
     *
     * @return true si autorisé, true également si le service est injoignable (fail-open)
     */
    @CircuitBreaker(name = "quotas-service", fallbackMethod = "allowOnFailure")
    public Mono<Boolean> canCreateProject(UUID userId) {
        return webClient.get()
                .uri(quotasServiceUrl + "/quotas/{userId}/check?resource={resource}&amount=1",
                        userId, RESOURCE_PROJECTS)
                .retrieve()
                .bodyToMono(QuotaCheckResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(response -> {
                    if (!response.isAllowed()) {
                        log.info("Quota projets atteint pour l'utilisateur {} : {}/{}",
                                userId, response.getUsed(), response.getLimit());
                    } else if (response.isWarning()) {
                        log.warn("Utilisateur {} à {}% de son quota projets",
                                userId, response.getUsagePercent());
                    }
                    return response.isAllowed();
                });
    }

    /**

     * Repli invoqué par le circuit breaker : on n'empêche pas de travailler
     * parce que le service de quotas est en panne.
     */
    @SuppressWarnings("unused")
    private Mono<Boolean> allowOnFailure(UUID userId, Throwable error) {
        log.warn("quotas-service injoignable pour l'utilisateur {} ({}) - création autorisée par défaut",
                userId, error.getMessage());
        return Mono.just(true);
    }

    /**

     * Projection de la réponse de {@code GET /quotas/{userId}/check}.
     */
    public static class QuotaCheckResponse {
        private boolean allowed;
        private long used;
        private long limit;
        private long remaining;
        private int usagePercent;
        private boolean warning;

        public boolean isAllowed() { return allowed; }
        public void setAllowed(boolean allowed) { this.allowed = allowed; }

        public long getUsed() { return used; }
        public void setUsed(long used) { this.used = used; }

        public long getLimit() { return limit; }
        public void setLimit(long limit) { this.limit = limit; }

        public long getRemaining() { return remaining; }
        public void setRemaining(long remaining) { this.remaining = remaining; }

        public int getUsagePercent() { return usagePercent; }
        public void setUsagePercent(int usagePercent) { this.usagePercent = usagePercent; }

        public boolean isWarning() { return warning; }
        public void setWarning(boolean warning) { this.warning = warning; }
    }
}
