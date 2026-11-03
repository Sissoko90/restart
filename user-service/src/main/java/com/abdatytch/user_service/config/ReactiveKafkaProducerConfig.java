package com.abdatytch.user_service.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Producteur Kafka réactif.
 *
 * <p>user-service ne publiait jusqu'ici aucun événement : il ne faisait que
 * consommer. L'effacement introduit un besoin de publication — l'accusé de
 * traitement, sans lequel auth-service ne peut pas prouver que l'effacement a
 * bien eu lieu ici.
 */
@Configuration
public class ReactiveKafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public KafkaSender<String, String> reactiveKafkaSender() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Les valeurs sont sérialisées en JSON par l'appelant : le sérialiseur
        // de spring-kafka n'est pas disponible ici, et l'ajouter pour un seul
        // usage introduirait une dépendance à maintenir.
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // Un accusé d'effacement perdu fausse le registre de conformité :
        // on exige la confirmation de toutes les répliques.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        return KafkaSender.create(SenderOptions.create(props));
    }

    /**
     * Repli lorsque l'auto-configuration Jackson n'est pas active — c'est le cas
     * du contexte de test allégé. En fonctionnement normal, Spring Boot fournit
     * déjà cet objet et cette déclaration s'efface devant lui.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper erasureObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
