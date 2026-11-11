package com.abdatytch.setting_service.erasure;

import com.abdatytch.commons.erasure.ErasureCompletedEvent;
import com.abdatytch.commons.erasure.ErasureTopics;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**

 * Traite les demandes d'effacement venues d'auth-service.
 *
 * <p><b>L'accusé n'est émis qu'après l'effacement.</b> L'inverse — accuser puis
 * effacer — donnerait un registre affirmant que tout est fait alors qu'une panne
 * aurait pu interrompre le traitement. Le registre servant de preuve de
 * conformité, il vaut mieux qu'il reste incomplet, et donc visiblement à
 * reprendre, plutôt que faussement rassurant.
 *
 * <p>De même, l'acquittement Kafka n'est posé qu'en cas de succès : un échec
 * fait relivrer l'événement. L'effacement est idempotent — effacer deux fois un
 * profil déjà vide ne change rien.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErasureRequestListener {

    private final Map<String, Object> kafkaConsumerProps;
    private final ObjectMapper objectMapper;
    private final SettingErasureService erasureService;
    private final KafkaSender<String, String> erasureKafkaSender;

    @Value("${spring.application.name:setting-service}")
    private String serviceName;

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
                    log.error("Consommateur d'effacement arrêté : {}", e.getMessage());
                    return Mono.empty();
                })
                .subscribe();

        log.info("Écoute des demandes d'effacement sur {}", ErasureTopics.ERASURE_REQUESTED);
    }

    private Mono<Void> handle(ReceiverRecord<String, String> record) {
        return Mono.fromCallable(() ->
                        objectMapper.readValue(record.value(), UserErasureRequestedEvent.class))
                .flatMap(event -> {
                    log.info("Effacement demandé pour {} (demande {})",
                            event.subjectId(), event.requestId());

                    return erasureService.erase(event.subjectId())
                            .flatMap(erased -> accuser(event, erased))
                            .doOnSuccess(v -> record.receiverOffset().acknowledge());
                })
                .onErrorResume(e -> {
                    // Pas d'acquittement : l'événement sera relivré. Un effacement
                    // manqué en silence est exactement ce qu'il faut éviter.
                    log.error("Effacement non traité, l'événement sera rejoué : {}",
                            e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> accuser(UserErasureRequestedEvent event, int erased) {
        ErasureCompletedEvent ack = ErasureCompletedEvent.of(
                event.requestId(), serviceName, erased,
                "préférences utilisateur");

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(ack))
                .map(json -> new ProducerRecord<String, String>(
                        ErasureTopics.ERASURE_COMPLETED, event.requestId().toString(), json))
                .flatMapMany(record -> erasureKafkaSender
                        .send(Mono.just(SenderRecord.create(record, event.requestId().toString()))))
                .doOnNext(r -> log.info("Accusé d'effacement émis pour la demande {}",
                        event.requestId()))
                .then();
    }
}
