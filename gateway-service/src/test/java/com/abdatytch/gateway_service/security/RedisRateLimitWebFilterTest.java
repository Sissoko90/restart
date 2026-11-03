package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Limitation de debit au Gateway.
 *
 * <h2>Ce que ce filtre protege</h2>
 *
 * {@code /api/v1/auth/**} est plafonne a cinq requetes par minute et par
 * adresse. C'est le seul rempart contre l'essai systematique de mots de passe
 * et contre l'epuisement des codes de verification par SMS — lesquels coutent
 * de l'argent a chaque envoi.
 *
 * <h2>Le defaut corrige</h2>
 *
 * L'expiration de la cle de comptage n'etait posee que lorsque le compteur
 * valait 1. Si cet appel echouait, la cle survivait <b>sans expiration</b> :
 * les appels suivants n'y repassaient jamais, le compteur ne redescendait plus,
 * et l'adresse franchissait le plafond puis y restait <b>definitivement</b> — a
 * cause d'un incident Redis d'une seconde.
 *
 * <p>Une expiration manquante est desormais reposee, et une expiration valide
 * est laissee telle quelle : la reposer a chaque requete ferait glisser la
 * fenetre et rendrait le plafond inatteignable pour un attaquant regulier.
 */
@DisplayName("RedisRateLimitWebFilter - limitation de debit")
class RedisRateLimitWebFilterTest {

    private static final String IP = "203.0.113.7";

    private ReactiveRedisTemplate<String, String> redis;
    private ReactiveValueOperations<String, String> valeurs;
    private LocalRateLimiter repliLocal;
    private SecurityViolationRecorder enregistreur;
    private RedisRateLimitWebFilter filtre;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = Mockito.mock(ReactiveRedisTemplate.class);
        valeurs = Mockito.mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);
        when(redis.expire(anyString(), any())).thenReturn(Mono.just(true));
        when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(30)));

        ClientIpResolver resolveur = Mockito.mock(ClientIpResolver.class);
        when(resolveur.resolve(any())).thenReturn(IP);

        repliLocal = Mockito.mock(LocalRateLimiter.class);
        enregistreur = Mockito.mock(SecurityViolationRecorder.class);
        when(enregistreur.enregistrer(anyString(), anyString())).thenReturn(Mono.empty());

        filtre = new RedisRateLimitWebFilter(redis, resolveur, repliLocal)
                .avecEnregistreur(enregistreur);
    }

    private void compteur(long valeur) {
        when(valeurs.increment(anyString())).thenReturn(Mono.just(valeur));
    }

    /** Rend l'echange apres passage dans le filtre. */
    private MockServerWebExchange appel(String chemin) {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get(chemin).build());
        AtomicBoolean atteinte = new AtomicBoolean(false);
        echange.getAttributes().put("atteinte", atteinte);

        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();

        return echange;
    }

    private boolean passe(MockServerWebExchange echange) {
        return ((AtomicBoolean) echange.getAttributes().get("atteinte")).get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Decision")
    class Decision {

        @Test
        @DisplayName("sous le plafond, la requete passe avec les en-tetes de quota")
        void sousLePlafond() {
            compteur(2L);

            var echange = appel("/api/v1/auth/login");

            assertThat(passe(echange)).isTrue();
            assertThat(echange.getResponse().getHeaders().getFirst("X-Rate-Limit-Limit"))
                    .isEqualTo("5");
            assertThat(echange.getResponse().getHeaders().getFirst("X-Rate-Limit-Remaining"))
                    .isEqualTo("3");
        }

        @Test
        @DisplayName("au-dela du plafond, la requete est refusee en 429")
        void auDelaDuPlafond() {
            compteur(6L);

            var echange = appel("/api/v1/auth/login");

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("un depassement est SIGNALE pour alimenter la liste noire")
        void depassementSignale() {
            // Sans ce signalement, un attaquant pouvait depasser le plafond
            // indefiniment : chaque requete etait refusee isolement, mais rien
            // ne s'accumulait et l'adresse n'etait jamais mise en liste noire.
            compteur(6L);

            appel("/api/v1/auth/login");

            verify(enregistreur).enregistrer(Mockito.eq(IP), anyString());
        }

        @ParameterizedTest(name = "{0} -> plafond {1}")
        @CsvSource({
                "/api/v1/auth/login, 5",
                "/api/v1/auth/register, 5",
                // LA SURFACE SDK D'AUTH-SERVICE, qui manquait.
                //
                // "/api/v1/auth/" ne prefixe PAS "/api/v1/auth-sdk/..." : la
                // barre oblique finale ferme le motif sur le segment « auth ».
                // Toute la surface SDK — connexion, inscription, OTP,
                // reinitialisation — tombait donc dans le plafond le plus LAXE,
                // 100/min au lieu de 5/min, alors qu'elle vient d'etre rendue
                // publique.
                "/api/v1/auth-sdk/login, 5",
                "/api/v1/auth-sdk/phone/register-request, 5",
                "/api/v1/auth-sdk/forgot-password, 5",
                "/api/v1/auth-sdk/admin/login, 5",
                "/api/v1/auth-sdk/biometric/authenticate/initiate, 5",
                "/api/v1/admin/accounts/x, 50",
                "/actuator/health, 50",
                "/api/v1/users/me, 100"
        })
        @DisplayName("le plafond depend de la famille de chemins")
        void plafondParFamille(String chemin, int plafondAttendu) {
            // L'espace d'authentification anonyme est le plus expose : essai de
            // mots de passe, epuisement des codes SMS qui coutent de l'argent.
            //
            // Et c'est le seul rempart contre la force brute DISTRIBUEE : le
            // verrouillage de compte se contourne en changeant d'identifiant a
            // chaque essai, la limitation par IP non.
            compteur(1L);

            var echange = appel(chemin);

            assertThat(echange.getResponse().getHeaders().getFirst("X-Rate-Limit-Limit"))
                    .isEqualTo(String.valueOf(plafondAttendu));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La forme du prefixe de chemin")
    class FormeDuPrefixe {

        /**
         * Le defaut que ce test existe pour empecher.
         *
         * <p>Le commentaire de {@code determineLimit} annoncait « tout l'espace
         * d'authentification anonyme », et le motif n'en couvrait que la moitie :
         * {@code "/api/v1/auth/"} ne prefixe pas {@code "/api/v1/auth-sdk/"}.
         *
         * <p>C'est la meme famille de defaut que les trois deja corriges dans
         * les filtres de ce service — un controle de chemin qui designe moins
         * large, ou plus large, que son intention. Ici il designait moins, et le
         * cout etait un plafond vingt fois trop permissif sur la surface la plus
         * exposee.
         */
        @Test
        @DisplayName("auth-sdk et auth recoivent le MEME plafond")
        void authSdkEtAuthMemePlafond() {
            compteur(1L);
            String plafondAuth = appel("/api/v1/auth/login").getResponse()
                    .getHeaders().getFirst("X-Rate-Limit-Limit");

            compteur(1L);
            String plafondSdk = appel("/api/v1/auth-sdk/login").getResponse()
                    .getHeaders().getFirst("X-Rate-Limit-Limit");

            assertThat(plafondSdk)
                    .withFailMessage("la surface SDK d'auth-service recoit un plafond "
                            + "different de celle d'interface-service, alors que les deux "
                            + "sont des endpoints d'authentification publics")
                    .isEqualTo(plafondAuth);
        }

        @Test
        @DisplayName("un chemin voisin ne beneficie pas du plafond sensible par hasard")
        void unCheminVoisinNEstPasConfondu() {
            // L'inverse du defaut : le motif ne doit pas non plus ouvrir le
            // plafond strict a des chemins qui n'en relevent pas. /auth-users/**
            // est une surface d'administration, pas d'authentification.
            compteur(1L);

            var echange = appel("/api/v1/auth-users/x/roles/ADMIN");

            assertThat(echange.getResponse().getHeaders().getFirst("X-Rate-Limit-Limit"))
                    .isNotEqualTo("5");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Expiration de la cle de comptage")
    class Expiration {

        @Test
        @DisplayName("l'expiration est posee a la premiere requete")
        void posseePremiereRequete() {
            compteur(1L);

            appel("/api/v1/auth/login");

            verify(redis).expire(anyString(), any());
        }

        @Test
        @DisplayName("une expiration MANQUANTE est reposee")
        void expirationReparee() {
            // LE defaut corrige. Sans cette reparation, une cle orpheline — issue
            // d'un expire ayant echoue au premier appel — ne redescendait jamais,
            // et l'adresse restait limitee definitivement.
            compteur(3L);
            when(redis.getExpire(anyString())).thenReturn(Mono.just(Duration.ofSeconds(-1)));

            appel("/api/v1/auth/login");

            verify(redis).expire(anyString(), any());
        }

        @Test
        @DisplayName("une expiration VALIDE n'est pas reposee")
        void expirationConservee() {
            // La reposer a chaque requete ferait glisser la fenetre : un
            // attaquant envoyant une requete toutes les cinquante secondes
            // n'atteindrait jamais le plafond.
            compteur(3L);

            appel("/api/v1/auth/login");

            verify(redis, never()).expire(anyString(), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Panne de Redis - repli local")
    class Repli {

        @Test
        @DisplayName("Redis injoignable bascule sur le compteur local")
        void repliSurCompteurLocal() {
            // Contrairement a la liste noire, la limitation ne s'ouvre pas en cas
            // de panne : un plafond en memoire prend le relais. Laisser passer
            // sans limite exposerait l'essai de mots de passe au moment ou la
            // supervision est deja degradee.
            when(valeurs.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));
            when(repliLocal.tryConsume(anyString(), Mockito.anyInt())).thenReturn(true);

            var echange = appel("/api/v1/auth/login");

            assertThat(passe(echange)).isTrue();
            verify(repliLocal).tryConsume(anyString(), Mockito.anyInt());
        }

        @Test
        @DisplayName("le plafond local epuise refuse aussi en 429")
        void plafondLocalEpuise() {
            when(valeurs.increment(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));
            when(repliLocal.tryConsume(anyString(), Mockito.anyInt())).thenReturn(false);

            var echange = appel("/api/v1/auth/login");

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("une reponse vide de Redis ne perd pas la requete")
        void reponseVideNePerdPasLaRequete() {
            // Meme piege que celui trouve dans IpBlacklistWebFilter : un Mono
            // vide traverse les flatMap sans les declencher, et la requete
            // n'est ni transmise ni repondue — un client en attente jusqu'a son
            // delai d'expiration. onErrorResume ne s'applique pas : il n'y a
            // pas d'erreur, juste une absence de valeur.
            when(valeurs.increment(anyString())).thenReturn(Mono.empty());
            when(repliLocal.tryConsume(anyString(), Mockito.anyInt())).thenReturn(true);

            var echange = appel("/api/v1/auth/login");

            assertThat(passe(echange))
                    .withFailMessage("une reponse vide de Redis perd la requete : "
                            + "ni suite de chaine, ni reponse, donc un client en "
                            + "attente jusqu'a son delai d'expiration")
                    .isTrue();

            // Et le repli local DOIT avoir ete consulte : laisser passer sans
            // plafond exposerait l'essai de mots de passe au moment precis ou la
            // supervision est degradee.
            verify(repliLocal).tryConsume(anyString(), Mockito.anyInt());
        }
    }
}
