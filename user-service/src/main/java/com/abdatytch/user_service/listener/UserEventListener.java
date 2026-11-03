package com.abdatytch.user_service.listener;

import com.abdatytch.user_service.constants.ListenerConstants;
import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.event.OAuth2ProfileCompletedEvent;
import com.abdatytch.user_service.event.UserCreatedEvent;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.service.UserProfileSyncService;
import com.abdatytch.user_service.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.receiver.ReceiverRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * Consommateur des événements de compte publiés par auth-service.
 *
 * <h2>Le topic porte TROIS types d'événements</h2>
 *
 * auth-service publie {@code user.created}, {@code user.logged-in} et
 * {@code user.oauth2-profile-completed} sur {@code user-events}, avec la même
 * clef. Ce listener désérialisait <b>tout</b> en {@code UserCreatedEvent} et
 * appelait le traitement de création.
 *
 * <p>La conséquence la plus coûteuse portait sur l'inscription OAuth2 : la
 * complétion de profil était lue comme une création, le compte existait déjà, et
 * le traitement s'arrêtait là. Numéro, date de naissance, adresse et biographie
 * saisis par la personne juste après son inscription Google ou Facebook
 * n'arrivaient <b>jamais</b> dans son profil.
 *
 * <p>Le type est désormais lu dans l'en-tête {@code event-type}. Un en-tête
 * plutôt qu'un champ : c'est le mécanisme prévu par Kafka, et le contrat de
 * données reste inchangé.
 *
 * <h2>L'enregistrement du compte à sa création</h2>
 *
 * C'est la raison d'être de ce listener : à la création d'un compte,
 * auth-service publie son identifiant, et ce service crée la ligne locale et son
 * profil. L'idempotence est assurée par {@code existsByAuthUserId} dans
 * {@code UserService.createUser} <b>et</b> par la contrainte {@code UNIQUE} sur
 * {@code auth_user_id} : un événement rejoué ne crée pas de doublon, même si
 * deux instances du service le traitent en même temps.
 *
 * <h2>Acquittement</h2>
 *
 * Un événement traité est acquitté. Un échec de <b>traitement</b> ne l'est pas,
 * pour que Kafka le repropose. Un message <b>illisible</b> l'est quand même :
 * le refuser indéfiniment bloquerait la partition sur un message qui ne passera
 * jamais, et le compteur d'échecs le signale.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserEventListener {

    /**
     * Nom de l'en-tête portant le type d'événement.
     *
     * <p>Doit valoir exactement ce que pose {@code EventPublisherImpl} dans
     * auth-service. Les deux services ne partagent pas de module de contrat
     * d'événements, donc la constante est dupliquée — et
     * {@code ContratDesEvenementsTest} vérifie que les deux concordent. Sans ce
     * garde-fou, renommer l'en-tête d'un côté ferait silencieusement ignorer
     * tous les événements.
     */
    public static final String EN_TETE_TYPE = "event-type";

    public static final String TYPE_COMPTE_CREE = "user.created";
    public static final String TYPE_CONNEXION = "user.logged-in";
    public static final String TYPE_PROFIL_OAUTH2_COMPLETE = "user.oauth2-profile-completed";

    private final UserService userService;
    private final UserProfileSyncService profileSyncService;
    private final Map<String, Object> kafkaConsumerProps;
    private final ObjectMapper objectMapper;
    private final UserMetrics userMetrics;

    @PostConstruct
    public void startConsuming() {
        log.info("Demarrage du consommateur Kafka sur le topic {}",
                ListenerConstants.TOPIC_USER_EVENTS);

        ReceiverOptions<String, String> options =
                ReceiverOptions.<String, String>create(kafkaConsumerProps)
                        .subscription(Collections.singleton(ListenerConstants.TOPIC_USER_EVENTS));

        KafkaReceiver.create(options)
                .receive()
                .flatMap(this::traiter)
                .doOnError(erreur -> log.error("Erreur du consommateur Kafka : {}",
                        erreur.getMessage()))
                .retryWhen(reactor.util.retry.Retry.fixedDelay(3, Duration.ofSeconds(5))
                        .doBeforeRetry(signal -> log.warn(
                                "Nouvelle tentative de connexion Kafka, essai {}",
                                signal.totalRetries() + 1))
                        .onRetryExhaustedThrow((spec, signal) -> {
                            log.error("Connexion Kafka impossible apres {} tentatives : "
                                    + "le consommateur ne sera pas disponible",
                                    signal.totalRetries());
                            return signal.failure();
                        }))
                .onErrorResume(erreur -> {
                    log.error("Le consommateur Kafka n'a pas demarre ({}). "
                            + "Le service continue sans lui.", erreur.getMessage());
                    return Mono.empty();
                })
                .subscribe();

        log.info("Abonnement Kafka initie");
    }

    /**
     * Aiguille un message vers le traitement de son type.
     *
     * <p>Un type inconnu est <b>acquitté sans erreur</b> : il appartient
     * probablement à un consommateur ajouté depuis, et le refuser bloquerait la
     * partition. Il est journalisé en DEBUG, pas en WARN — un topic partagé
     * porte normalement des messages qui ne nous concernent pas.
     */
    private Mono<Void> traiter(ReceiverRecord<String, String> record) {
        String type = typeDe(record);

        return aiguiller(type, record.value())
                .doOnSuccess(rien -> {
                    record.receiverOffset().acknowledge();
                    userMetrics.evenementKafkaTraite();
                })
                .onErrorResume(erreur -> {
                    if (erreur instanceof EvenementIllisible) {
                        // Illisible : acquitte malgre tout, sinon la partition
                        // reste bloquee sur un message qui ne passera jamais.
                        log.error("Evenement illisible, acquitte et abandonne : {}",
                                erreur.getMessage());
                        record.receiverOffset().acknowledge();
                    } else {
                        // Echec de TRAITEMENT : pas d'acquittement, Kafka
                        // represente le message.
                        log.error(ListenerConstants.LOG_ERROR_EVENT_PROCESSING, type, erreur);
                    }
                    userMetrics.evenementKafkaEnEchec();
                    return Mono.empty();
                });
    }

    /**
     * Aiguille une charge utile vers le traitement de son type.
     *
     * <h3>Pourquoi cette méthode est séparée de {@link #traiter}</h3>
     *
     * Deux responsabilités vivaient ensemble : décider quoi faire d'un couple
     * (type, charge utile), et la plomberie Kafka — acquitter l'offset, compter
     * l'événement. La seconde exige un {@code ReceiverRecord}, qu'on ne
     * construit pas sans courtier ; la première est du métier pur.
     *
     * <p>Les garder ensemble rendait l'aiguillage <b>intestable</b>, et il ne
     * l'était pas : l'ancien {@code UserEventListenerTest} appelait le service
     * simulé et vérifiait que le simulacre avait été appelé. Deux assertions qui
     * n'auraient pas échoué si ce listener n'avait pas existé.
     *
     * <p>Visibilité de paquet, et non privée, pour cette seule raison.
     *
     * @param type valeur de l'en-tête {@code event-type} ; {@code null} si absent
     */
    Mono<Void> aiguiller(String type, String charge) {
        // `Mono.defer` : `lire(...)` leve une EvenementIllisible, et une
        // exception levee a l'ASSEMBLAGE remonterait a l'appelant au lieu de
        // devenir un signal d'erreur du flux — donc hors de portee du
        // `onErrorResume` qui decide d'acquitter ou non.
        return Mono.defer(() -> switch (type == null ? "" : type) {
            case TYPE_COMPTE_CREE -> compteCree(charge);
            case TYPE_PROFIL_OAUTH2_COMPLETE -> profilOAuth2Complete(charge);
            case TYPE_CONNEXION -> connexion(charge);
            default -> typeNonTraite(type, charge);
        });
    }

    /** Valeur de l'en-tête {@code event-type}, ou {@code null} s'il est absent. */
    private static String typeDe(ReceiverRecord<String, String> record) {
        Header entete = record.headers().lastHeader(EN_TETE_TYPE);
        if (entete == null || entete.value() == null) {
            return null;
        }
        return new String(entete.value(), StandardCharsets.UTF_8);
    }

    /**
     * Création du compte local à partir de l'identifiant publié.
     *
     * <p>C'est le cœur du contrat entre les deux services : auth-service crée
     * l'identité et publie son identifiant, ce service crée la ligne et le
     * profil qui s'y rattachent.
     *
     * <p>Seul {@code authUserId} est repris de l'événement. Le courriel, le
     * numéro, le nom et le rôle y figurent aussi, et sont volontairement
     * <b>ignorés</b> : les recopier ferait un second magasin pour un état
     * qu'auth-service détient, qui divergerait à la première modification. Ils
     * sont lus à la demande par {@code UserMapper}.
     */
    private Mono<Void> compteCree(String charge) {
        UserCreatedEvent evenement = lire(charge, UserCreatedEvent.class);
        log.info(ListenerConstants.LOG_INFO_USER_CREATED_EVENT, evenement.getAuthUserId());

        if (evenement.getAuthUserId() == null || evenement.getAuthUserId().isBlank()) {
            // Sans identifiant, il n'y a rien a enregistrer. C'est illisible au
            // sens metier : le rejouer ne changera rien.
            throw new EvenementIllisible("evenement de creation sans authUserId");
        }

        return userService.getUserByAuthUserId(evenement.getAuthUserId())
                .flatMap(existant -> {
                    log.info(ListenerConstants.LOG_INFO_USER_ALREADY_EXISTS,
                            evenement.getAuthUserId());
                    return Mono.<Void>empty();
                })
                .onErrorResume(ResourceNotFoundException.class, absent ->
                        userService.createUser(UserCreationDTO.pourAuthUserId(
                                        evenement.getAuthUserId()))
                                .doOnSuccess(cree -> log.info(
                                        ListenerConstants.LOG_INFO_PROFILE_CREATED,
                                        evenement.getAuthUserId()))
                                .then());
    }

    /**
     * Complétion de profil après une inscription OAuth2.
     *
     * <p>Le compte peut ne pas encore exister localement si la création et la
     * complétion sont consommées trop vite l'une après l'autre. La clef Kafka
     * étant l'{@code authUserId}, les deux événements vont dans la même
     * partition et sont donc <b>ordonnés</b> : la création précède toujours. Le
     * cas « compte absent » reste traité comme un échec, pour que Kafka
     * repropose plutôt que de perdre le profil.
     */
    private Mono<Void> profilOAuth2Complete(String charge) {
        OAuth2ProfileCompletedEvent evenement =
                lire(charge, OAuth2ProfileCompletedEvent.class);
        log.info("Completion de profil OAuth2 recue pour le compte {}",
                evenement.getAuthUserId());
        return profileSyncService.appliquerCompletionOAuth2(evenement);
    }

    /**
     * Connexion.
     *
     * <p>Rien à enregistrer : la date de dernière connexion appartient à
     * auth-service, qui la détient et la rend dans {@code lastLogin}. L'événement
     * est acquitté pour ne pas s'accumuler.
     *
     * <p>C'est volontairement un traitement vide et non une absence de
     * traitement : la méthode précédente, {@code handleUserLoggedInReactive},
     * était privée et <b>jamais appelée</b>, et appelait un
     * {@code updateLastLogin} qui rendait {@code Mono.empty()}. Trois niveaux
     * pour ne rien faire, dont rien ne disait que c'était voulu.
     */
    private Mono<Void> connexion(String charge) {
        log.debug("Evenement de connexion recu, sans effet local");
        return Mono.empty();
    }

    private Mono<Void> typeNonTraite(String type, String charge) {
        log.debug("Type d'evenement non traite par ce service : {}", type);
        return Mono.empty();
    }

    /** Désérialise, en distinguant l'illisible du reste. */
    private <T> T lire(String charge, Class<T> type) {
        try {
            return objectMapper.readValue(charge, type);
        } catch (Exception erreur) {
            throw new EvenementIllisible(
                    type.getSimpleName() + " : " + erreur.getMessage());
        }
    }

    /**
     * Charge utile qu'aucun rejeu ne rendra lisible.
     *
     * <p>Distinguée d'un échec de traitement, parce que la conduite à tenir
     * diffère : un message illisible est acquitté et abandonné, un traitement en
     * échec est reproposé.
     */
    static class EvenementIllisible extends RuntimeException {
        EvenementIllisible(String message) {
            super(message);
        }
    }
}
