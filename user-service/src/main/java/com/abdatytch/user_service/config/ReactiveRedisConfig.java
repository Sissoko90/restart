package com.abdatytch.user_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.util.Collection;
import java.util.Map;

/**
 * Configuration Redis réactive pour caching non-bloquant.
 *
 * <h2>Pourquoi le contexte de sérialisation est extrait</h2>
 *
 * Il était construit dans le corps du bean, donc inatteignable autrement qu'en
 * démarrant un contexte Spring avec un Redis joignable. Un test qui veut
 * vérifier qu'une valeur mise en cache se relit correctement devait donc
 * recopier la configuration — et une garde qui recopie ce qu'elle garde ne garde
 * rien : elle resterait verte si la configuration de production changeait.
 *
 * <p>La méthode statique rend la construction exerçable telle quelle. Ce n'est
 * pas une ouverture pour la commodité : c'est la seule façon que la garde porte
 * sur l'objet réel.
 *
 * <h2>Le cache n'a jamais rien retenu</h2>
 *
 * Le sérialiseur employé jusqu'ici, {@code GenericJackson2JsonRedisSerializer},
 * construit son propre mapper Jackson 2 <b>sans module temporel</b>. Or
 * {@code UserDetailDTO} porte {@code dateOfBirth}, {@code createdAt},
 * {@code updatedAt} et {@code lastLoginAt} : <b>aucune</b> valeur réelle ne
 * pouvait donc être encodée.
 *
 * <p>L'échec était invisible, et pour une bonne raison : les deux écritures de
 * cache du service avalent leur erreur en WARN, afin qu'une panne de Redis ne
 * fasse pas échouer une modification déjà enregistrée en base. Le résultat est
 * un cache annoncé à quinze minutes, inopérant depuis l'origine, et pas un seul
 * test rouge — personne n'exerçait le sérialiseur, {@code CacheDesComptesTest}
 * simulant {@code ReactiveRedisTemplate}.
 *
 * <p>Le remplaçant {@code GenericJacksonJsonRedisSerializer} est bâti sur
 * Jackson 3, qui porte le support de {@code java.time} dans son socle. Il n'est
 * pas déprécié, contrairement au précédent, marqué pour suppression depuis
 * spring-data-redis 4.0. Aucune compatibilité ascendante n'est à préserver :
 * les entrées qu'il faudrait relire n'ont jamais pu être écrites.
 */
@Configuration
public class ReactiveRedisConfig {

    /**
     * Ce que l'on accepte de reconstruire depuis un document en cache.
     *
     * <h3>Pourquoi un validateur, et non {@code enableUnsafeDefaultTyping}</h3>
     *
     * L'inscription du type dans le document est indispensable : sans elle, toute
     * valeur se relirait en {@code LinkedHashMap} et le {@code cast} de
     * {@code CacheDesComptes.lire} comme celui de {@code ClientIdentiteAuth}
     * lèverait une {@code ClassCastException} — rattrapée en WARN, donc
     * invisible.
     *
     * <p>Mais une inscription de type est aussi une <b>instruction de
     * construction</b> : à la relecture, Jackson instancie la classe que le
     * document nomme. Sans restriction, quiconque peut écrire dans Redis choisit
     * donc la classe que ce service instanciera. C'est précisément ce que
     * l'option nommée {@code unsafe} autorise, et son nom ne ment pas.
     *
     * <p>La liste ci-dessous est la plus étroite qui suffise :
     * <ul>
     *   <li>nos propres types, pour les DTO mis en cache ;</li>
     *   <li>{@code Map} et {@code Collection}, parce que les champs déclarés par
     *       une interface — {@code Map<String,String> preferences} — portent eux
     *       aussi une inscription de type, celle de l'implémentation concrète
     *       choisie à l'écriture.</li>
     * </ul>
     *
     * <p>Trop étroite, la liste ferait échouer l'écriture <b>en silence</b>, les
     * deux écrivains avalant leur erreur en WARN. C'est pourquoi
     * {@code SerialisationDesValeursTest} exerce ce contexte-ci, et non une
     * recopie.
     */
    private static final PolymorphicTypeValidator TYPES_AUTORISES =
            BasicPolymorphicTypeValidator.builder()
                    .allowIfSubType("com.abdatytch.")
                    .allowIfSubType(Map.class)
                    .allowIfSubType(Collection.class)
                    .build();

    @Bean
    public ReactiveRedisTemplate<String, Object> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveRedisTemplate<>(connectionFactory, contexteDeSerialisation());
    }

    /**
     * L'encodage de toute valeur mise en cache par ce service.
     *
     * <p>Clés en texte brut, pour qu'un {@code redis-cli KEYS} reste lisible ;
     * valeurs en JSON. Le sérialiseur est <b>générique</b> et non typé : une même
     * instance encode {@code UserDetailDTO} comme {@code AuthUserDTO}, et inscrit
     * le type dans le document pour savoir le relire.
     */
    static RedisSerializationContext<String, Object> contexteDeSerialisation() {
        StringRedisSerializer cles = new StringRedisSerializer();
        GenericJacksonJsonRedisSerializer valeurs = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(TYPES_AUTORISES)
                .build();

        return RedisSerializationContext
                .<String, Object>newSerializationContext()
                .key(cles)
                .value(valeurs)
                .hashKey(cles)
                .hashValue(valeurs)
                .build();
    }
}
