package com.abdatytch.market_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Redis, en chaines.
 *
 * <p>Un {@code ReactiveStringRedisTemplate} et non un gabarit serialise par
 * Jackson : la forme de ce qui est stocke est alors VISIBLE dans le code qui
 * l'ecrit — {@code CacheDePortee} serialise lui-meme son enregistrement — au
 * lieu de dependre d'un serialiseur configure ailleurs. Une entree devenue
 * illisible apres un changement de forme s'y traite explicitement.
 */
@Configuration
public class RedisConfig {

    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory fabrique) {
        return new ReactiveStringRedisTemplate(fabrique);
    }
}
