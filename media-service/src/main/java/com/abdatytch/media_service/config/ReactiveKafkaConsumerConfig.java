package com.abdatytch.media_service.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Consommateur Kafka réactif.
 *
 * <p>media-service ne faisait jusqu'ici que publier. L'effacement RGPD lui donne
 * une raison de consommer : c'est lui qui détient les pièces d'identité, la
 * donnée la plus sensible de la plateforme, et il ne peut pas l'effacer s'il
 * n'apprend jamais qu'on le lui demande.
 */
@Configuration
public class ReactiveKafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:media-service}")
    private String groupId;

    @Bean
    public Map<String, Object> kafkaConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Acquittement manuel : un effacement ne doit être considéré comme
        // traité qu'une fois réellement exécuté, jamais à la réception.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
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
