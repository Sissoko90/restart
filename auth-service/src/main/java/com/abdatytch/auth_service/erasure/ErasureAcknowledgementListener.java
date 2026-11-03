package com.abdatytch.auth_service.erasure;

import com.abdatytch.auth_service.entities.ErasureAcknowledgement;
import com.abdatytch.auth_service.repository.ErasureAcknowledgementRepository;
import com.abdatytch.auth_service.repository.ErasureRequestRepository;
import com.abdatytch.commons.erasure.ErasureCompletedEvent;
import com.abdatytch.commons.erasure.ErasureTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**

 * Recueille les accusés d'effacement des services aval.
 *
 * <p>C'est ce qui transforme une intention en preuve. Sans ce registre,
 * auth-service saurait avoir <i>demandé</i> l'effacement, sans savoir si
 * media-service a réellement supprimé les pièces d'identité. L'article 5.2 exige
 * de pouvoir démontrer la conformité, pas seulement de l'avoir voulue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErasureAcknowledgementListener {

    private final Map<String, Object> kafkaConsumerProps;
    private final ObjectMapper objectMapper;
    private final ErasureAcknowledgementRepository acknowledgementRepository;
    private final ErasureRequestRepository requestRepository;

    /**

     * Services attendus pour considérer une demande comme terminée. La liste est
     * configurable : ajouter un service qui détient des données personnelles sans
     * l'inscrire ici marquerait les demandes comme terminées à tort.
     */
    @Value("${erasure.expected-services:user-service,media-service}")
    private String expectedServices;

    @PostConstruct
    public void startConsuming() {
        ReceiverOptions<String, String> options =
                ReceiverOptions.<String, String>create(kafkaConsumerProps)
                        .subscription(Collections.singleton(ErasureTopics.ERASURE_COMPLETED));

        KafkaReceiver.create(options)
                .receive()
                .flatMap(this::handle)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    log.error(" Consommateur d'accusés d'effacement arrêté : {}", e.getMessage());
                    return Mono.empty();
                })
                .subscribe();

        log.info(" Écoute des accusés d'effacement sur {}", ErasureTopics.ERASURE_COMPLETED);
    }

    private Mono<Void> handle(ReceiverRecord<String, String> record) {
        return Mono.fromCallable(() ->
                        objectMapper.readValue(record.value(), ErasureCompletedEvent.class))
                .flatMap(this::enregistrer)
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .onErrorResume(e -> {
                    // Sans acquittement, l'événement sera relivré : c'est voulu.
                    // Perdre un accusé fausserait le registre dans le sens le plus
                    // dangereux — une demande paraîtrait inachevée alors qu'elle
                    // l'est, ou l'inverse.
                    log.error(" Accusé d'effacement non enregistré : {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> enregistrer(ErasureCompletedEvent event) {
        String requestId = event.requestId().toString();

        return acknowledgementRepository
                .existsByRequestIdAndServiceName(requestId, event.service())
                .flatMap(dejaVu -> {
                    if (Boolean.TRUE.equals(dejaVu)) {
                        // Kafka garantit « au moins une fois » : un doublon est
                        // normal, pas une anomalie.
                        log.debug("Accusé déjà enregistré : {} / {}", requestId, event.service());
                        return Mono.empty();
                    }

                    ErasureAcknowledgement ack = new ErasureAcknowledgement();
                    ack.setId(UUID.randomUUID().toString());
                    ack.setRequestId(requestId);
                    ack.setServiceName(event.service());
                    ack.setCompletedAt(LocalDateTime.ofInstant(
                            event.completedAt(), ZoneId.systemDefault()));
                    ack.setRecordsErased(event.recordsErased());
                    ack.setDetails(event.details());

                    log.info(" {} a effacé {} enregistrement(s) pour la demande {}",
                            event.service(), event.recordsErased(), requestId);

                    return acknowledgementRepository.save(ack)
                            .then(marquerTermineSiComplet(requestId));
                });
    }

    /** Clôt la demande lorsque tous les services attendus ont répondu. */
    private Mono<Void> marquerTermineSiComplet(String requestId) {
        long attendus = expectedServices.isBlank() ? 0
                : expectedServices.split(",").length;

        return acknowledgementRepository.countByRequestId(requestId)
                .filter(recus -> recus >= attendus)
                .flatMap(recus -> requestRepository.findById(requestId))
                .flatMap(request -> {
                    request.setCompletedAt(LocalDateTime.now());
                    log.info(" Demande d'effacement {} terminée — tous les services ont accusé",
                            requestId);
                    return requestRepository.save(request);
                })
                .then();
    }
}
