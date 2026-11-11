package com.abdatytch.market_service.cache;

import com.abdatytch.market_service.security.PorteeDuCatalogue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le cache de portee, et sa tolerance aux pannes.
 *
 * <h2>Ce qui est verifie ici est une AFFIRMATION de la classe</h2>
 *
 * Sa javadoc dit « une panne de Redis ne REFUSE rien : elle fait recalculer ».
 * Un cache dont la panne casse le service est pire que pas de cache — il ajoute
 * une dependance au chemin critique sans l'annoncer. Ces tests sont ce qui rend
 * l'affirmation verifiable.
 */
@DisplayName("CacheDePortee")
class CacheDePorteeTest {

    private static final String MOI = "u-1";

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valeurs;
    private CacheDePortee cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparer() {
        redis = mock(ReactiveStringRedisTemplate.class);
        valeurs = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        cache = new CacheDePortee(redis, new ObjectMapper(), 120);
    }

    // ==================================================================
    @Nested
    @DisplayName("L'aller-retour")
    class AllerRetour {

        @Test
        @DisplayName("ce qui est ecrit se relit a l'identique")
        void ecrireEtRelire() {
            UUID boutique = UUID.randomUUID();
            var portee = new PorteeDuCatalogue(MOI, "ML", boutique, true, false);

            var ecrit = new java.util.concurrent.atomic.AtomicReference<String>();
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(a -> {
                        ecrit.set(a.getArgument(1));
                        return Mono.just(true);
                    });

            cache.ecrire(portee).block();
            when(valeurs.get(CacheDePortee.PREFIXE + MOI)).thenReturn(Mono.just(ecrit.get()));

            StepVerifier.create(cache.lire(MOI, false))
                    .assertNext(relue -> {
                        assertThat(relue.pays()).isEqualTo("ML");
                        assertThat(relue.boutiqueId()).isEqualTo(boutique);
                        assertThat(relue.professionnel()).isTrue();
                    })
                    .verifyComplete();
        }

        /**
         * Le role d'administrateur n'est PAS mis en cache.
         *
         * <p>Il vient du jeton et change a la reconnexion. Le conserver ferait
         * survivre un pouvoir retire jusqu'a l'expiration de la clef.
         */
        @Test
        @DisplayName("le role d'administrateur vient du JETON, jamais du cache")
        void roleNonMisEnCache() {
            var portee = new PorteeDuCatalogue(MOI, "ML", null, false, true);
            var ecrit = new java.util.concurrent.atomic.AtomicReference<String>();
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(a -> {
                        ecrit.set(a.getArgument(1));
                        return Mono.just(true);
                    });

            cache.ecrire(portee).block();
            assertThat(ecrit.get())
                    .withFailMessage("""
                            Le role d'administrateur a ete serialise.

                            Il vient du jeton et change a la reconnexion : le garder \\
                            ferait survivre un pouvoir retire jusqu'a l'expiration de \\
                            la clef.""")
                    .doesNotContain("administrateur");

            when(valeurs.get(CacheDePortee.PREFIXE + MOI)).thenReturn(Mono.just(ecrit.get()));
            StepVerifier.create(cache.lire(MOI, false))
                    .assertNext(relue -> assertThat(relue.administrateur()).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("une portee SANS boutique se relit sans boutique")
        void sansBoutique() {
            var ecrit = new java.util.concurrent.atomic.AtomicReference<String>();
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(a -> {
                        ecrit.set(a.getArgument(1));
                        return Mono.just(true);
                    });

            cache.ecrire(new PorteeDuCatalogue(MOI, "SN", null, false, false)).block();
            when(valeurs.get(CacheDePortee.PREFIXE + MOI)).thenReturn(Mono.just(ecrit.get()));

            StepVerifier.create(cache.lire(MOI, false))
                    .assertNext(relue -> {
                        assertThat(relue.boutiqueId()).isNull();
                        assertThat(relue.professionnel()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une panne de Redis ne refuse rien")
    class PanneDeRedis {

        @Test
        @DisplayName("une lecture en panne rend VIDE, donc fait recalculer")
        void lectureEnPanne() {
            when(valeurs.get(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("Redis absent")));

            // Rend VIDE, ce qui fait recalculer. Si l'erreur etait propagee,
            // une panne de Redis casserait le catalogue entier : un cache dont
            // la panne casse le service est pire que pas de cache, parce qu'il
            // ajoute une dependance au chemin critique sans l'annoncer.
            StepVerifier.create(cache.lire(MOI, false)).verifyComplete();
        }

        @Test
        @DisplayName("une ecriture en panne se termine normalement")
        void ecritureEnPanne() {
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.error(new IllegalStateException("Redis absent")));

            StepVerifier.create(cache.ecrire(
                            new PorteeDuCatalogue(MOI, "ML", null, false, false)))
                    .verifyComplete();
        }

        @Test
        @DisplayName("une eviction en panne se termine normalement")
        void evictionEnPanne() {
            when(redis.delete(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("Redis absent")));

            StepVerifier.create(cache.evincer(MOI)).verifyComplete();
        }

        /**
         * Une valeur ILLISIBLE vient d'un changement de forme entre versions.
         *
         * <p>On la jette au lieu de refuser : le recalcul rend la bonne valeur,
         * et la prochaine ecriture remplace l'ancienne.
         */
        @Test
        @DisplayName("une entree illisible fait recalculer au lieu de lever")
        void entreeIllisible() {
            when(valeurs.get(anyString())).thenReturn(Mono.just("{ceci n'est pas du json"));

            StepVerifier.create(cache.lire(MOI, false)).verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'eviction")
    class Eviction {

        @Test
        @DisplayName("porte sur la clef prefixee du compte")
        void clefPrefixee() {
            when(redis.delete(anyString())).thenReturn(Mono.just(1L));
            cache.evincer(MOI).block();
            verify(redis).delete(CacheDePortee.PREFIXE + MOI);
        }

        @Test
        @DisplayName("sans identifiant : ne touche pas Redis")
        void sansIdentifiant() {
            StepVerifier.create(cache.evincer(null)).verifyComplete();
            StepVerifier.create(cache.evincer("  ")).verifyComplete();
            verify(redis, org.mockito.Mockito.never()).delete(anyString());
        }
    }
}
