package com.abdatytch.gateway_service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le scanner de corps n'avait aucun test, et il perdait des requêtes.
 *
 * <h2>Les deux défauts, qui se tenaient</h2>
 *
 * <ol>
 *   <li>le garde-fou de méthode était <b>inerte</b> :
 *       {@code request.getMethod().matches("GET|DELETE|HEAD|OPTIONS")}.
 *       {@code HttpMethod.matches(String)} compare le nom de la méthode à la
 *       chaîne fournie, <b>exactement</b> — ce n'est pas une expression
 *       régulière, malgré l'apparence. Toutes les méthodes entraient donc dans
 *       le scan ;</li>
 *   <li>{@code DataBufferUtils.join} rend un {@code Mono} <b>vide</b> quand il
 *       n'y a pas de corps. Le {@code flatMap} ne tournait alors jamais, la
 *       requête n'était <b>pas transmise</b>, et la réponse se terminait sans
 *       rien.</li>
 * </ol>
 *
 * <p>Le premier masquait le second : tant qu'on croyait les GET écartés, le
 * traitement d'un corps vide n'avait pas à être correct.
 *
 * <h2>Ce que cela produisait</h2>
 *
 * Mesuré sur l'environnement de développement, avec un GET portant
 * {@code Content-Type: application/json} et aucun corps — ce que produit tout
 * client qui pose cet en-tête par défaut, dont les collections Postman
 * générées de ce dépôt :
 *
 * <pre>
 * GET /api/v1/auth-admin/sms/solde                     -> 200, 56 octets
 * GET /api/v1/auth-admin/sms/solde + Content-Type JSON -> 200, 0 octet
 * </pre>
 *
 * Une réponse 200 <b>vide</b>, pour une requête que le service aval n'a jamais
 * vue. C'est la pire forme : le client croit avoir été servi.
 */
@DisplayName("BodyXssScannerWebFilter — aucune requête ne doit disparaître")
class BodyXssScannerWebFilterTest {

    private static final String CHEMIN = "/api/v1/auth-admin/sms/solde";

    private BodyXssScannerWebFilter filtre() {
        ClientIpResolver resolveur = Mockito.mock(ClientIpResolver.class);
        Mockito.when(resolveur.resolve(Mockito.any())).thenReturn("10.0.0.5");
        return new BodyXssScannerWebFilter(resolveur);
    }

    /** Traverse le filtre et dit combien de fois la suite a été atteinte. */
    private int transmissions(MockServerWebExchange echange) {
        AtomicInteger atteintes = new AtomicInteger();
        filtre().filter(echange, suite -> {
            atteintes.incrementAndGet();
            return Mono.empty();
        }).block();
        return atteintes.get();
    }

    @Nested
    @DisplayName("Un corps absent ne fait pas disparaître la requête")
    class CorpsAbsent {

