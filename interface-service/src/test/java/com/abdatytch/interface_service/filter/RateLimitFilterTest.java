package com.abdatytch.interface_service.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Limitation de débit d'interface-service.
 *
 * <h2>Le défaut le plus grave : la requête était exécutée DEUX FOIS</h2>
 *
 * L'{@code onErrorResume} final était placé <b>après</b> le {@code flatMap} qui
 * appelle {@code chain.filter(exchange)}. Il rattrapait donc aussi les erreurs
 * venues de l'<b>aval</b> — et y répondait en rappelant
 * {@code chain.filter(exchange)}.
 *
 * <p>Toute erreur levée par un filtre suivant ou par un contrôleur faisait donc
 * exécuter la requête une seconde fois : effets de bord dupliqués — un
 * {@code POST} traité deux fois — sur une réponse déjà partiellement écrite.
 *
 * <p>C'est la portée d'un {@code onErrorResume} qui était fautive, pas sa
 * présence. Le comptage vit désormais dans un {@code Mono} distinct, et le
 * repli ne couvre que lui.
 *
 * <h2>Les deux autres</h2>
 *
 * <ul>
 *   <li>l'expiration n'était posée qu'à la première requête. Si cet appel
 *       échouait, la clé survivait sans expiration : le compteur ne redescendait
 *       plus et l'adresse restait plafonnée <b>définitivement</b> ;</li>
 *   <li>{@code startsWith("/actuator")} — sans même la barre — exemptait tout le
 *       sous-arbre de la limitation, et aussi {@code /actuatorXYZ}.</li>
 * </ul>
 */
@DisplayName("RateLimitFilter - limitation de débit")
class RateLimitFilterTest {

