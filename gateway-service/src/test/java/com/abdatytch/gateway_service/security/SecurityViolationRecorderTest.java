package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Enregistrement des violations et alimentation de la liste noire.
 *
 * <h2>La panne que cette classe repare</h2>
 *
 * {@link IpBlacklistWebFilter} refuse les adresses inscrites dans une cle Redis,
 * et portait une methode {@code recordViolation} chargee de les y inscrire au
 * bout de dix violations. <b>Cette methode n'etait appelee nulle part.</b>
 *
 * <p>La liste noire ne se remplissait donc jamais : le filtre lisait une cle
 * qu'aucun code n'ecrivait. Il refusait correctement une adresse deja inscrite,
 * mais aucune ne l'etait. Dix traversees de repertoire depuis la meme adresse
 * n'avaient aucune consequence au-dela du refus de chaque requete prise
 * isolement — c'est-a-dire qu'un balayage pouvait continuer indefiniment.
 *
 * <p>C'est la troisieme occurrence du meme motif dans ce depot : une protection
 * ecrite, presente, et jamais atteinte. Les deux precedentes etaient les 33
 * {@code @PreAuthorize} inertes d'auth-service et le {@code isValidImage}
 * inatteignable de user-service.
 */
@DisplayName("SecurityViolationRecorder - alimentation de la liste noire")
class SecurityViolationRecorderTest {

