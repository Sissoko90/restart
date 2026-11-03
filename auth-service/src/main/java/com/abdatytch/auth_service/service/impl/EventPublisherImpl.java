package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.service.IEventPublisher;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.event.OAuth2ProfileCompletedEvent;
import com.abdatytch.auth_service.event.UserCreatedEvent;
import com.abdatytch.auth_service.event.UserLoggedInEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Service;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Publication des événements de compte sur {@code user-events}.
 *
 * <h2>Trois types d'événements, et rien pour les distinguer</h2>
 *
 * Ce service publie {@code UserCreatedEvent}, {@code UserLoggedInEvent} et
 * {@code OAuth2ProfileCompletedEvent} sur le <b>même</b> topic, avec la même
 * clef ({@code authUserId}) et sans aucun discriminant. Les trois charges utiles
 * ne diffèrent que par la présence de champs.
 *
 * <p>Côté consommateur, user-service désérialisait donc <b>tout</b> en
 * {@code UserCreatedEvent}. Conséquences, dans l'ordre de gravité :
 *
 * <ul>
 *   <li>la <b>complétion de profil OAuth2</b> n'atteignait jamais user-service :
 *       l'événement était lu comme une création, le compte existait déjà, et le
 *       traitement s'arrêtait là. Numéro, date de naissance, adresse et
 *       biographie renseignés à l'inscription OAuth2 restaient donc absents du
 *       profil ;</li>
 *   <li>un événement de connexion déclenchait le traitement d'une création ;</li>
 *   <li>et si l'ObjectMapper du consommateur refusait les champs inconnus,
 *       chaque événement non-création devenait un échec de désérialisation,
 *       acquitté et perdu.</li>
 * </ul>
 *
 * <h2>Le discriminant est un EN-TÊTE, pas un champ</h2>
 *
 * {@code event-type} est posé en en-tête Kafka plutôt qu'ajouté aux trois
 * charges utiles. C'est le mécanisme prévu pour cela, et il laisse le contrat de
 * données inchangé : un consommateur qui lit déjà ces événements continue de les
 * lire. Un champ dans le corps aurait obligé à modifier les trois classes et
 * tous leurs lecteurs.
 *
 * <h2>Une seule méthode d'envoi</h2>
 *
 * Les trois publications étaient recopiées à l'identique — même construction de
 * {@code ProducerRecord}, même {@code send}, même gestion d'erreur — et seuls le
 * type et le message de journal changeaient. Elles partagent désormais
 * {@link #publier}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventPublisherImpl implements IEventPublisher {

    private static final String USER_EVENTS_TOPIC = ServiceConstants.KAFKA_TOPIC_USER_EVENTS;

    /**
     * Nom de l'en-tête qui porte le type d'événement.
     *
     * <p>La même constante doit valoir côté consommateur. Elle est dupliquée
     * dans user-service, et c'est inévitable : les deux services ne partagent
     * pas de module de contrat d'événements. {@code ContratDesEvenementsTest}
     * (platform-commons) vérifie que les deux valeurs concordent — sans quoi
     * aucun événement ne serait plus reconnu, en silence.
     */
    public static final String EN_TETE_TYPE = "event-type";

    public static final String TYPE_COMPTE_CREE = "user.created";
    public static final String TYPE_CONNEXION = "user.logged-in";
    public static final String TYPE_PROFIL_OAUTH2_COMPLETE = "user.oauth2-profile-completed";

    private final KafkaSender<String, Object> reactiveKafkaSender;

    @Override
    public void publishUserCreated(UserCreatedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        log.info(ServiceConstants.LOG_INFO_PUBLISHING_USER_CREATED, event.getAuthUserId());
        publier(TYPE_COMPTE_CREE, event.getAuthUserId(), event, event.getEventId(),
                ServiceConstants.LOG_INFO_USER_CREATED_PUBLISHED);
    }

    @Override
    public void publishUserLoggedIn(UserLoggedInEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        log.info(ServiceConstants.LOG_INFO_PUBLISHING_USER_LOGGED_IN, event.getAuthUserId());
        publier(TYPE_CONNEXION, event.getAuthUserId(), event, event.getEventId(),
                ServiceConstants.LOG_INFO_USER_LOGGED_IN_PUBLISHED);
    }

    @Override
    public void publishOAuth2ProfileCompleted(OAuth2ProfileCompletedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        log.info("Publication de la completion de profil OAuth2 pour le compte {}",
                event.getAuthUserId());
        publier(TYPE_PROFIL_OAUTH2_COMPLETE, event.getAuthUserId(), event, event.getEventId(),
                "Completion de profil OAuth2 publiee");
    }

    /**
     * Envoi commun aux trois types.
     *
     * <p>La clef est l'{@code authUserId} : tous les événements d'un même compte
     * vont donc dans la même partition, et sont consommés <b>dans l'ordre</b>.
     * C'est ce qui garantit qu'une complétion de profil n'est pas traitée avant
     * la création du compte qu'elle complète.
     *
     * @param type     valeur de l'en-tête {@code event-type}
     * @param cle      clef de partitionnement, l'identifiant du compte
     * @param correlation identifiant porté par le {@code SenderRecord}, pour
     *                    relier l'accusé d'envoi à l'événement
     */
    private void publier(String type, String cle, Object event, String correlation,
                         String messageDeSucces) {
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(USER_EVENTS_TOPIC, cle, event);
        record.headers().add(EN_TETE_TYPE, type.getBytes(StandardCharsets.UTF_8));

        reactiveKafkaSender.send(reactor.core.publisher.Mono.just(
                        SenderRecord.create(record, correlation)))
                .doOnNext(resultat -> log.info(messageDeSucces))
                .doOnError(erreur ->
                        log.error(ServiceConstants.LOG_ERROR_EVENT_PUBLISH_FAILED, erreur))
                .subscribe();
    }
}
