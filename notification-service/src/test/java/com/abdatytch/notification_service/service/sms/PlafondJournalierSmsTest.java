package com.abdatytch.notification_service.service.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.abdatytch.notification_service.metrics.NotificationMetrics;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Plafond journalier de dépense SMS")
class PlafondJournalierSmsTest {

    /**
     * Un registre Micrometer REEL, et non un bouchon.
     *
     * <p>Il permet de RELIRE le compteur apres coup, donc de verifier que le
     * refus est bien rendu visible — ce qui est tout l'objet de cette metrique.
     * Un bouchon n'aurait verifie qu'un appel de methode.
     */
    private final SimpleMeterRegistry registre = new SimpleMeterRegistry();

    private NotificationMetrics metriques() {
        return new NotificationMetrics(registre);
    }

    private double refusComptes() {
        var compteur = registre.find("sms.plafond.atteint.total").counter();
        return compteur == null ? 0d : compteur.count();
    }

    @SuppressWarnings("unchecked")
    private static ReactiveRedisTemplate<String, String> redisQuiRend(String valeur) {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valeurs = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(valeurs.get(anyString()))
                .thenReturn(valeur == null ? Mono.empty() : Mono.just(valeur));
        return redis;
    }

    @SuppressWarnings("unchecked")
    private static ReactiveRedisTemplate<String, String> redisEnPanne() {
        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valeurs = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(valeurs.get(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("connexion refusee")));
        return redis;
    }

    @Nested
    @DisplayName("Le plafond")
    class LePlafond {

        @Test
        @DisplayName("sous le plafond, l'envoi est autorisé")
        void sousLePlafond() {
            var plafond = new PlafondJournalierSms(redisQuiRend("499"), metriques(), 500);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("au plafond, l'envoi est refusé")
        void auPlafond() {
            // 500 envois deja faits avec un plafond de 500 : le suivant est
            // refuse. La comparaison est `dejaEnvoyes < plafond`, donc ATTEINDRE
            // le plafond suffit a fermer — et non le depasser.
            var plafond = new PlafondJournalierSms(redisQuiRend("500"), metriques(), 500);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(false)
                    .verifyComplete();

            // Le refus doit etre VISIBLE, et c'est la moitie qui compte.
            //
            // Sans ce compteur, le plafond refuserait des codes de verification
            // en silence : le service repond 200, aucune autre alerte ne se
            // declenche, et le symptome serait des utilisateurs qui ne
            // recoivent rien sans que personne ne sache pourquoi.
            assertThat(refusComptes()).isEqualTo(1d);
        }

        @Test
        @DisplayName("sous le plafond, aucun refus n'est compté")
        void sousLePlafondRienNEstCompte() {
            var plafond = new PlafondJournalierSms(redisQuiRend("10"), metriques(), 500);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(true)
                    .verifyComplete();

            // Le pendant du test precedent : une metrique qui monte sur du
            // trafic normal est une alerte qu'on apprend a ignorer.
            assertThat(refusComptes()).isZero();
        }

        @Test
        @DisplayName("aucun compteur encore écrit vaut zéro envoi")
        void compteurAbsent() {
            // Premier SMS de la journee : la cle n'existe pas. `get` rend un
            // Mono VIDE, et sans defaultIfEmpty la chaine se terminerait sans
            // rien emettre — le flatMap de l'appelant ne s'executerait pas, et
            // aucun SMS ne partirait jamais en debut de journee.
            var plafond = new PlafondJournalierSms(redisQuiRend(null), metriques(), 500);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un compteur illisible est traité comme zéro, pas comme une erreur")
        void compteurIllisible() {
            var plafond = new PlafondJournalierSms(redisQuiRend("pas-un-nombre"), metriques(), 500);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(true)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Redis indisponible")
    class RedisIndisponible {

        /**
         * Le choix le plus discutable de cette classe, donc celui qui doit etre
         * verrouille par un test.
         *
         * <p>Les SMS de ce service portent les codes de connexion. Refuser
         * faute de pouvoir LIRE un compteur transformerait un hoquet Redis en
         * impossibilite de se connecter pour tout le monde — un incident bien
         * plus grave que celui qu'on cherche a eviter.
         *
         * <p>Ce test existe pour qu'un futur durcissement « par principe » du
         * repli soit un choix explicite, et non une correction machinale.
         */
        @Test
        @DisplayName("une panne de lecture LAISSE PASSER l'envoi")
        void panneDeLectureLaissePasser() {
            var plafond = new PlafondJournalierSms(redisEnPanne(), metriques(), 500);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(true)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Plafond désactivé")
    class PlafondDesactive {

        /**
         * A zero, Redis n'est pas interroge DU TOUT.
         *
         * <p>Ce n'est pas une optimisation : c'est ce qui permet de construire
         * cette classe sans Redis dans les tests de {@code SmsServiceImpl}, et
         * ce qui garantit qu'un plafond desactive ne fait pas dependre l'envoi
         * de SMS de la disponibilite de Redis.
         */
        @Test
        @DisplayName("à zéro, aucune commande Redis n'est émise")
        void zeroNInterrogePasRedis() {
            ReactiveRedisTemplate<String, String> redis = redisQuiRend("999999");
            var plafond = new PlafondJournalierSms(redis, metriques(), 0);

            StepVerifier.create(plafond.resteDuCredit())
                    .expectNext(true)
                    .verifyComplete();
            StepVerifier.create(plafond.compterUnEnvoi())
                    .verifyComplete();

            verify(redis, never()).opsForValue();
            verify(redis, never()).expire(anyString(), any());
        }
    }
}
