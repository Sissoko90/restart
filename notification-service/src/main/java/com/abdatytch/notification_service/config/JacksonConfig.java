package com.abdatytch.notification_service.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Le {@code ObjectMapper} de ce service.
 *
 * <h2>Pourquoi il faut le declarer, alors que Spring Boot en fournit un</h2>
 *
 * Parce que sous Spring Boot 4,
 * {@code com.fasterxml.jackson.databind.ObjectMapper} — Jackson 2 — n'est plus
 * auto-configure : la plateforme Spring est passee a Jackson 3
 * ({@code tools.jackson}). Le POM racine epingle les deux, et ce depot emploie
 * Jackson 2 dans 38 fichiers.
 *
 * <p>Le contexte echouait donc sur « No qualifying bean of type
 * com.fasterxml.jackson.databind.ObjectMapper ». Meme declaration que
 * {@code auth-service.JacksonConfig}, et pour la meme raison.
 *
 * <h2>Les trois reglages ne sont pas cosmetiques</h2>
 *
 * <ul>
 *   <li>{@code JavaTimeModule} : sans lui, un {@code Instant} ou un
 *       {@code LocalDateTime} n'est pas serialisable.
 *       {@code DemandeDeNotification} porte un {@code Instant} — les demandes
 *       seraient illisibles, et le consommateur les ecarterait toutes comme
 *       malformees.</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS} desactive : une date lisible dans le
 *       message, et non un nombre que personne ne sait interpreter en lisant un
 *       journal Kafka.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} desactive : c'est ce qui permet a un
 *       emetteur d'ajouter un champ a {@code DemandeDeNotification} sans que ce
 *       service refuse d'un coup TOUTES ses demandes. Sans cette tolerance, le
 *       deploiement devrait etre simultane sur onze modules.</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
