package com.abdatytch.user_service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.receiver.ReceiverOptions;

import java.util.HashMap;
import java.util.Map;

/**

 * Configuration Kafka RÉACTIF pour les consumers
 * Utilise les propriétés centralisées depuis application.yml et config-repo
 */
@Configuration
@Slf4j
public class ReactiveKafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${kafka.consumer.resilience.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${kafka.consumer.resilience.enable-auto-commit:false}")
    private boolean enableAutoCommit;

    @Value("${kafka.consumer.resilience.max-poll-records:100}")
    private int maxPollRecords;

    @Value("${kafka.consumer.resilience.session-timeout:30000}")
    private int sessionTimeout;

    @Value("${kafka.consumer.resilience.max-poll-interval:300000}")
    private int maxPollInterval;

    @Value("${kafka.consumer.resilience.heartbeat-interval:10000}")
    private int heartbeatInterval;

    @Bean
    public Map<String, Object> kafkaConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeout);
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollInterval);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatInterval);

        log.info("Kafka consumer configured:");
        log.info("- Bootstrap: {}", bootstrapServers);
        log.info("- Group ID: {}", groupId);
        log.info("- Auto offset reset: {}", autoOffsetReset);
        log.info("- Max poll records: {}", maxPollRecords);
        log.info("- Session timeout: {}ms", sessionTimeout);

        return props;
    }

    @Bean
    public ObjectMapper kafkaObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public <K, V> ReceiverOptions<K, V> kafkaReceiverOptions() {
        return ReceiverOptions.create(kafkaConsumerProps());
    }
}
