package com.abdatytch.quotas_service.config;

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
 * Producteur Kafka réactif, introduit pour les accusés d'effacement.
 *
 * <p>quotas-service ne faisait jusqu'ici que consommer. Sans accusé, auth-service
 * ne peut pas savoir si ce service a effacé quoi que ce soit — et le registre de
 * conformité resterait incomplet sans qu'on sache pourquoi.
 */
@Configuration
public class ReactiveKafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Bean
    public KafkaSender<String, String> erasureKafkaSender() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
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
