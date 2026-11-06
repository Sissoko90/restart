package com.abdatytch.notification_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Acces Redis, en chaines de bout en bout.
 *
 * <h2>Pourquoi ce bean est declare, alors qu'il semble auto-configure</h2>
 *
 * Spring Boot auto-configure {@code ReactiveStringRedisTemplate} et un
 * {@code ReactiveRedisTemplate<Object, Object>} nomme
 * {@code reactiveRedisTemplate}. Aucun des deux ne satisfait l'injection de
 * {@code RegistreDesEnvoisSmsRedis}, qui demande le nom
 * {@code reactiveRedisTemplate} ET le type {@code <String, String>} :
 *
 * <pre>
 * No qualifying bean of type 'ReactiveRedisTemplate&lt;String, String&gt;'
 *   available ... &#64;Qualifier("reactiveRedisTemplate")
 * </pre>
 *
 * <p>Le nom et le type doivent concorder tous les deux, d'ou cette declaration
 * explicite — la meme que celle d'auth-service, d'ou le registre SMS arrive.
 *
 * <h2>Pourquoi des chaines et non du JSON</h2>
 *
 * Le registre des envois SMS stocke une correspondance « identifiant rendu par
 * le fournisseur -&gt; numero destinataire ». Un serialiseur JSON de Spring Data
 * ecrit un en-tete de type dans la valeur : la valeur relue porterait le nom
 * d'une classe Java, et serait illisible pour qui inspecte la cle avec
 * {@code redis-cli} pendant un incident d'accuses de reception.
 *
 * <p>Meme serialisation que les quatre autres modules du depot. Pour
 * auth-service, gateway-service et security-service, c'est une <b>obligation</b>
 * : ils lisent les memes cles et doivent les lire de la meme facon. Ici les cles
 * {@code sms:*} n'appartiennent qu'a ce service — la coherence est donc choisie,
 * pas imposee, pour que l'exploitation n'ait pas deux conventions a connaitre.
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
