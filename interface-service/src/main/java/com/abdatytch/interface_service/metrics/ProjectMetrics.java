package com.abdatytch.interface_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Métriques pour l'interface-service
 * Collecte les métriques de création/gestion des projets pour Grafana
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
    private static final String PROJECT_CREATION_TIMER = "interface.project.creation.duration";
    private static final String USER_REGISTRATION_TIMER = "interface.user.registration.duration";
    private static final String DATABASE_QUERY_TIMER = "interface.database.query.duration";

    /**
     * Incrémente le compteur de projets créés
     */
    public void incrementProjectCreated(String userId) {
        Counter.builder(PROJECT_CREATED_COUNTER)
                .description("Nombre total de projets créés")
                .tag("user_id", userId)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Incrémente le compteur de projets supprimés
     */
    public void incrementProjectDeleted(String userId) {
        Counter.builder(PROJECT_DELETED_COUNTER)
                .description("Nombre total de projets supprimés")
                .tag("user_id", userId)
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
    public void incrementUserLogin(String userId) {
        Counter.builder(USER_LOGIN_COUNTER)
                .description("Nombre total de connexions utilisateur")
                .tag("user_id", userId)
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
     * Démarre un timer pour la création de projet
     */
    public Timer.Sample startProjectCreationTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Arrête le timer de création de projet
     */
    public void stopProjectCreationTimer(Timer.Sample sample) {
        sample.stop(Timer.builder(PROJECT_CREATION_TIMER)
                .description("Durée de création d'un projet")
                .register(meterRegistry));
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

    /**
     * Enregistre la durée d'une requête base de données
     */
    public void recordDatabaseQueryTime(String operation, long durationMs) {
        Timer.builder(DATABASE_QUERY_TIMER)
                .description("Durée des requêtes base de données")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(durationMs));
    }
}
