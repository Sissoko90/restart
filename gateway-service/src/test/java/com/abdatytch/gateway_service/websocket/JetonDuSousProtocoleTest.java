package com.abdatytch.gateway_service.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le jeton annonce dans le sous-protocole WebSocket.
 *
 * <h2>Ce que ce filtre fait, et ce qu'il ne fait pas</h2>
 *
 * Il DEPLACE une valeur d'un en-tete vers un autre pour qu'un navigateur puisse
 * s'abonner au flux : l'API {@code WebSocket} des navigateurs ne permet pas de
 * poser {@code Authorization}, mais permet d'annoncer des sous-protocoles.
 *
 * <p>Il n'accorde AUCUNE confiance : {@code JwtWebFilter} verifie ensuite le
 * jeton comme pour n'importe quelle requete. Ce qui doit donc etre teste n'est
 * pas une validation — c'est que le deplacement ne s'applique QUE dans les
 * quatre cas voulus, et jamais ailleurs.
 */
@DisplayName("JetonDuSousProtocole — le jeton annonce par un navigateur")
class JetonDuSousProtocoleTest {

    private static final String CHEMIN = "/api/v1/notifications-flux";
    private static final String JETON = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhd2EifQ.sig";

    /** Le filtre, et l'echange tel que la chaine suivante le verra. */
    private static ServerWebExchange apresLeFiltre(ServerWebExchange entrant) {
        AtomicReference<ServerWebExchange> vu = new AtomicReference<>();
        StepVerifier.create(new JetonDuSousProtocole().filter(entrant, ex -> {
            vu.set(ex);
            return Mono.empty();
        })).verifyComplete();
        return vu.get();
    }

    private static MockServerWebExchange montee(String chemin, HttpHeaders enTetes) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(chemin).headers(enTetes).build());
    }

    private static HttpHeaders avecSousProtocole(String valeur) {
        HttpHeaders enTetes = new HttpHeaders();
        enTetes.set("Upgrade", "websocket");
        enTetes.set("Connection", "Upgrade");
        if (valeur != null) {
            enTetes.set("Sec-WebSocket-Protocol", valeur);
        }
        return enTetes;
    }

    // ==================================================================
    @Nested
    @DisplayName("Le cas voulu")
    class CasVoulu {

        @Test
        @DisplayName("le jeton annonce devient un en-tete Authorization")
        void jetonDeplace() {
            var apres = apresLeFiltre(montee(CHEMIN,
                    avecSousProtocole(JetonDuSousProtocole.MARQUEUR + ", " + JETON)));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .withFailMessage("""
                            Le jeton annonce dans le sous-protocole n'a pas ete \
                            deplace.

                            Sans ce deplacement, un navigateur ne peut PAS \
                            s'abonner au flux : son API WebSocket ne permet pas \
                            de poser un en-tete, et les trois autres voies sont \
                            ecartees — jeton dans l'URL (journaux d'acces), \
                            cookie (plateforme sans session), premier message \
                            (surface de commande).""")
                    .isEqualTo("Bearer " + JETON);
        }

        @Test
        @DisplayName("le marqueur est reconnu quelle que soit sa casse")
        void marqueurInsensibleALaCasse() {
            var apres = apresLeFiltre(montee(CHEMIN, avecSousProtocole("Bearer, " + JETON)));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isEqualTo("Bearer " + JETON);
        }

        @Test
        @DisplayName("le sous-protocole d'origine n'est pas retire de la requete")
        void sousProtocoleConserve() {
            var apres = apresLeFiltre(montee(CHEMIN,
                    avecSousProtocole(JetonDuSousProtocole.MARQUEUR + ", " + JETON)));

            // Il doit rester : la negociation de la poignee de main en a besoin
            // pour que le serveur REPONDE un sous-protocole, sans quoi le
            // navigateur ferme la connexion lui-meme. RelaisWebSocket le retire
            // de la poignee de main SORTANTE, ce qui est une autre etape.
            assertThat(apres.getRequest().getHeaders().getFirst("Sec-WebSocket-Protocol"))
                    .isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les quatre conditions, et chacune ferme une porte")
    class ConditionsRespectees {

        @Test
        @DisplayName("un Authorization deja present n'est JAMAIS remplace")
        void autorisationExistanteIntouchee() {
            HttpHeaders enTetes = avecSousProtocole(
                    JetonDuSousProtocole.MARQUEUR + ", " + JETON);
            enTetes.set(HttpHeaders.AUTHORIZATION, "Bearer jeton-authentique");

            var apres = apresLeFiltre(montee(CHEMIN, enTetes));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .withFailMessage("""
                            Le filtre a remplace un Authorization fourni.

                            Ce serait la porte : un appelant pourrait imposer \
                            une identite par le sous-protocole alors qu'il en \
                            presente une autre dans l'en-tete, et les couches \
                            suivantes verifieraient la mauvaise.""")
                    .isEqualTo("Bearer jeton-authentique");
        }

        @Test
        @DisplayName("un chemin etranger n'est pas touche, meme avec un jeton annonce")
        void cheminEtranger() {
            var apres = apresLeFiltre(montee("/api/v1/users/moi",
                    avecSousProtocole(JetonDuSousProtocole.MARQUEUR + ", " + JETON)));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .withFailMessage("Le filtre a pose un Authorization sur un chemin "
                            + "qui n'est pas celui du flux : ce mecanisme ne doit "
                            + "exister que la ou un navigateur ne peut pas faire "
                            + "autrement")
                    .isNull();
        }

        @Test
        @DisplayName("sans montee en protocole, rien n'est touche")
        void sansMonteeEnProtocole() {
            HttpHeaders enTetes = new HttpHeaders();
            enTetes.set("Sec-WebSocket-Protocol",
                    JetonDuSousProtocole.MARQUEUR + ", " + JETON);

            var apres = apresLeFiltre(montee(CHEMIN, enTetes));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isNull();
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = {
                "bearer",
                "autre-protocole, " + JETON,
                "bearer, " + JETON + ", surplus",
                "wamp"})
        @DisplayName("une annonce qui n'est pas exactement « bearer, <jeton> » est ignoree")
        void annonceMalFormee(String annonce) {
            var apres = apresLeFiltre(montee(CHEMIN, avecSousProtocole(annonce)));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .withFailMessage("""
                            « %s » a produit un Authorization.

                            Plus de deux valeurs est REFUSE plutot que devine : \
                            accepter « la deuxieme valeur, quelle que soit la \
                            liste » laisserait un client annoncer un \
                            sous-protocole reel suivi d'un jeton, et ce filtre \
                            choisirait a sa place.""", annonce)
                    .isNull();
        }

        @Test
        @DisplayName("sans sous-protocole annonce, rien n'est touche")
        void sansAnnonce() {
            var apres = apresLeFiltre(montee(CHEMIN, avecSousProtocole(null)));

            assertThat(apres.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                    .isNull();
        }
    }
}
