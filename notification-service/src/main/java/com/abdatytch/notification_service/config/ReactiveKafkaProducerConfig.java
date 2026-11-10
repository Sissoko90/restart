package com.abdatytch.notification_service.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Le producteur Kafka de ce service, et il n'en a qu'un usage.
 *
 * <h2>Ce service est un CONSOMMATEUR, sauf pour une chose</h2>
 *
 * notification-service recoit des demandes sur le bus et ne publiait rien :
 * c'est ecrit dans sa configuration. Ce producteur existe pour le seul accuse
 * d'effacement RGPD, et c'est deliberement sa seule raison d'etre.
 *
 * <h2>Pourquoi l'accuse ne pouvait pas etre omis</h2>
 *
 * auth-service tient le REGISTRE DE PREUVE de l'effacement : il agrege les
 * accuses de chaque service par {@code ErasureAcknowledgementListener}. Un
 * service qui efface sans accuser apparait dans ce registre comme n'ayant
 * jamais efface — et c'est pire que de ne rien faire, parce que la preuve
 * devient fausse au lieu d'etre absente.
 *
 * <h2>Les trois reglages, et leur raison</h2>
 *
 * <ul>
 *   <li>{@code acks=all} : l'accuse n'est considere emis que lorsque toutes les
 *       repliques l'ont. Un accuse perdu laisserait le registre incomplet, et
 *       personne ne le saurait ;</li>
 *   <li>{@code retries=3} : un hoquet du courtier ne doit pas perdre la
 *       preuve ;</li>
 *   <li>{@code enable.idempotence=true} : avec des reessais, un accuse pourrait
 *       etre ecrit deux fois. Le registre compterait alors deux effacements
 *       pour un.</li>
 * </ul>
 *
 * <p>Memes valeurs que media-service, qui accuse le meme effacement : deux
 * services qui participent a la meme preuve n'ont pas de raison d'avoir des
 * garanties differentes.
 */
@Configuration
public class ReactiveKafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String serveurs;

    @Bean
    public KafkaSender<String, String> kafkaSender() {
        Map<String, Object> proprietes = new HashMap<>();
        proprietes.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, serveurs);
        proprietes.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        proprietes.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        proprietes.put(ProducerConfig.ACKS_CONFIG, "all");
        proprietes.put(ProducerConfig.RETRIES_CONFIG, 3);
        proprietes.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        return KafkaSender.create(SenderOptions.create(proprietes));
    }
}
