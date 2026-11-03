package com.abdatytch.gateway_service.security;

import com.abdatytch.gateway_service.service.GeoLocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Blocage geographique au Gateway.
 *
 * <h2>Le compromis a verifier</h2>
 *
 * Ce filtre est <b>ouvert en cas d'erreur</b> : une base GeoIP illisible ou une
 * adresse non resolue laisse passer. C'est defendable — refuser faute de savoir
 * d'ou vient l'appelant fermerait la plateforme a tout le monde des que la base
 * se corrompt.
 *
 * <h2>Mais l'ouverture par ABSENCE n'est pas la meme chose</h2>
 *
 * {@code onErrorResume} ne rattrape que les erreurs. Un {@code Mono} <b>vide</b>
 * traverse les {@code flatMap} sans les declencher : la requete n'est alors ni
 * refusee ni transmise, et le client attend jusqu'a son delai d'expiration. Ce
 * n'est pas une ouverture, c'est une perte.
 *
 * <p>Le cas le plus genant est celui d'une adresse <b>bloquee</b> dont la
 * recherche de pays rend un {@code Mono} vide : la branche de refus produit
 * alors un {@code Mono} vide, et la requete se perd au lieu d'etre refusee.
 *
 * <p>C'est la troisieme fois que ce piege apparait dans ce Gateway — apres
 * {@code IpBlacklistWebFilter} et {@code RedisRateLimitWebFilter}. Il n'est
 * visible qu'en le cherchant.
 */
@DisplayName("GeoLocationWebFilter - blocage geographique")
class GeoLocationWebFilterTest {

    private static final String IP = "203.0.113.7";

    private GeoLocationService geo;
    private GeoLocationWebFilter filtre;

    @BeforeEach
    void setUp() {
        geo = Mockito.mock(GeoLocationService.class);
        when(geo.isEnabled()).thenReturn(true);
        when(geo.getCountryCode(anyString())).thenReturn(Mono.just("KP"));

        ClientIpResolver resolveur = Mockito.mock(ClientIpResolver.class);
        when(resolveur.resolve(Mockito.any())).thenReturn(IP);

        filtre = new GeoLocationWebFilter(geo, resolveur);
    }

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

    /** Une requete doit TOUJOURS aboutir : soit transmise, soit repondue. */
    private void doitAboutir(MockServerWebExchange echange) {
        assertThat(passe(echange) || echange.getResponse().getStatusCode() != null)
                .withFailMessage("la requete est perdue : ni transmise a la suite de "
                        + "la chaine, ni repondue. Le client attend jusqu'a son delai "
                        + "d'expiration")
                .isTrue();
    }

    // ==================================================================
    @Nested
    @DisplayName("Decision")
    class Decision {

        @Test
        @DisplayName("une adresse d'un pays bloque est refusee en 403")
        void paysBloqueRefuse() {
            when(geo.isIpBlocked(IP)).thenReturn(Mono.just(true));

            var echange = appel("/api/v1/auth/login");

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("une adresse autorisee passe")
        void paysAutorisePasse() {
            when(geo.isIpBlocked(IP)).thenReturn(Mono.just(false));

            assertThat(passe(appel("/api/v1/auth/login"))).isTrue();
        }

        @Test
        @DisplayName("la geolocalisation desactivee court-circuite tout")
        void desactivee() {
            when(geo.isEnabled()).thenReturn(false);

            assertThat(passe(appel("/api/v1/auth/login"))).isTrue();
            verify(geo, never()).isIpBlocked(anyString());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/swagger-ui/index.html",
                "/v3/api-docs",
                "/webjars/x.js",
                "/",
                "/health"
        })
        @DisplayName("un chemin de sonde ou de documentation n'est pas geo-bloque")
        void cheminExempte(String chemin) {
            // Les sondes viennent de Consul et de Prometheus, dont l'adresse
            // n'est pas celle d'un utilisateur. Les bloquer par pays
            // ferait croire le service mort.
            assertThat(passe(appel(chemin))).isTrue();
            verify(geo, never()).isIpBlocked(anyString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Degradation - ouverture en cas d'erreur")
    class Degradation {

        @Test
        @DisplayName("une erreur de la base GeoIP laisse passer")
        void erreurLaissePasser() {
            // Choix defendable : refuser faute de savoir d'ou vient l'appelant
            // fermerait la plateforme a tout le monde des que la base se
            // corrompt.
            when(geo.isIpBlocked(IP))
                    .thenReturn(Mono.error(new RuntimeException("base GeoIP illisible")));

            assertThat(passe(appel("/api/v1/auth/login"))).isTrue();
        }

        @Test
        @DisplayName("une reponse vide ne perd pas la requete")
        void reponseVideNePerdPasLaRequete() {
            // onErrorResume ne rattrape que les erreurs. Un Mono vide traverse
            // les flatMap sans les declencher : ni refus, ni transmission.
            when(geo.isIpBlocked(IP)).thenReturn(Mono.empty());

            doitAboutir(appel("/api/v1/auth/login"));
        }

        @Test
        @DisplayName("un pays introuvable sur une adresse BLOQUEE ne perd pas la requete")
        void paysIntrouvableSurAdresseBloquee() {
            // LE cas le plus genant. L'adresse est bloquee, mais la recherche de
            // pays — faite uniquement pour le message — rend un Mono vide. La
            // branche de refus produit alors un Mono vide : la requete se perd
            // au lieu d'etre refusee.
            //
            // Une recherche destinee a enrichir un message d'erreur ne doit pas
            // pouvoir empecher le refus lui-meme.
            when(geo.isIpBlocked(IP)).thenReturn(Mono.just(true));
            when(geo.getCountryCode(anyString())).thenReturn(Mono.empty());

            var echange = appel("/api/v1/auth/login");

            doitAboutir(echange);
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("une adresse bloquee n'est pas refusee parce que "
                            + "son pays est introuvable : le refus depend d'une "
                            + "recherche purement informative")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }
}
