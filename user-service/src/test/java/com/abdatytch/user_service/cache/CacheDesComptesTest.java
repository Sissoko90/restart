package com.abdatytch.user_service.cache;

import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.metrics.UserMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le détenteur unique du cache des comptes.
 *
 * <p>Il existe parce que la convention de clé vivait dans quatre services, et
 * que deux d'entre eux n'invalidaient rien du tout — l'envoi d'une photo de
 * profil, et l'effacement RGPD. Ce fichier verrouille les deux propriétés dont
 * tout le reste dépend : <b>les deux</b> clés sont effacées, et une panne de
 * Redis ne fait échouer aucune écriture déjà aboutie en base.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CacheDesComptes — détenteur unique des clés et de l'invalidation")
class CacheDesComptesTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String AUTH_USER_ID = "auth-user-42";

    @Mock
    private ReactiveRedisTemplate<String, Object> redis;
    @Mock
    private ReactiveValueOperations<String, Object> valeurs;
    @Mock
    private UserMetrics userMetrics;

    @InjectMocks
    private CacheDesComptes cache;

    private UserDetailDTO compte;

    @BeforeEach
    void setUp() {
        compte = new UserDetailDTO(
                ID, AUTH_USER_ID, null, null, null, null, null, null, null,
                UserStatus.ACTIVE, UserRole.USER,
                null, null, null, null, null, null, null, null, null);
        when(redis.opsForValue()).thenReturn(valeurs);
    }

    // ==================================================================
    @Nested
    @DisplayName("Invalidation")
    class Invalidation {

        /**
         * Le défaut que cette classe existe pour fermer.
         *
         * <p>Chaque service invalidait de son côté, et tous n'effaçaient que la
         * clé par identifiant. Celle indexée par {@code authUserId} est celle
         * que sert {@code GET /users/me} — donc la plus consultée — et elle
         * survivait un quart d'heure : un compte fraîchement bloqué continuait
         * de se voir actif.
         */
        @Test
        @DisplayName("les DEUX clés du compte sont effacées")
        void lesDeuxCles() {
            when(redis.delete(any(String[].class))).thenReturn(Mono.just(2L));

            StepVerifier.create(cache.invalider(ID, AUTH_USER_ID)).verifyComplete();

            ArgumentCaptor<String[]> cles = ArgumentCaptor.forClass(String[].class);
            verify(redis).delete(cles.capture());

            assertThat(cles.getValue())
                    .withFailMessage("la cle indexee par authUserId — celle que sert "
                            + "GET /users/me — n'est pas effacee")
                    .containsExactlyInAnyOrder(
                            "user:" + ID, "user:auth_" + AUTH_USER_ID);
        }

        @Test
        @DisplayName("sans authUserId, seule la clé par identifiant est effacée")
        void authUserIdAbsent() {
            when(redis.delete(any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(cache.invalider(ID, null)).verifyComplete();

            ArgumentCaptor<String[]> cles = ArgumentCaptor.forClass(String[].class);
            verify(redis).delete(cles.capture());
            // Mieux que rien, et visible : passer un `null` ici n'efface pas
            // la cle de /users/me, ce qui est une perte a assumer par
            // l'appelant — pas un comportement a decouvrir en production.
            assertThat(cles.getValue()).containsExactly("user:" + ID);
        }

        /**
         * Une panne de Redis ne doit pas faire échouer une mutation aboutie.
         *
         * <p>L'invalidation est le dernier maillon d'une écriture déjà
         * enregistrée en base. Propager son erreur ferait répondre « échec » à
         * un appelant dont la modification a réussi, et l'inciterait à
         * recommencer.
         */
        @Test
        @DisplayName("une panne de Redis n'échoue pas l'invalidation")
        void panneDeRedis() {
            when(redis.delete(any(String[].class)))
                    .thenReturn(Mono.error(new IllegalStateException("Redis injoignable")));

            StepVerifier.create(cache.invalider(ID, AUTH_USER_ID)).verifyComplete();
        }

        @Test
        @DisplayName("invaliderPuisRendre rend la valeur passée")
        void invaliderPuisRendre() {
            when(redis.delete(any(String[].class))).thenReturn(Mono.just(2L));

            StepVerifier.create(cache.invaliderPuisRendre(ID, AUTH_USER_ID, "valeur"))
                    .expectNext("valeur")
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Lecture et écriture")
    class LectureEtEcriture {

        @Test
        @DisplayName("un succès de cache est compté comme tel")
        void succesCompte() {
            when(valeurs.get(anyString())).thenReturn(Mono.just(compte));

            StepVerifier.create(cache.lire("user:" + ID))
                    .expectNext(compte)
                    .verifyComplete();

            verify(userMetrics).lectureServieParLeCache();
        }

        /**
         * L'absence est comptée par {@code switchIfEmpty}, pas {@code doOnSuccess}.
         *
         * <p>{@code doOnSuccess} s'exécute <b>aussi</b> sur une complétion vide,
         * avec {@code null} : il compterait un succès de cache là où il n'y a
         * rien, et le taux affiché serait de 100 % en permanence.
         */
        @Test
        @DisplayName("une absence est comptée comme un manque, et non comme un succès")
        void absenceComptee() {
            when(valeurs.get(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(cache.lire("user:" + ID)).verifyComplete();

            verify(userMetrics).lectureAbsenteDuCache();
            verify(userMetrics, org.mockito.Mockito.never()).lectureServieParLeCache();
        }

        @Test
        @DisplayName("un cache illisible compte un manque et laisse passer")
        void cacheIllisible() {
            when(valeurs.get(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("Redis injoignable")));

            StepVerifier.create(cache.lire("user:" + ID)).verifyComplete();

            verify(userMetrics).lectureAbsenteDuCache();
        }

        @Test
        @DisplayName("l'écriture porte la rétention et rend la valeur")
        void ecriture() {
            when(valeurs.set(anyString(), any(), any(Duration.class)))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(cache.ecrire("user:" + ID, compte))
                    .expectNext(compte)
                    .verifyComplete();

            verify(valeurs).set(eq("user:" + ID), eq(compte), eq(Duration.ofMinutes(15)));
        }

        @Test
        @DisplayName("une écriture ratée ne perd pas la réponse déjà calculée")
        void ecritureRatee() {
            when(valeurs.set(anyString(), any(), any(Duration.class)))
                    .thenReturn(Mono.error(new IllegalStateException("Redis injoignable")));

            StepVerifier.create(cache.ecrire("user:" + ID, compte))
                    .expectNext(compte)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Convention de clé")
    class ConventionDeCle {

        @Test
        @DisplayName("les deux clés sont distinctes et préfixées")
        void deuxClesDistinctes() {
            assertThat(cache.cleParIdentifiant(ID)).isEqualTo("user:" + ID);
            assertThat(cache.cleParAuthUserId(AUTH_USER_ID))
                    .isEqualTo("user:auth_" + AUTH_USER_ID);
            assertThat(cache.cleParIdentifiant(ID))
                    .isNotEqualTo(cache.cleParAuthUserId(AUTH_USER_ID));
        }
    }
}