    private static final String IP = "203.0.113.7";

    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveValueOperations<String, String> valeurs;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry registre;
    private SecurityViolationRecorder enregistreur;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = Mockito.mock(ReactiveRedisTemplate.class);
        valeurs = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(redis.expire(anyString(), any())).thenReturn(Mono.just(true));
        when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofMinutes(3)));
        when(valeurs.set(anyString(), anyString(), any())).thenReturn(Mono.just(true));

        // Un VRAI registre, et non un bouchon : les assertions ci-dessous
        // lisent les compteurs reellement produits. Avec un mock, elles ne
        // verifieraient que l'appel, pas la valeur ni les etiquettes.
        registre = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        enregistreur = new SecurityViolationRecorder(redis, registre);
    }

    private void violations(long compteur) {
        when(valeurs.increment(anyString())).thenReturn(Mono.just(compteur));
    }

    // ==================================================================
    @Nested
    @DisplayName("Comptage")
    class Comptage {

        @Test
        @DisplayName("une violation incremente le compteur de l'adresse")
        void compteurIncremente() {
            violations(1L);

            StepVerifier.create(enregistreur.enregistrer(IP, "path-traversal"))
                    .verifyComplete();

            verify(valeurs).increment(IpBlacklistWebFilter.VIOLATION_KEY_PREFIX + IP);
        }

        @Test
        @DisplayName("l'expiration est posee a la premiere violation")
        void expirationPremiereViolation() {
            // Sans expiration, les violations s'accumuleraient indefiniment et
            // toute adresse finirait en liste noire — y compris une adresse
            // partagee derriere un NAT d'operateur.
            violations(1L);

            StepVerifier.create(enregistreur.enregistrer(IP, "xss-body")).verifyComplete();

            verify(redis).expire(IpBlacklistWebFilter.VIOLATION_KEY_PREFIX + IP,
                    SecurityViolationRecorder.VIOLATION_WINDOW);
        }

        @Test
        @DisplayName("une expiration manquante est REPOSEE sur les violations suivantes")
        void expirationReparee() {
            // Le defaut que ce controle previent : poser l'expiration seulement
            // quand le compteur vaut 1 laisse une cle orpheline si cet appel
            // echoue. Les appels suivants n'y repassent jamais, le compteur ne
            // redescend plus, et l'adresse franchit le seuil puis y reste —
            // definitivement, a cause d'un incident Redis d'une seconde.
            violations(3L);
            when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(-1)));

            StepVerifier.create(enregistreur.enregistrer(IP, "xss-body")).verifyComplete();

            verify(redis).expire(IpBlacklistWebFilter.VIOLATION_KEY_PREFIX + IP,
                    SecurityViolationRecorder.VIOLATION_WINDOW);
        }

        @Test
        @DisplayName("une expiration valide n'est pas reposee")
        void expirationConservee() {
            // Reposer l'expiration a chaque violation ferait glisser la fenetre
            // et rendrait le seuil inatteignable pour un attaquant regulier.
            violations(3L);

            StepVerifier.create(enregistreur.enregistrer(IP, "xss-body")).verifyComplete();

            verify(redis, never()).expire(anyString(), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Mise en liste noire")
    class ListeNoire {

        @Test
        @DisplayName("sous le seuil, aucune inscription")
        void sousLeSeuil() {
            violations(SecurityViolationRecorder.VIOLATIONS_BEFORE_BLACKLIST - 1);

            StepVerifier.create(enregistreur.enregistrer(IP, "path-traversal"))
                    .verifyComplete();

            verify(valeurs, never()).set(
                    eq(IpBlacklistWebFilter.BLACKLIST_KEY_PREFIX + IP), anyString(), any());
        }

        @Test
        @DisplayName("au seuil, l'adresse est inscrite avec une expiration")
        void auSeuil() {
            // LE comportement qui n'existait pas : c'est cette ecriture que
            // IpBlacklistWebFilter attendait et que personne ne produisait.
            violations(SecurityViolationRecorder.VIOLATIONS_BEFORE_BLACKLIST);

            StepVerifier.create(enregistreur.enregistrer(IP, "path-traversal"))
                    .verifyComplete();

            verify(valeurs).set(
                    eq(IpBlacklistWebFilter.BLACKLIST_KEY_PREFIX + IP),
                    eq("path-traversal"),
                    eq(SecurityViolationRecorder.BLACKLIST_TTL));
        }

        @Test
        @DisplayName("la cle ecrite est EXACTEMENT celle que le filtre lit")
        void memeCleQueLeFiltre() {
            // Le coeur du probleme d'origine. Que l'ecrivain et le lecteur
            // emploient des prefixes differents produirait la meme panne sous
            // une autre forme : une liste noire qui se remplit et que personne
            // ne consulte. Les constantes sont desormais partagees, et ce test
            // echouerait si quelqu'un les redeclarait.
            violations(SecurityViolationRecorder.VIOLATIONS_BEFORE_BLACKLIST);

            StepVerifier.create(enregistreur.enregistrer(IP, "motif")).verifyComplete();

            verify(valeurs).set(
                    eq(IpBlacklistWebFilter.BLACKLIST_KEY_PREFIX + IP), anyString(), any());
        }

        @Test
        @DisplayName("l'inscription expire : une adresse n'est pas bannie a vie")
        void inscriptionExpire() {
            // Les adresses sont reattribuees, et un NAT d'operateur en couvre
            // des milliers d'abonnes. Un bannissement sans expiration finirait
            // par refuser des personnes qui n'ont rien fait.
            org.assertj.core.api.Assertions
                    .assertThat(SecurityViolationRecorder.BLACKLIST_TTL)
                    .isPositive()
                    .isLessThanOrEqualTo(Duration.ofDays(30));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Robustesse")
    class Robustesse {

        @Test
        @DisplayName("Redis injoignable n'interrompt pas la requete")
        void redisInjoignable() {
            // Le refus de la requete en cours appartient au filtre qui detecte.
            // Si l'enregistrement propageait son erreur, une panne de Redis
            // transformerait chaque detection en erreur 500 au lieu d'un refus
            // propre — et perdre un compteur de violations est moins grave que
            // de brouiller le signal rendu a l'appelant.
            when(valeurs.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            StepVerifier.create(enregistreur.enregistrer(IP, "path-traversal"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("une adresse absente ou vide est ignoree sans erreur")
        void adresseAbsente() {
            StepVerifier.create(enregistreur.enregistrer(null, "motif")).verifyComplete();
            StepVerifier.create(enregistreur.enregistrer("  ", "motif")).verifyComplete();

            verify(valeurs, never()).increment(anyString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Metriques — le point de convergence des quatre filtres")
    class Metriques {

        @Test
        @DisplayName("chaque violation est comptee, etiquetee par motif")
        void violationComptee() {
            violations(1L);

            StepVerifier.create(enregistreur.enregistrer(IP, "path-traversal"))
                    .verifyComplete();

            assertThat(registre.counter(SecurityViolationRecorder.METRIQUE_VIOLATIONS,
                            "motif", "path-traversal").count())
                    .withFailMessage("la violation n'est pas comptee : les filtres du "
                            + "Gateway refusent du trafic sans que la supervision le voie")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("la metrique ne depend PAS de Redis")
        void metriqueIndependanteDeRedis() {
            // L'enregistrement Redis avale ses erreurs — perdre un compteur de
            // violations vaut mieux que refuser du trafic legitime pendant une
            // panne de cache. Mais si la METRIQUE dependait du meme Redis, une
            // panne rendrait les violations invisibles a la supervision au
            // moment precis ou elles comptent le plus.
            when(valeurs.increment(anyString()))
                    .thenReturn(Mono.error(new IllegalStateException("Redis injoignable")));

            StepVerifier.create(enregistreur.enregistrer(IP, "xss"))
                    .verifyComplete();

            assertThat(registre.counter(SecurityViolationRecorder.METRIQUE_VIOLATIONS,
                            "motif", "xss").count())
                    .withFailMessage("Redis en panne a fait disparaitre la metrique : "
                            + "une attaque pendant une panne de cache serait invisible")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("une mise en liste noire est comptee a part")
        void listeNoireComptee() {
            violations(SecurityViolationRecorder.VIOLATIONS_BEFORE_BLACKLIST);

            StepVerifier.create(enregistreur.enregistrer(IP, "rate-limit"))
                    .verifyComplete();

            // Evenement RARE par construction — dix violations en cinq minutes
            // depuis la meme adresse — donc son alerte a un seuil a zero.
            assertThat(registre.counter(SecurityViolationRecorder.METRIQUE_LISTE_NOIRE,
                            "motif", "rate-limit").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("sous le seuil, rien n'est compte en liste noire")
        void pasDeListeNoireSousLeSeuil() {
            violations(3L);

            StepVerifier.create(enregistreur.enregistrer(IP, "xss")).verifyComplete();

            assertThat(registre.find(SecurityViolationRecorder.METRIQUE_LISTE_NOIRE)
                            .counter())
                    .withFailMessage("une mise en liste noire est comptee alors que le "
                            + "seuil n'est pas atteint : l'alerte a seuil zero se "
                            + "declencherait a chaque violation")
                    .isNull();
        }

        @Test
        @DisplayName("aucune etiquette ne porte l'adresse IP")
        void aucuneEtiquetteDAdresse() {
            // Une adresse IP est une donnee personnelle — AccountErasureService
            // l'efface a ce titre — et une serie Prometheus n'est pas
            // effaçable. C'est aussi une etiquette de cardinalite non bornee,
            // qui ferait croitre la memoire de Prometheus sans limite.
            violations(SecurityViolationRecorder.VIOLATIONS_BEFORE_BLACKLIST);
            StepVerifier.create(enregistreur.enregistrer(IP, "xss")).verifyComplete();

            boolean adresseExposee = registre.getMeters().stream()
                    .flatMap(m -> m.getId().getTags().stream())
                    .anyMatch(tag -> tag.getValue().contains(IP));

            assertThat(adresseExposee)
                    .withFailMessage("l'adresse %s est exposee en etiquette : donnee "
                            + "personnelle non effaçable, et cardinalite non bornee", IP)
                    .isFalse();
        }
    }
}
