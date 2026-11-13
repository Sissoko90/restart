package com.abdatytch.interface_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Metriques d'interface-service.
 *
 * <h2>AUCUNE ETIQUETTE NE PORTE L'IDENTIFIANT D'UNE PERSONNE</h2>
 *
 * Trois compteurs portaient {@code .tag("user_id", userId)}. Deux consequences,
 * et la premiere est la plus grave :
 *
 * <ul>
 *   <li>une metrique est conservee longtemps et exportee largement. Y inscrire
 *       qui a cree un projet ou qui s'est connecte en fait un <b>journal
 *       d'activite personnel</b>, hors de toute duree de conservation ;</li>
 *   <li>la CARDINALITE est illimitee : une serie temporelle par compte. Cent
 *       mille comptes font cent mille series pour un seul compteur, et c'est
 *       ainsi qu'un Prometheus tombe.</li>
 * </ul>
 *
 * <p>Le nombre de projets crees se lit sans savoir par qui. Qui l'a fait est
 * une question d'AUDIT, et le journal d'audit existe pour cela — avec sa duree
 * de conservation et son controle d'acces.
 *
 * <h2>Trois methodes ont ete retirees : elles n'avaient aucun appelant</h2>
 *
 * {@code startProjectCreationTimer}, {@code stopProjectCreationTimer} et
 * {@code recordDatabaseQueryTime}. Leurs series n'etaient interrogees par aucun
 * panneau ni aucune alerte. Trouvees par la generalisation de
 * {@code MetriquesDesAlertesTest}, qui ne regardait jusque-la qu'AuthMetrics.
 */
@Component
public class ProjectMetrics {

    private final MeterRegistry meterRegistry;

    public ProjectMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // Compteurs
    private static final String PROJECT_CREATED_COUNTER = "interface.project.created.total";
    private static final String PROJECT_DELETED_COUNTER = "interface.project.deleted.total";
    private static final String USER_REGISTERED_COUNTER = "interface.user.registered.total";
    private static final String USER_LOGIN_COUNTER = "interface.user.login.total";
    private static final String KAFKA_EVENT_SENT_COUNTER = "interface.kafka.event.sent.total";
    private static final String KAFKA_EVENT_FAILED_COUNTER = "interface.kafka.event.failed.total";

    // Timers
    private static final String USER_REGISTRATION_TIMER = "interface.user.registration.duration";

    /**
     * Incrémente le compteur de projets créés
     */
    public void incrementProjectCreated() {
        Counter.builder(PROJECT_CREATED_COUNTER)
                .description("Nombre total de projets créés")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Incrémente le compteur de projets supprimés
     */
    public void incrementProjectDeleted() {
        Counter.builder(PROJECT_DELETED_COUNTER)
                .description("Nombre total de projets supprimés")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Incrémente le compteur d'utilisateurs inscrits
     */
    public void incrementUserRegistered() {
        Counter.builder(USER_REGISTERED_COUNTER)
                .description("Nombre total d'utilisateurs inscrits")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Incrémente le compteur de connexions utilisateur
     */
    public void incrementUserLogin() {
        Counter.builder(USER_LOGIN_COUNTER)
                .description("Nombre total de connexions utilisateur")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Incrémente le compteur d'événements Kafka envoyés
     */
    public void incrementKafkaEventSent(String eventType) {
        Counter.builder(KAFKA_EVENT_SENT_COUNTER)
                .description("Nombre total d'événements Kafka envoyés")
                .tag("event_type", eventType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Incrémente le compteur d'événements Kafka échoués
     */
    public void incrementKafkaEventFailed(String eventType, String errorType) {
        Counter.builder(KAFKA_EVENT_FAILED_COUNTER)
                .description("Nombre total d'événements Kafka échoués")
                .tag("event_type", eventType)
                .tag("error_type", errorType)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Démarre un timer pour l'inscription utilisateur
     */
    public Timer.Sample startUserRegistrationTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Arrête le timer d'inscription utilisateur
     */
    public void stopUserRegistrationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder(USER_REGISTRATION_TIMER)
                .description("Durée d'inscription d'un utilisateur")
                .register(meterRegistry));
    }

}
