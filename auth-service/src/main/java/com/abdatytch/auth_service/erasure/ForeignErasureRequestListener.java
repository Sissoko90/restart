package com.abdatytch.auth_service.erasure;

import com.abdatytch.auth_service.entities.ErasureRequest;
import com.abdatytch.auth_service.repository.ErasureRequestRepository;
import com.abdatytch.commons.erasure.ErasureTopics;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**

 * Enregistre au registre les demandes d'effacement qu'auth-service n'a pas émises.
 *
 * <h2>Pourquoi ce consommateur existe</h2>
 *
 * Le registre est tenu par auth-service, mais toutes les demandes n'en
 * proviennent pas : un développeur qui efface son compte depuis le portail voit
 * sa demande publiée par interface-service. Sans ce consommateur, les accusés
 * des services aval arriveraient pour une demande absente du registre, et
 * seraient rejetés par la contrainte de clé étrangère — l'effacement aurait bien
 * lieu, mais on ne pourrait pas le prouver.
 *
 * <p>Le traitement est <b>idempotent</b> : auth-service consomme aussi ses
 * propres demandes, déjà enregistrées avant l'effacement local. Une demande déjà
 * connue est simplement ignorée.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ForeignErasureRequestListener {

    private final Map<String, Object> kafkaConsumerProps;
    private final ObjectMapper objectMapper;
    private final ErasureRequestRepository requestRepository;

    @PostConstruct
    public void startConsuming() {
        ReceiverOptions<String, String> options =
                ReceiverOptions.<String, String>create(kafkaConsumerProps)
                        .subscription(Collections.singleton(ErasureTopics.ERASURE_REQUESTED));

        KafkaReceiver.create(options)
                .receive()
                .flatMap(this::handle)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(5)))
                .onErrorResume(e -> {
                    log.error(" Enregistrement des demandes externes arrêté : {}", e.getMessage());
                    return Mono.empty();
                })
                .subscribe();

        log.info(" Enregistrement des demandes d'effacement externes sur {}",
                ErasureTopics.ERASURE_REQUESTED);
    }

    private Mono<Void> handle(ReceiverRecord<String, String> record) {
        return Mono.fromCallable(() ->
                        objectMapper.readValue(record.value(), UserErasureRequestedEvent.class))
                .flatMap(this::enregistrerSiInconnue)
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .onErrorResume(e -> {
                    log.error(" Demande d'effacement non enregistrée, sera rejouée : {}",
                            e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> enregistrerSiInconnue(UserErasureRequestedEvent event) {
        String requestId = event.requestId().toString();

        return requestRepository.existsById(requestId)
                .flatMap(connue -> {
                    if (Boolean.TRUE.equals(connue)) {
                        // Demande émise par auth-service lui-même : déjà au registre.
                        return Mono.empty();
                    }

                    ErasureRequest request = new ErasureRequest();
                    request.setId(requestId);
                    request.setSubjectId(event.subjectId());
                    request.setReason(event.reason());
                    request.setRequestedAt(LocalDateTime.ofInstant(
                            event.requestedAt(), ZoneId.systemDefault()));
                    request.setRequestedBy(event.subjectId());

                    log.info(" Demande d'effacement externe enregistrée : {} (porteur {})",
                            requestId, event.subjectId());

                    return requestRepository.save(request).then();
                });
    }
}
