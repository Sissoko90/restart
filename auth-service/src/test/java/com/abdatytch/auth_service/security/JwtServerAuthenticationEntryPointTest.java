package com.abdatytch.auth_service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.AuthenticationException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La réponse rendue quand l'authentification échoue.
 *
 * <h2>Pourquoi cette classe mérite des tests</h2>
 *
 * C'est le point de sortie de toute la chaîne : un défaut dans
 * {@link JwtAuthenticationWebFilter} ne se voit pas là où il est, il se voit
 * <b>ici</b>, sous la forme d'un 401. La réponse est donc le seul élément dont
 * dispose un développeur légitime pour diagnostiquer — et le seul dont dispose
 * un attaquant pour s'orienter.
 *
 * <h2>Le défaut corrigé</h2>
 *
 * Le corps portait {@code "details": ex.getMessage()} : le message interne de
 * l'exception Spring Security. Un appelant apprenait si son jeton était absent,
 * expiré, mal signé ou destiné à une autre audience — soit quels contrôles il a
 * déjà franchis.
 *
 * <p>Le Gateway applique la politique inverse, et son filtre JWT la porte en
 * commentaire : la réponse ne dit jamais pourquoi. Les deux services doivent
 * répondre de la même façon, sans quoi il suffit d'interroger le moins discret.
 */
@DisplayName("JwtServerAuthenticationEntryPoint - le 401 ne renseigne pas")
class JwtServerAuthenticationEntryPointTest {

    private JwtServerAuthenticationEntryPoint point;

    @BeforeEach
    void setUp() {
        // Injection par constructeur : l'ancien @Autowired sur le champ rendait
        // cette classe intestable sans contexte Spring, ce qui explique
        // largement qu'elle n'ait eu aucun test.
        point = new JwtServerAuthenticationEntryPoint(new ObjectMapper());
    }

    private MockServerWebExchange repondre(AuthenticationException ex) {
        MockServerWebExchange echange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/admin/accounts").build());
        point.commence(echange, ex).block();
        return echange;
    }

    private String corps(MockServerWebExchange echange) {
        return echange.getResponse().getBodyAsString()
                .defaultIfEmpty("")
                .block();
    }

    // ==================================================================
    @Nested
    @DisplayName("Forme de la réponse")
    class Forme {

        @Test
        @DisplayName("le statut est 401 et le corps est du JSON")
        void statutEtType() {
            var echange = repondre(new BadCredentialsException("jeton absent"));

            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(echange.getResponse().getHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("le corps est écrit, la requête n'est pas laissée en suspens")
        void corpsEcrit() {
            // Un entry point qui ne repond pas laisse le client attendre
            // jusqu'a son delai d'expiration.
            assertThat(corps(repondre(new BadCredentialsException("x"))))
                    .isNotEmpty()
                    .contains("\"status\":401");
        }

        @Test
        @DisplayName("le corps est un JSON valide et complet")
        void jsonValide() {
            String json = corps(repondre(new BadCredentialsException("x")));

            assertThat(json)
                    .startsWith("{")
                    .endsWith("}")
                    .contains("\"timestamp\"")
                    .contains("\"path\":\"/admin/accounts\"");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : la réponse ne dit pas POURQUOI")
    class PasDOracle {

        @ParameterizedTest(name = "« {0} » ne fuit pas")
        @ValueSource(strings = {
                "Jeton expiré depuis 3 jours",
                "Signature invalide : clé kid=abc123 inconnue",
                "Audience attendue auth-service, reçue media-service",
                "Utilisateur 11111111-1111-1111-1111-111111111111 révoqué"
        })
        @DisplayName("le message interne de l'exception n'apparaît pas dans le corps")
        void messageInterneNonExpose(String messageInterne) {
            // LE DEFAUT. Avec "details": ex.getMessage(), chacun de ces
            // messages partait au client.
            //
            // Le dernier est le pire : il confirme l'existence d'un compte et en
            // livre l'identifiant technique. Le deuxieme nomme une cle de
            // signature. Le troisieme decrit l'architecture interne.
            String json = corps(repondre(new BadCredentialsException(messageInterne)));

            assertThat(json)
                    .withFailMessage("le message interne « %s » est renvoyé au client",
                            messageInterne)
                    .doesNotContain(messageInterne);
            assertThat(json)
                    .withFailMessage("le champ `details` est de retour : il portait "
                            + "le message interne")
                    .doesNotContain("\"details\"");
        }

        @Test
        @DisplayName("un identifiant présent dans l'exception ne fuit pas")
        void identifiantNeFuitPas() {
            String identifiant = "11111111-1111-1111-1111-111111111111";

            assertThat(corps(repondre(new BadCredentialsException(
                    "Utilisateur " + identifiant + " introuvable"))))
                    .doesNotContain(identifiant);
        }

        @Test
        @DisplayName("deux causes DIFFERENTES donnent exactement le même message")
        void messageUniforme() {
            // C'est l'assertion decisive. Toute difference entre un jeton absent
            // et un jeton expire est un oracle : elle permet de distinguer
            // « ce compte existe mais ta session a expire » de « rien ici ».
            //
            // Comparer les messages, et non les corps entiers : ceux-ci
            // contiennent un horodatage, donc differeraient toujours.
            String absent = extraireMessage(corps(
                    repondre(new BadCredentialsException("aucun jeton fourni"))));
            String expire = extraireMessage(corps(
                    repondre(new CredentialsExpiredException("jeton expiré à 12h04"))));

            assertThat(absent)
                    .withFailMessage("le message varie selon la cause : c'est un oracle")
                    .isEqualTo(expire)
                    .isNotEmpty();
        }

        private String extraireMessage(String json) {
            int debut = json.indexOf("\"message\":\"");
            if (debut < 0) {
                return "";
            }
            debut += "\"message\":\"".length();
            return json.substring(debut, json.indexOf('"', debut));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Robustesse")
    class Robustesse {

        @Test
        @DisplayName("une exception sans message ne provoque pas d'échec")
        void exceptionSansMessage() {
            // getMessage() peut rendre null : l'ancien code le mettait tel quel
            // dans le corps. Il ne doit ni lever, ni produire « null » comme
            // explication.
            var echange = repondre(new BadCredentialsException(null));

            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(corps(echange)).doesNotContain("null");
        }

        @Test
        @DisplayName("le corps annoncé correspond aux octets écrits")
        void longueurCoherente() {
            // Un Content-Length incorrect ferait tronquer ou attendre le client.
            var echange = repondre(new BadCredentialsException("x"));
            String json = corps(echange);

            long annoncee = echange.getResponse().getHeaders().getContentLength();
            if (annoncee > 0) {
                assertThat(json.getBytes(StandardCharsets.UTF_8))
                        .hasSize((int) annoncee);
            }
        }
    }
}
