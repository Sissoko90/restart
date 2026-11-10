package com.abdatytch.notification_service.erasure;

import com.abdatytch.commons.erasure.ErasureCompletedEvent;
import com.abdatytch.commons.erasure.ErasureTopics;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Ecoute les demandes d'effacement RGPD, et accuse.
 *
 * <h2>Ce que son absence coutait</h2>
 *
 * notification-service n'ecoutait PAS {@code user.erasure.requested}. Les deux
 * briques de l'effacement etaient pourtant ecrites et documentees —
 * {@code toutesPourEffacement} et {@code Notification.anonymiser()} — et
 * appelees par personne, et la migration V1 annonce que les variables sont
 * tenues separees exactement pour cela.
 *
 * <p>Consequence : une personne qui exerce son droit a l'effacement voyait son
 * compte efface par auth-service, son profil par user-service, ses pieces par
 * media-service — et ses notifications restaient, avec leurs variables, et ses
 * jetons d'appareil restaient utilisables pour lui pousser un message.
 *
 * <p>Pire que l'oubli : auth-service tient le REGISTRE DE PREUVE en agregeant
 * les accuses. Un service muet n'y figure pas, donc la preuve etait incomplete
 * sans que rien ne le signale.
 *
 * <h2>Le meme patron que media-service et user-service, deliberement</h2>
 *
 * Recevoir, effacer, accuser. Trois services participent a la meme preuve et
 * n'ont aucune raison de s'y prendre differemment : une variante serait une
 * chose de plus a verifier le jour ou un accuse manque.
 *
 * <h2>Pourquoi l'accuse part APRES l'effacement, et seulement s'il aboutit</h2>
 *
 * {@code flatMap} enchaine les deux : si l'effacement echoue, l'accuse n'est
 * pas emis et le registre montrera un service en attente. C'est l'etat vrai, et
 * c'est ce qui permet de reprendre. Emettre l'accuse d'abord, ou en parallele,
 * produirait une preuve d'un effacement qui n'a pas eu lieu — exactement le
 * faux succes que ce depot traque ailleurs.
 */
@Component
public class EcouteurDEffacement {

    private static final Logger log = LoggerFactory.getLogger(EcouteurDEffacement.class);

    /**
     * Ce que ce service declare avoir efface, dans l'accuse.
     *
     * <p>Lu par un humain dans le registre de preuve : il doit nommer les
     * donnees, pas les tables.
     */
    private static final String DETAIL =
            "variables des notifications, jetons d'appareil";

    private final Map<String, Object> proprietesDuConsommateur;
    private final ObjectMapper jackson;
    private final ServiceDEffacementDesNotifications effacement;
    private final KafkaSender<String, String> emetteur;

    @Value("${spring.application.name:notification-service}")
    private String nomDuService;

    public EcouteurDEffacement(Map<String, Object> kafkaConsumerProps,
                               ObjectMapper jackson,
                               ServiceDEffacementDesNotifications effacement,
                               KafkaSender<String, String> kafkaSender) {
        this.proprietesDuConsommateur = kafkaConsumerProps;
        this.jackson = jackson;
        this.effacement = effacement;
        this.emetteur = kafkaSender;
    }

    @PostConstruct
    public void commencerAEcouter() {
        ReceiverOptions<String, String> options =
                ReceiverOptions.<String, String>create(proprietesDuConsommateur)
                        .subscription(Collections.singleton(ErasureTopics.ERASURE_REQUESTED));

        KafkaReceiver.create(options)
                .receive()
                .flatMap(this::traiter)
                .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(5)))
                .onErrorResume(panne -> {
                    // Le flux s'arrete, et le journal le dit en ERROR : un
                    // consommateur d'effacement mort en silence laisse des
                    // demandes RGPD sans suite, et le delai legal court.
                    log.error("Consommateur d'effacement arrete : {}", panne.getMessage());
                    return Mono.empty();
                })
                .subscribe();

        log.info("Ecoute des demandes d'effacement sur {}", ErasureTopics.ERASURE_REQUESTED);
    }

    private Mono<Void> traiter(ReceiverRecord<String, String> enregistrement) {
        return Mono.fromCallable(() ->
                        jackson.readValue(enregistrement.value(),
                                UserErasureRequestedEvent.class))
                .flatMap(evenement -> {
                    log.info("Effacement demande pour {} (demande {})",
                            evenement.subjectId(), evenement.requestId());
                    return effacement.effacer(evenement.subjectId())
                            .flatMap(touches -> accuser(evenement, touches));
                })
                .doOnSuccess(rien -> enregistrement.receiverOffset().acknowledge())
                .onErrorResume(panne -> {
                    // Le decalage n'est PAS acquitte : la demande sera relue.
                    // L'effacement est idempotent, donc un second passage ne
                    // coute rien — alors qu'un acquittement sur echec perdrait
                    // la demande sans trace.
                    log.error("Effacement impossible pour cette demande : {}",
                            panne.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> accuser(UserErasureRequestedEvent evenement, int touches) {
        ErasureCompletedEvent accuse = ErasureCompletedEvent.of(
                evenement.requestId(), nomDuService, touches, DETAIL);

        return Mono.fromCallable(() -> jackson.writeValueAsString(accuse))
                .map(json -> new ProducerRecord<String, String>(
                        ErasureTopics.ERASURE_COMPLETED,
                        evenement.requestId().toString(), json))
                .flatMapMany(enregistrement -> emetteur.send(Mono.just(
                        SenderRecord.create(enregistrement,
                                evenement.requestId().toString()))))
                .doOnNext(resultat -> log.info(
                        "Accuse d'effacement emis pour la demande {} ({} enregistrement(s))",
                        evenement.requestId(), touches))
                .then();
    }
}
