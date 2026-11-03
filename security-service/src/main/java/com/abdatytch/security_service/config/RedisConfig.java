package com.abdatytch.security_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Acces Redis, en chaines de bout en bout.
 *
 * <h2>Pourquoi tout est serialise en chaines</h2>
 *
 * Les cles publiees ici sont lues par le <b>gateway</b>, qui n'a pas les classes
 * de ce module. Un serialiseur JSON de Spring Data ecrit un en-tete de type dans
 * la valeur : le gateway lirait alors une chaine prefixee du nom d'une classe
 * qu'il ne connait pas, et {@code SISMEMBER "ML"} echouerait sur une valeur
 * pourtant presente.
 *
 * <p>Le contrat entre les deux services est donc du texte nu, et les cles sont
 * declarees dans {@link com.abdatytch.commons.security.ClesRedisSecurite}.
 *
 * <p>Meme serialisation que {@code auth-service} et {@code gateway-service},
 * volontairement : trois services qui lisent les memes cles doivent les lire de
 * la meme façon.
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory fabrique) {

        StringRedisSerializer chaines = new StringRedisSerializer();

        RedisSerializationContext<String, String> contexte =
                RedisSerializationContext.<String, String>newSerializationContext(chaines)
                        .key(chaines)
                        .value(chaines)
                        .hashKey(chaines)
                        .hashValue(chaines)
                        .build();

        return new ReactiveRedisTemplate<>(fabrique, contexte);
    }
}