        /**
         * Le défaut exact qui rendait 200 à corps vide.
         *
         * <p>Un GET avec {@code Content-Type: application/json} et aucun corps
         * n'était jamais transmis : {@code join} rendait un {@code Mono} vide,
         * le {@code flatMap} était ignoré, et la réponse se terminait sans rien.
         */
        @ParameterizedTest(name = "{0} avec Content-Type JSON et sans corps")
        @ValueSource(strings = {"GET", "DELETE", "HEAD", "OPTIONS"})
        @DisplayName("les méthodes sans corps sont transmises, malgré le Content-Type")
        void methodesSansCorpsTransmises(String methode) {
            var requete = MockServerHttpRequest
                    .method(HttpMethod.valueOf(methode), CHEMIN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .build();

            assertThat(transmissions(MockServerWebExchange.from(requete)))
                    .withFailMessage("la requête n'est pas transmise : le client "
                            + "recevra 200 avec un corps vide, pour une requête que "
                            + "le service aval n'a jamais vue")
                    .isEqualTo(1);
        }

        /**
         * Verrouille le garde-fou de méthode lui-même.
         *
         * <p>Une fois le corps vide traité correctement, ce garde-fou ne change
         * plus le <b>résultat</b> — vérifié par mutation : le remettre en
         * comparaison exacte ne fait échouer aucun des autres tests. Son effet
         * observable est de ne pas <b>lire</b> le corps sur une méthode qui n'en
         * a pas.
         *
         * <p>Ce test observe donc la souscription au corps, et non la réponse.
         * Sans cela, la correction du garde-fou serait du code que rien ne
         * retient.
         */
        @Test
        @DisplayName("sur un GET, le corps n'est même pas lu")
        void leCorpsNEstPasLuSurUnGet() {
            java.util.concurrent.atomic.AtomicBoolean lu =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            var corpsObserve = reactor.core.publisher.Flux
                    .<org.springframework.core.io.buffer.DataBuffer>empty()
                    .doOnSubscribe(s -> lu.set(true));

            var requete = MockServerHttpRequest.method(HttpMethod.GET, CHEMIN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(corpsObserve);

            assertThat(transmissions(MockServerWebExchange.from(requete))).isEqualTo(1);
            assertThat(lu)
                    .withFailMessage("le corps d'un GET a été lu : le garde-fou de "
                            + "méthode ne filtre rien")
                    .isFalse();
        }

        @Test
        @DisplayName("un POST annonçant du JSON sans corps est transmis aussi")
        void postSansCorpsTransmis() {
            // Le garde-fou de methode ne couvre pas POST : c'est bien le
            // traitement du corps VIDE qui doit tenir, et non l'exclusion de la
            // methode.
            var requete = MockServerHttpRequest.post(CHEMIN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .build();

            assertThat(transmissions(MockServerWebExchange.from(requete))).isEqualTo(1);
        }

        @Test
        @DisplayName("la requête est transmise UNE SEULE fois")
        void transmiseUneSeuleFois() {
            // Le piege symetrique : poser le defaut sur le Mono<Void> rendu —
            // plutot que sur le Mono<DataBuffer> — ferait parcourir la chaine
            // DEUX fois, puisque `chain.filter` se termine vide quand tout va
            // bien. Ce depot a deja corrige cette erreur neuf fois.
            var requete = MockServerHttpRequest.post(CHEMIN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"phoneNumber\":\"+22300000000\"}");

            assertThat(transmissions(MockServerWebExchange.from(requete))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Le scan reste actif sur ce qui porte un corps")
    class ScanActif {

        @Test
        @DisplayName("un corps sain est transmis")
        void corpsSainTransmis() {
            var requete = MockServerHttpRequest.post(CHEMIN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"firstName\":\"Amadou\",\"lastName\":\"Traoré\"}");

            assertThat(transmissions(MockServerWebExchange.from(requete))).isEqualTo(1);
        }

        @ParameterizedTest(name = "corps hostile : {0}")
        @ValueSource(strings = {
                "{\"x\":\"<script>alert(1)</script>\"}",
                "{\"x\":\"javascript:alert(1)\"}",
        })
        @DisplayName("un corps hostile est refusé et NON transmis")
        void corpsHostileRefuse(String corps) {
            // La correction ne doit pas avoir desarme le scan : c'est la raison
            // d'etre du filtre.
            var echange = MockServerWebExchange.from(MockServerHttpRequest.post(CHEMIN)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body(corps));

            assertThat(transmissions(echange))
                    .withFailMessage("un corps hostile a été transmis au service aval")
                    .isZero();
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("requête bloquée sans statut : le client "
                            + "attendrait jusqu'à son délai d'expiration")
                    .isNotNull();
        }

        @Test
        @DisplayName("un corps sans Content-Type scannable passe sans scan")
        void corpsNonScannable() {
            var requete = MockServerHttpRequest.post(CHEMIN)
                    .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
                    .body("<script>alert(1)</script>");

            // Hors JSON et formulaire, le filtre ne scanne pas — choix d'origine,
            // conserve : le point est seulement que la requete ne disparaisse pas.
            assertThat(transmissions(MockServerWebExchange.from(requete))).isEqualTo(1);
        }
    }
}
