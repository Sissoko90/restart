package com.abdatytch.gateway_service.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation de la version d'API dans le chemin.
 *
 * <h2>Le defaut corrige</h2>
 *
 * Le motif etait {@code /api/v(\d+)/.*} — le nombre de chiffres n'etait pas
 * borne. Le chemin {@code /api/v99999999999999999999/x} produisait un groupe de
 * vingt chiffres, et {@code Integer.parseInt} levait une
 * {@code NumberFormatException} <b>non rattrapee</b> : une erreur <b>500</b> sur
 * une entree que l'appelant controle entierement.
 *
 * <p>Ce n'est pas un trou de securite, mais ce n'est pas anodin : un 500
 * renseigne sur la pile d'appels, et il pollue la supervision avec de fausses
 * defaillances serveur — celles-la meme qu'on finit par ignorer, au moment ou
 * une vraie panne survient.
 *
 * <p>Le motif accepte desormais un a trois chiffres, ce qui laisse de la marge
 * pour mille versions.
 */
@DisplayName("ApiVersionWebFilter - version dans le chemin")
class ApiVersionWebFilterTest {

    private ApiVersionWebFilter filtre;

    @BeforeEach
    void setUp() {
        filtre = new ApiVersionWebFilter();
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

    // ==================================================================
    @Nested
    @DisplayName("Versions acceptees")
    class Acceptees {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/auth/login",
                "/api/v1/users/me",
                "/api/v1/media/fichier.jpg"
        })
        @DisplayName("la version prise en charge passe")
        void versionPriseEnCharge(String chemin) {
            assertThat(passe(appel(chemin))).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/v3/api-docs",
                "/swagger-ui/index.html"
        })
        @DisplayName("un chemin hors /api/v n'est pas concerne")
        void horsPerimetre(String chemin) {
            assertThat(passe(appel(chemin))).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Versions refusees")
    class Refusees {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/api/v2/auth/login", "/api/v9/users/me", "/api/v0/x"})
        @DisplayName("une version non prise en charge rend 404")
        void versionInconnue(String chemin) {
            var echange = appel(chemin);

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/api/vX/auth", "/api/v/auth", "/api/v1"})
        @DisplayName("un chemin mal forme rend 400")
        void cheminMalForme(String chemin) {
            var echange = appel(chemin);

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le defaut corrige")
    class DefautCorrige {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v99999999999999999999/x",
                "/api/v2147483648/x",
                "/api/v00000000000000000001/x"
        })
        @DisplayName("un numero de version enorme ne provoque PAS d'erreur 500")
        void numeroEnorme(String chemin) {
            // Avec `\\d+`, Integer.parseInt levait une NumberFormatException non
            // rattrapee. Le motif borne a trois chiffres fait desormais echouer
            // la correspondance, donc rendre 400 — une reponse de client, pas de
            // serveur.
            var echange = appel(chemin);

            assertThat(passe(echange))
                    .withFailMessage("%s traverse le filtre", chemin)
                    .isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("%s rend %s : une entree controlee par "
                            + "l'appelant ne doit pas produire une defaillance serveur",
                            chemin, echange.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("trois chiffres restent acceptes, pour laisser de la marge")
        void troisChiffresAcceptes() {
            // Borner trop court aurait ete une regression silencieuse : /api/v100
            // doit rester un chemin exploitable, meme si la version n'est pas
            // encore prise en charge. Il rend 404, pas 400.
            var echange = appel("/api/v100/auth/login");

            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("une version a trois chiffres est traitee comme "
                            + "un chemin mal forme : le motif est trop etroit")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
