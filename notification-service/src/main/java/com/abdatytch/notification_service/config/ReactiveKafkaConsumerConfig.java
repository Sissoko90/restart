package com.abdatytch.notification_service.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Proprietes du consommateur Kafka reactif.
 *
 * <h2>Deserialisation en String, puis Jackson — et c'est une decision</h2>
 *
 * Meme forme que quotas-service et user-service. Deux raisons, et la seconde
 * est de securite :
 *
 * <ul>
 *   <li>cela evite de dependre des en-tetes de type que pose le
 *       {@code JsonSerializer} de l'emetteur, donc de coupler les classes
 *       d'evenements entre services. Un emetteur qui renomme son paquet ne
 *       casse pas ce consommateur ;</li>
 *   <li>cela n'active <b>pas</b> le {@code JsonDeserializer} de Spring, et donc
 *       pas {@code spring.json.trusted.packages}. Cette propriete vaut
 *       {@code "*"} dans {@code config-repo/dev/application-dev.yml}, donc
 *       commune a TOUS les services : elle autorise a instancier n'importe
 *       quelle classe du classpath a partir d'un message du bus. Elle est
 *       inerte tant que personne n'emploie ce deserialiseur — et c'est
 *       precisement pourquoi on ne l'emploie pas.</li>
 * </ul>
 *
 * <h2>L'acquittement est MANUEL</h2>
 *
 * {@code ENABLE_AUTO_COMMIT_CONFIG = false}. Avec l'acquittement automatique,
 * Kafka avance le curseur a intervalle fixe, independamment du traitement : une
 * demande de notification serait acquittee avant d'avoir ete remise, et un
 * redemarrage au mauvais moment la perdrait definitivement.
 *
 * <p>Ici le curseur n'avance qu'une fois la notification enregistree. La
 * contrepartie est le doublon — Kafka garantit « au moins une fois » — et c'est
 * la contrainte d'unicite sur {@code demande_id} qui le neutralise.
 */
@Configuration
public class ReactiveKafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String serveurs;

    @Value("${spring.kafka.consumer.group-id:notification-service-group}")
    private String groupe;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String depuis;

    @Bean
    public Map<String, Object> kafkaConsumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, serveurs);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupe);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, depuis);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return props;
    }
}
