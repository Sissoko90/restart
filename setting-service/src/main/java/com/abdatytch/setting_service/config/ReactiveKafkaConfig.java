package com.abdatytch.setting_service.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
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
 * Kafka pour setting-service, introduit par l'effacement RGPD.
 *
 * <p>Ce service n'échangeait aucun message : il répondait uniquement à des
 * requêtes. Les préférences utilisateur acceptent cependant des valeurs saisies
 * librement, qui peuvent contenir n'importe quelle donnée personnelle. Les
 * laisser en place après un effacement aurait été un manquement, et la seule
 * raison de le faire aurait été de ne pas ajouter cette dépendance.
 */
@Configuration
public class ReactiveKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:setting-service}")
    private String groupId;

    @Bean
    public Map<String, Object> kafkaConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Acquittement manuel : un effacement n'est traité qu'une fois exécuté.
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }

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
