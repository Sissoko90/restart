package com.abdatytch.auth_service.erasure;

import com.abdatytch.commons.erasure.ErasureTopics;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**

 * Publie les demandes d'effacement vers les services aval.
 *
 * <p><b>La publication est attendue, pas lancée en arrière-plan.</b> Les autres
 * publications de ce service se terminent par un {@code .subscribe()} qui rend
 * la main immédiatement : un échec Kafka y passe inaperçu. C'est tolérable pour
 * un événement de connexion ; ça ne l'est pas ici. Une demande d'effacement
 * perdue, c'est une personne qui croit ses données supprimées alors qu'elles
 * subsistent dans cinq services.
 *
 * <p>L'appelant reçoit donc l'erreur et peut refuser la demande plutôt que de
 * confirmer un effacement qui n'aura pas lieu.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErasurePublisher {

    private final KafkaSender<String, Object> reactiveKafkaSender;

    public Mono<Void> publishRequested(UserErasureRequestedEvent event) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(
                ErasureTopics.ERASURE_REQUESTED,
                // La clé est le porteur : toutes les demandes le concernant
                // atterrissent sur la même partition et sont donc traitées
                // dans l'ordre.
                event.subjectId(),
                event);

        return reactiveKafkaSender
                .send(Mono.just(SenderRecord.create(record, event.requestId().toString())))
                .doOnNext(result -> log.info(
                        " Demande d'effacement {} publiée pour le porteur {}",
                        event.requestId(), event.subjectId()))
                .doOnError(e -> log.error(
                        " Publication de la demande d'effacement {} impossible : {}",
                        event.requestId(), e.getMessage()))
                .then();
    }
}
