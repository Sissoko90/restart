package com.abdatytch.quotas_service.listener;

import com.abdatytch.quotas_service.constant.QuotasConstants;
import com.abdatytch.quotas_service.enums.QuotaResource;
import com.abdatytch.quotas_service.event.ProjectEvent;
import com.abdatytch.quotas_service.service.IQuotaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**

 * Met à jour la consommation de quotas à partir des événements projet.
 *
 * <p>C'est le volet <b>asynchrone</b> du modèle retenu : la vérification, elle, est
 * synchrone (interface-service appelle {@code /quotas/{userId}/check} avant de créer).
 * Le compteur n'est donc incrémenté qu'après création effective du projet.
 *
 * <p>Le service démarre même si Kafka est indisponible : la souscription réessaie
 * en arrière-plan sans bloquer le démarrage.
 */
@Component
@Slf4j
public class ProjectEventListener {

    private final IQuotaService quotaService;
    private final Map<String, Object> kafkaConsumerProps;
    private final ObjectMapper objectMapper;

    /**

     * L'ObjectMapper est récupéré via {@link ObjectProvider} : selon les starters
     * présents, l'auto-configuration Jackson peut ne pas en publier. On retombe
     * alors sur une instance dédiée — les événements consommés ne contiennent que
     * des chaînes, aucun module supplémentaire n'est requis.
     */
    public ProjectEventListener(IQuotaService quotaService,
                                Map<String, Object> kafkaConsumerProps,
                                ObjectProvider<ObjectMapper> objectMapperProvider) {
        this.quotaService = quotaService;
        this.kafkaConsumerProps = kafkaConsumerProps;
        this.objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
    }

    @PostConstruct
    public void startConsuming() {
        log.info("Démarrage du consommateur Kafka pour les topics {} et {}",
                QuotasConstants.TOPIC_PROJECT_CREATED, QuotasConstants.TOPIC_PROJECT_DELETED);

        ReceiverOptions<String, String> options =
                ReceiverOptions.<String, String>create(kafkaConsumerProps)
                        .subscription(Set.of(
                                QuotasConstants.TOPIC_PROJECT_CREATED,
                                QuotasConstants.TOPIC_PROJECT_DELETED));

        KafkaReceiver.create(options)
                .receive()
                .flatMap(this::handleRecord)
                .retryWhen(Retry.backoff(5, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.warn("Reconnexion Kafka, tentative {}",
                                signal.totalRetries() + 1)))
                .onErrorResume(error -> {
                    log.error("Consommateur Kafka arrêté : {}. "
                    + "Les quotas ne seront plus mis à jour par événement.", error.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    private Mono<Void> handleRecord(ReceiverRecord<String, String> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.value(), ProjectEvent.class))
                .flatMap(event -> {
                    log.info(QuotasConstants.LOG_EVENT_RECEIVED, record.topic(), event.getProjectId());

                    if (event.getUserId() == null) {
                        log.warn("Événement {} sans userId (projet {}) - ignoré",
                                record.topic(), event.getProjectId());
                        return Mono.<Void>empty();
                    }

                    Mono<?> action = QuotasConstants.TOPIC_PROJECT_CREATED.equals(record.topic())
                            ? quotaService.consume(event.getUserId(), QuotaResource.PROJECTS, 1)
                            : quotaService.release(event.getUserId(), QuotaResource.PROJECTS, 1);

                    return action.then();
                })
                .doOnSuccess(unused -> record.receiverOffset().acknowledge())
                .onErrorResume(error -> {
                    // Message illisible : on acquitte pour ne pas bloquer la partition.
                    // Erreur de traitement : on n'acquitte pas, l'offset sera rejoué.
                    if (error instanceof com.fasterxml.jackson.core.JacksonException) {
                        log.error("Événement illisible sur {} - acquitté pour ne pas bloquer : {}",
                                record.topic(), error.getMessage());
                        record.receiverOffset().acknowledge();
                    } else {
                        log.error("Échec du traitement de l'événement {} : {}",
                                record.topic(), error.getMessage());
                    }
                    return Mono.empty();
                });
    }
}
