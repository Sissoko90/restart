package com.abdatytch.interface_service.erasure;

import com.abdatytch.commons.erasure.ErasureTopics;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**

 * Publie une demande d'effacement émanant d'un développeur.
 *
 * <p>La demande emprunte la même file que celle des utilisateurs finaux : c'est
 * ce qui permet à quotas-service et setting-service de traiter les deux
 * populations sans distinction. Ces services indexent leurs données sur le sujet
 * du jeton, quel qu'il soit ; un identifiant qui ne leur correspond à rien
 * produit simplement un effacement de zéro enregistrement.
 *
 * <p>La publication est <b>attendue</b> : si Kafka est injoignable, l'appelant
 * doit le savoir plutôt que de confirmer un effacement qui n'aura pas lieu.
 */
@Component
public class DeveloperErasurePublisher {

    private static final Logger log = LoggerFactory.getLogger(DeveloperErasurePublisher.class);

    private final KafkaSender<String, String> erasureKafkaSender;
    private final ObjectMapper objectMapper;

    public DeveloperErasurePublisher(KafkaSender<String, String> erasureKafkaSender,
                                     ObjectMapper objectMapper) {
        this.erasureKafkaSender = erasureKafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(UserErasureRequestedEvent event) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(event))
                .map(json -> new ProducerRecord<>(
                        ErasureTopics.ERASURE_REQUESTED, event.subjectId(), json))
                .flatMapMany(record -> erasureKafkaSender
                        .send(Mono.just(SenderRecord.create(record, event.requestId().toString()))))
                .doOnNext(r -> log.info("Demande d'effacement développeur {} publiée",
                        event.requestId()))
                .doOnError(e -> log.error("Publication impossible pour la demande {} : {}",
                        event.requestId(), e.getMessage()))
                .then();
    }
}