    private static final String IP = "203.0.113.7";

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valeurs;
    private RateLimitFilter filtre;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = Mockito.mock(ReactiveStringRedisTemplate.class);
        valeurs = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(30)));
        when(valeurs.increment(anyString())).thenReturn(Mono.just(1L));

        filtre = new RateLimitFilter(redis);
        ReflectionTestUtils.setField(filtre, "enabled", true);
        ReflectionTestUtils.setField(filtre, "requestsPerMinute", 60);
        ReflectionTestUtils.setField(filtre, "windowSeconds", 60);
    }

    /** Nombre de fois que la suite de la chaîne a été exécutée. */
    private AtomicInteger executions;

    private MockServerWebExchange appel(String chemin, Mono<Void> reponseAval) {
        executions = new AtomicInteger(0);
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get(chemin).header("X-Client-Ip", IP).build());

        filtre.filter(echange, suite -> {
            executions.incrementAndGet();
            return reponseAval;
        }).onErrorResume(e -> Mono.empty()).block();

        return echange;
    }

    private MockServerWebExchange appel(String chemin) {
        return appel(chemin, Mono.empty());
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : la requête n'est exécutée QU'UNE FOIS")
    class ExecutionUnique {

        @Test
        @DisplayName("une erreur venue de l'AVAL ne relance pas la chaîne")
        void erreurAvalNeRelancePas() {
            // CE TEST A TROUVE LE DEFAUT, et c'est l'assertion qui compte.
            //
            // L'ancien onErrorResume englobait chain.filter(exchange). Une
            // erreur d'un filtre suivant ou d'un controleur y tombait, et il
            // repondait en rappelant chain.filter(exchange) : la requete etait
            // traitee DEUX FOIS, avec ses effets de bord.
            appel("/auth/login", Mono.error(new RuntimeException("panne en aval")));

            assertThat(executions.get())
                    .withFailMessage("la suite de la chaîne a été exécutée %d fois : "
                            + "les effets de bord sont dupliqués", executions.get())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("une requête ordinaire n'exécute la chaîne qu'une fois")
        void requeteOrdinaire() {
            appel("/auth/login");

            assertThat(executions.get()).isEqualTo(1);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Décision de plafond")
    class Plafond {

        @Test
        @DisplayName("sous le plafond, la requête passe avec les en-têtes de quota")
        void sousLePlafond() {
            when(valeurs.increment(anyString())).thenReturn(Mono.just(10L));

            var echange = appel("/auth/login");

            assertThat(executions.get()).isEqualTo(1);
            assertThat(echange.getResponse().getHeaders().getFirst("X-RateLimit-Limit"))
                    .isEqualTo("60");
            assertThat(echange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"))
                    .isEqualTo("50");
        }

        @Test
        @DisplayName("au plafond EXACT, la requête passe encore")
        void auPlafondExact() {
            // La borne compte : `count > plafond` et non `>=`. Un decalage d'un
            // cran sur un plafond de 60 est invisible a l'usage mais fausse tout
            // test de charge.
            when(valeurs.increment(anyString())).thenReturn(Mono.just(60L));

            appel("/auth/login");

            assertThat(executions.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("au-delà du plafond, la requête est refusée sans atteindre la suite")
        void auDelaDuPlafond() {
            when(valeurs.increment(anyString())).thenReturn(Mono.just(61L));

            var echange = appel("/auth/login");

            assertThat(executions.get())
                    .withFailMessage("une requête au-delà du plafond atteint le service")
                    .isZero();
            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : expiration de la clé de comptage")
    class Expiration {

        @Test
        @DisplayName("l'expiration est posée à la première requête")
        void poseeALaPremiere() {
            when(valeurs.increment(anyString())).thenReturn(Mono.just(1L));

            appel("/auth/login");

            verify(redis).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("une expiration MANQUANTE est reposée")
        void expirationReparee() {
            // LE DEFAUT. Elle n'etait posee qu'a la premiere requete : si cet
            // appel echouait, la cle survivait sans expiration, le compteur ne
            // redescendait plus, et l'adresse restait plafonnee definitivement a
            // cause d'un incident Redis d'une seconde.
            when(valeurs.increment(anyString())).thenReturn(Mono.just(10L));
            when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(-1)));

            appel("/auth/login");

            verify(redis).expire(anyString(), any(Duration.class));
        }

        @Test
        @DisplayName("une expiration VALIDE n'est pas reposée")
        void expirationConservee() {
            // La reposer a chaque requete ferait glisser la fenetre : un
            // appelant regulier n'atteindrait jamais le plafond.
            when(valeurs.increment(anyString())).thenReturn(Mono.just(10L));

            appel("/auth/login");

            verify(redis, never()).expire(anyString(), any(Duration.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : les sondes exemptées")
    class SondesExemptees {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health", "/actuator/health/readiness",
                "/actuator/info", "/actuator/prometheus"
        })
        @DisplayName("les trois sondes ne sont pas plafonnées")
        void sondesExemptees(String chemin) {
            // Consul interroge health toutes les dix secondes et Prometheus
            // racle prometheus : les plafonner ferait croire le service mort.
            appel(chemin);

            assertThat(executions.get()).isEqualTo(1);
            verify(valeurs, never()).increment(anyString());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/env", "/actuator/loggers", "/actuator/heapdump",
                "/actuatorXYZ", "/actuator"
        })
        @DisplayName("les autres actuators SONT plafonnés")
        void autresActuatorsPlafonnes(String chemin) {
            // LE DEFAUT. `startsWith("/actuator")` — sans meme la barre —
            // exemptait tout cela de la limitation, y compris /actuatorXYZ.
            appel(chemin);

            verify(valeurs)
                    .increment(anyString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Dégradation")
    class Degradation {

        @Test
        @DisplayName("Redis injoignable laisse passer, sans relancer la chaîne")
        void redisInjoignable() {
            // Repli ouvert, assume : plus faible que le compteur local du
            // Gateway, mais au moins il ne couvre plus l'aval.
            when(valeurs.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            appel("/auth/login");

            assertThat(executions.get())
                    .withFailMessage("la chaîne a été exécutée %d fois", executions.get())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("une réponse VIDE de Redis ne perd pas la requête")
        void reponseVide() {
            // Le piege du Mono vide : le flatMap ne se declenche pas, et
            // onErrorResume ne s'applique pas — il n'y a pas d'erreur, juste une
            // absence de valeur. La requete n'etait ni transmise ni repondue.
            when(valeurs.increment(anyString())).thenReturn(Mono.empty());

            appel("/auth/login");

            assertThat(executions.get())
                    .withFailMessage("une réponse vide de Redis perd la requête")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("le filtre désactivé court-circuite tout")
        void desactive() {
            ReflectionTestUtils.setField(filtre, "enabled", false);

            appel("/auth/login");

            assertThat(executions.get()).isEqualTo(1);
            verify(valeurs, never()).increment(anyString());
        }
    }
}
