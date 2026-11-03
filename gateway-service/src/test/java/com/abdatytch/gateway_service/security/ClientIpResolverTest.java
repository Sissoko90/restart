package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'IP du client détermine à elle seule trois décisions de sécurité : le
 * plafond de débit, la liste noire et le blocage géographique. Si elle est
 * falsifiable, les trois le sont.
 *
 * <p>Ces tests décrivent la seule règle qui tienne : ne croire un en-tête de
 * transfert que lorsqu'il vient d'un pair qu'on a explicitement désigné comme
 * proxy de confiance.
 */
@DisplayName("ClientIpResolver — établir l'IP réelle du client")
class ClientIpResolverTest {

    private static final String CLIENT = "203.0.113.7";
    private static final String PROXY = "10.0.0.5";

    private MockServerWebExchange from(String peer, String... headers) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/v1/users/me")
                .remoteAddress(new InetSocketAddress(peer, 44444));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return MockServerWebExchange.from(builder.build());
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Sans proxy de confiance configuré (Gateway exposé directement)")
    class SansProxyDeConfiance {

        private final ClientIpResolver resolver = new ClientIpResolver("");

        @Test
        @DisplayName("X-Forwarded-For est ignoré au profit de l'adresse de la socket")
        void xffIgnore() {
            var exchange = from(CLIENT, "X-Forwarded-For", "1.2.3.4");

            assertThat(resolver.resolve(exchange))
                    .withFailMessage("un en-tête écrit par le client a été retenu : il suffit "
                            + "d'en changer la valeur pour obtenir un compteur de débit neuf, "
                            + "ou pour se soustraire à la liste noire")
                    .isEqualTo(CLIENT);
        }

        @Test
        @DisplayName("X-Real-IP est ignoré lui aussi")
        void realIpIgnore() {
            assertThat(resolver.resolve(from(CLIENT, "X-Real-IP", "1.2.3.4"))).isEqualTo(CLIENT);
        }

        @Test
        @DisplayName("sans en-tête, l'adresse de la socket est retenue")
        void adresseDeSocket() {
            assertThat(resolver.resolve(from(CLIENT))).isEqualTo(CLIENT);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Derrière un proxy de confiance")
    class DerriereUnProxy {

        private final ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");

        @Test
        @DisplayName("X-Forwarded-For est honoré quand il vient du proxy")
        void xffHonore() {
            var exchange = from(PROXY, "X-Forwarded-For", CLIENT);

            assertThat(resolver.resolve(exchange)).isEqualTo(CLIENT);
        }

        @Test
        @DisplayName("la chaîne est lue de droite à gauche, en sautant les proxys connus")
        void chaineLueDepuisLaDroite() {
            // Le client écrit lui-même le début de la chaîne : « 66.66.66.66 » est
            // une valeur qu'il a inventée. Seule l'entrée ajoutée par notre propre
            // proxy — la plus à droite hors plages de confiance — fait foi.
            var exchange = from(PROXY, "X-Forwarded-For", "66.66.66.66, " + CLIENT + ", 10.0.0.9");

            assertThat(resolver.resolve(exchange))
                    .withFailMessage("la chaîne a été lue de gauche à droite : le client "
                            + "choisit alors l'IP qu'on lui attribue")
                    .isEqualTo(CLIENT);
        }

        @Test
        @DisplayName("un pair hors des plages de confiance ne peut pas usurper d'IP")
        void pairNonFiable() {
            var exchange = from("198.51.100.9", "X-Forwarded-For", CLIENT);

            assertThat(resolver.resolve(exchange)).isEqualTo("198.51.100.9");
        }

        @Test
        @DisplayName("une chaîne malformée fait retomber sur l'adresse de la socket")
        void chaineMalformee() {
            var exchange = from(PROXY, "X-Forwarded-For", "pas-une-ip");

            assertThat(resolver.resolve(exchange)).isEqualTo(PROXY);
        }

        @Test
        @DisplayName("un port éventuel est retiré")
        void portRetire() {
            var exchange = from(PROXY, "X-Forwarded-For", CLIENT + ":41234");

            assertThat(resolver.resolve(exchange)).isEqualTo(CLIENT);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Robustesse de la configuration")
    class Configuration {

        @Test
        @DisplayName("une plage illisible est ignorée sans élargir la confiance")
        void plageIllisible() {
            // Une faute de frappe dans la configuration ne doit jamais se traduire
            // par « on fait confiance à tout le monde ».
            ClientIpResolver resolver = new ClientIpResolver("pas-un-cidr, 10.0.0.0/8");

            assertThat(resolver.resolve(from(PROXY, "X-Forwarded-For", CLIENT))).isEqualTo(CLIENT);
            assertThat(resolver.resolve(from("198.51.100.9", "X-Forwarded-For", CLIENT)))
                    .isEqualTo("198.51.100.9");
        }

        @Test
        @DisplayName("une plage IPv4 ne fait pas confiance à un pair IPv6")
        void pasDeConfusionEntreFamilles() {
            ClientIpResolver resolver = new ClientIpResolver("0.0.0.0/0");
            var exchange = from("::1", "X-Forwarded-For", CLIENT);

            assertThat(resolver.resolve(exchange)).isEqualTo("0:0:0:0:0:0:0:1");
        }
    }
}
