package com.abdatytch.gateway_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Connexions Redis du Gateway.
 *
 * <p>Deux espaces distincts, délibérément :
 * <ul>
 *   <li>la base <b>propre au Gateway</b> ({@code spring.data.redis.database}, 0
 *       par défaut) : compteurs de rate limiting — des données éphémères dont le
 *       Gateway est le seul propriétaire ;</li>
 *   <li>la base de <b>sécurité partagée</b>, écrite par interface-service (la
 *       console d'administration et l'autorité JWT) et seulement lue ici :
 *       révocations de jetons et liste noire d'IP.</li>
 * </ul>
 *
 * <p>La seconde existe parce que ces décisions sont prises ailleurs qu'au
 * Gateway. Auparavant chaque service écrivait dans sa propre base, sous son
 * propre nom de clé : bloquer une IP depuis la console d'administration
 * n'avait aucun effet sur le trafic entrant, et une révocation de jeton non
 * plus. Les deux écrivaient consciencieusement dans le vide.
 */
@Configuration
public class RedisConfig {

    private static RedisSerializationContext<String, String> stringContext() {
        StringRedisSerializer serializer = new StringRedisSerializer();
        return RedisSerializationContext.<String, String>newSerializationContext()
                .key(serializer)
                .value(serializer)
                .hashKey(serializer)
                .hashValue(serializer)
                .build();
    }

    /** Base propre au Gateway : compteurs de rate limiting. */
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisTemplate<>(connectionFactory, stringContext());
    }

    /**
     * Connexion à la base de sécurité partagée.
     *
     * <p>{@code gateway.shared-security.redis-database} doit rester aligné sur le
     * {@code spring.data.redis.database} d'interface-service. Un désalignement ne
     * provoque aucune erreur au démarrage — seulement des listes vides, donc des
     * contrôles qui ne bloquent plus rien. C'est précisément la panne silencieuse
     * qu'il faut surveiller ici.
     */
    @Bean(destroyMethod = "destroy")
    public LettuceConnectionFactory sharedSecurityRedisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${gateway.shared-security.redis-database:3}") int database) {

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        configuration.setDatabase(database);
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }

        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        return factory;
    }

    @Bean
    public ReactiveRedisTemplate<String, String> sharedSecurityRedisTemplate(
            LettuceConnectionFactory sharedSecurityRedisConnectionFactory) {
        return new ReactiveRedisTemplate<>(sharedSecurityRedisConnectionFactory, stringContext());
    }
}
