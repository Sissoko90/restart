package com.abdatytch.notification_service.listener;

import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.commons.notification.SujetsNotification;
import com.abdatytch.notification_service.metrics.NotificationMetrics;
import com.abdatytch.notification_service.service.IServiceDeNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;

import java.util.Map;
import java.util.Set;

/**
 * L'entree principale du service : les demandes publiees par les autres.
 *
 * <h2>Le curseur n'avance QUE si le traitement a abouti</h2>
 *
 * C'est le point de ce consommateur. {@code acknowledge()} est appele dans
 * {@code doOnSuccess}, donc jamais sur le chemin d'erreur : une demande non
 * traitee reste dans la file et sera reprise.
 *
 * <p>Acquitter systematiquement — ce que fait l'acquittement automatique de
 * Kafka — rendrait une panne de base equivalente a une perte definitive : le
 * curseur avancerait sur un message que personne n'a traite.
 *
 * <h2>Un message ILLISIBLE est acquitte, lui</h2>
 *
 * Et c'est delibere. Un JSON malforme ne deviendra jamais lisible : le laisser
 * dans la file bloquerait la partition indefiniment, donc TOUTES les
 * notifications derriere lui. On l'acquitte, on le compte, et on le dit en
 * ERROR.
 *
 * <p>C'est la distinction entre une erreur <b>transitoire</b> (a reprendre) et
 * une erreur <b>definitive</b> (a ecarter). Les confondre donne soit une file
 * bloquee, soit des pertes silencieuses.
 *
 * <h2>Traitement SEQUENTIEL par {@code concatMap}</h2>
 *
 * {@code flatMap} traiterait les demandes en parallele, et deux occurrences du
 * meme message — Kafka garantit « au moins une fois » — pourraient alors
 * franchir le controle d'idempotence en meme temps, avant que l'une n'ait
 * ecrit. La contrainte d'unicite en base rattraperait, mais en levant une
 * erreur sur le chemin normal.
 */
@Component
public class DemandeDeNotificationListener {

    private static final Logger log =
            LoggerFactory.getLogger(DemandeDeNotificationListener.class);

    private final Map<String, Object> proprietes;
    private final IServiceDeNotification service;
    private final ObjectMapper json;
    private final NotificationMetrics metriques;

    public DemandeDeNotificationListener(Map<String, Object> kafkaConsumerProps,
                                         IServiceDeNotification service,
                                         ObjectMapper json,
                                         NotificationMetrics metriques) {
        this.proprietes = kafkaConsumerProps;
        this.service = service;
        this.json = json;
        this.metriques = metriques;
    }

    @PostConstruct
    void ecouter() {
        ReceiverOptions<String, String> options =
                ReceiverOptions.<String, String>create(proprietes)
                        .subscription(Set.of(SujetsNotification.DEMANDE));

        KafkaReceiver.create(options)
                .receive()
                .concatMap(this::traiter)
                // Le flux ne doit JAMAIS s'arreter.
                //
                // Sans ce rattrapage, une seule erreur non prevue terminerait
                // l'abonnement : le service resterait vivant, ses sondes
                // vertes, et plus aucune notification ne serait traitee. C'est
                // le pire etat possible — en panne sans le paraitre.
                .onErrorContinue((erreur, element) ->
                        log.error("Erreur non prevue dans le flux, ecoute maintenue : {}",
                                erreur.getMessage()))
                .subscribe();

        log.info("Ecoute du sujet {} demarree", SujetsNotification.DEMANDE);
    }

    private Mono<Void> traiter(ReceiverRecord<String, String> enregistrement) {
        DemandeDeNotification demande;
        try {
            demande = json.readValue(enregistrement.value(), DemandeDeNotification.class);
        } catch (Exception illisible) {
            // ACQUITTE : ce message ne deviendra jamais lisible, et le garder
            // bloquerait la partition entiere.
            metriques.rejet("message-illisible");
            log.error("Message illisible sur {} (offset {}), ecarte : {}",
                    enregistrement.topic(), enregistrement.offset(),
                    illisible.getMessage());
            enregistrement.receiverOffset().acknowledge();
            return Mono.empty();
        }

        return service.traiter(demande)
                // Acquitte seulement en cas de succes : une demande non traitee
                // reste dans la file.
                .doOnSuccess(compteRendu -> enregistrement.receiverOffset().acknowledge())
                .doOnError(panne -> log.error(
                        "Demande non traitee, elle sera reprise : {}", panne.getMessage()))
                // L'erreur est absorbee pour ne pas interrompre le flux, et le
                // curseur n'a PAS avance : la reprise se fera au prochain
                // reequilibrage ou redemarrage.
                .onErrorResume(panne -> Mono.empty())
                .then();
    }
}
