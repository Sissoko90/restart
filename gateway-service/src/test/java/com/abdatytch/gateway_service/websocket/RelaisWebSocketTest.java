package com.abdatytch.gateway_service.websocket;

import com.abdatytch.gateway_service.routing.ChoixDInstance;
import com.abdatytch.gateway_service.security.ClientIpResolver;
import com.abdatytch.gateway_service.security.JwtWebFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Le contrat de securite du relais du flux temps reel.
 *
 * <h2>Ce que cette suite exerce, et pourquoi c'est la bonne cible</h2>
 *
 * Un relais WebSocket a une seule maniere d'ouvrir une porte : transmettre les
 * mauvais en-tetes. {@code GestionnaireWebSocket} de notification-service lit
 * l'identite dans son contexte de securite, et sa chaine exige la signature de
 * la passerelle — tout repose donc sur ce que le relais pose dans la poignee de
 * main sortante.
 *
 * <p>Les quatre pieces a verifier :
 *
 * <ul>
 *   <li>un {@code X-Auth-User-Id} fourni par le CLIENT ne passe pas, et est
 *       remplace par le sujet des claims VALIDES. Sans cela, n'importe qui
 *       s'abonnerait au flux de n'importe qui — codes de verification
 *       compris ;</li>
 *   <li>{@code Authorization} passe, sans quoi le service fermerait la session
 *       faute de porteur identifiable ;</li>
 *   <li>les en-tetes {@code X-Gateway-*} passent : le service aval recalcule la
 *       signature, et sans eux la poignee de main serait refusee en 403 ;</li>
 *   <li>{@code X-Forwarded-For} porte l'adresse RESOLUE, jamais celle que le
 *       client annonce.</li>
 * </ul>
 *
 * <p>Et deux cas de non-interference : un chemin etranger et une requete sans
 * montee en protocole doivent continuer la chaine, faute de quoi ce filtre
 * capturerait du trafic qui ne lui appartient pas.
 */
@DisplayName("RelaisWebSocket — ce qui franchit la poignee de main")
class RelaisWebSocketTest {

    private static final String CHEMIN = "/api/v1/notifications-flux";
    private static final String IP_RESOLUE = "203.0.113.7";
    private static final String SUJET = "compte-authentique";

    /** Un resolveur qui rend toujours l'adresse que la passerelle a etablie. */
    private static ClientIpResolver resolveurFixe() {
        ClientIpResolver resolveur = mock(ClientIpResolver.class);
        org.mockito.Mockito.when(resolveur.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(IP_RESOLUE);
        return resolveur;
    }

    private static RelaisWebSocket relais() {
        return new RelaisWebSocket(mock(ChoixDInstance.class), resolveurFixe());
    }

    /** Des claims VALIDES, tels que JwtWebFilter les pose. */
    private static Claims claims(String sujet, String role) {
        Map<String, Object> contenu = new HashMap<>();
        contenu.put(Claims.SUBJECT, sujet);
        contenu.put("roles", List.of(role));
        return new DefaultClaims(contenu);
    }

    private static ServerWebExchange echangeAvec(HttpHeaders enTetes, Claims claims) {
        MockServerHttpRequest.BaseBuilder<?> constructeur =
                MockServerHttpRequest.method(HttpMethod.GET, CHEMIN).headers(enTetes);
        MockServerWebExchange echange = MockServerWebExchange.from(constructeur.build());
        if (claims != null) {
            echange.getAttributes().put(JwtWebFilter.VALIDATED_CLAIMS_ATTRIBUTE, claims);
        }
        return echange;
    }

    private static HttpHeaders poigneeDeMain() {
        HttpHeaders enTetes = new HttpHeaders();
        enTetes.set("Upgrade", "websocket");
        enTetes.set("Connection", "Upgrade");
        enTetes.set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        enTetes.set("Sec-WebSocket-Version", "13");
        return enTetes;
    }

    // ==================================================================
    @Nested
    @DisplayName("L'identite ne vient JAMAIS du client")
    class IdentiteDesClaims {

        @Test
        @DisplayName("un X-Auth-User-Id fourni par le client est ecrase")
        void identiteDuClientEcrasee() {
            HttpHeaders entrants = poigneeDeMain();
            entrants.set("X-Auth-User-Id", "compte-usurpe");

            HttpHeaders relayes = relais().enTetesARelayer(
                    echangeAvec(entrants, claims(SUJET, "USER")));

            assertThat(relayes.getFirst("X-Auth-User-Id"))
                    .withFailMessage("""
                            L'identite annoncee par le client a franchi le relais.

                            notification-service lit le destinataire dans son \
                            contexte de securite, mais un en-tete d'identite \
                            recopie depuis le client est exactement ce qui \
                            permet de s'abonner au flux d'autrui — codes de \
                            verification compris.""")
                    .isEqualTo(SUJET);
        }

        @Test
        @DisplayName("le role vient des claims, pas de l'en-tete du client")
        void roleDesClaims() {
            HttpHeaders entrants = poigneeDeMain();
            entrants.set("X-User-Role", "SUPER_ADMIN");

            HttpHeaders relayes = relais().enTetesARelayer(
                    echangeAvec(entrants, claims(SUJET, "USER")));

            assertThat(relayes.getFirst("X-User-Role")).isEqualTo("USER");
        }

        @Test
        @DisplayName("sans claims, aucun en-tete d'identite n'est pose")
        void sansClaimsAucuneIdentite() {
            HttpHeaders entrants = poigneeDeMain();
            entrants.set("X-Auth-User-Id", "compte-usurpe");

            HttpHeaders relayes = relais().enTetesARelayer(echangeAvec(entrants, null));

            // Ni l'identite du client, ni une identite inventee. Le service
            // aval fermera la session, ce qui est le comportement voulu : mieux
            // vaut un flux refuse qu'un flux attribue au hasard.
            assertThat(relayes.getFirst("X-Auth-User-Id")).isNull();
            assertThat(relayes.getFirst("X-User-Role")).isNull();
        }

        @Test
        @DisplayName("X-Forwarded-For porte l'adresse resolue, pas celle annoncee")
        void adresseResolue() {
            HttpHeaders entrants = poigneeDeMain();
            entrants.set("X-Forwarded-For", "10.0.0.1");

            HttpHeaders relayes = relais().enTetesARelayer(
                    echangeAvec(entrants, claims(SUJET, "USER")));

            assertThat(relayes.getFirst("X-Forwarded-For")).isEqualTo(IP_RESOLUE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui doit passer, et ce qui ne doit pas")
    class EnTetesTransmis {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Authorization", "X-Gateway-Timestamp", "X-Gateway-Signature",
                "X-Gateway-Request-Id", "X-Gateway-Source"})
        @DisplayName("les en-tetes du porteur et de la signature passent")
        void enTetesEssentiels(String nom) {
            HttpHeaders entrants = poigneeDeMain();
            entrants.set(nom, "valeur-attendue");

            HttpHeaders relayes = relais().enTetesARelayer(
                    echangeAvec(entrants, claims(SUJET, "USER")));

            assertThat(relayes.getFirst(nom))
                    .withFailMessage("""
                            %s n'a pas franchi le relais. Sans Authorization le \
                            service ferme la session ; sans les en-tetes de \
                            signature il refuse la poignee de main en 403, avec \
                            un message parlant de signature et non de relais.""",
                            nom)
                    .isEqualTo("valeur-attendue");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Connection", "Upgrade", "Sec-WebSocket-Key",
                "Sec-WebSocket-Version", "Host"})
        @DisplayName("les en-tetes de la poignee de main amont sont retires")
        void enTetesDeLaPoigneeRetires(String nom) {
            HttpHeaders entrants = poigneeDeMain();
            entrants.set("Host", "passerelle.exemple");

            HttpHeaders relayes = relais().enTetesARelayer(
                    echangeAvec(entrants, claims(SUJET, "USER")));

            assertThat(relayes.getFirst(nom))
                    .withFailMessage("""
                            %s a ete transmis. Le client sortant pose les siens, \
                            et Sec-WebSocket-Accept est derive de la clef de \
                            celui qui OUVRE : transmettre celle de l'amont fait \
                            echouer la negociation aval.""", nom)
                    .isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le filtre ne capture que ce qui lui appartient")
    class NonInterference {

        private Mono<Void> passage(ServerWebExchange echange, AtomicBoolean suite) {
            return relais().filter(echange, ex -> {
                suite.set(true);
                return Mono.empty();
            });
        }

        @Test
        @DisplayName("un chemin etranger continue la chaine")
        void cheminEtranger() {
            AtomicBoolean suite = new AtomicBoolean(false);
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/users/moi")
                            .headers(poigneeDeMain()).build());

            StepVerifier.create(passage(echange, suite)).verifyComplete();
            assertThat(suite).isTrue();
        }

        @Test
        @DisplayName("sans montee en protocole, le chemin du flux continue la chaine")
        void sansMonteeEnProtocole() {
            AtomicBoolean suite = new AtomicBoolean(false);
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(CHEMIN).build());

            // Un GET ordinaire sur ce chemin doit finir en 404 par la chaine, et
            // non etre remis a un service de poignee de main qui repondrait par
            // une erreur de negociation incomprehensible.
            StepVerifier.create(passage(echange, suite)).verifyComplete();
            assertThat(suite)
                    .withFailMessage("Le relais a capture une requete qui n'est pas "
                            + "une montee en protocole")
                    .isTrue();
        }

        @ParameterizedTest(name = "Upgrade: {0}")
        @ValueSource(strings = {"websocket", "WebSocket", "WEBSOCKET", " websocket "})
        @DisplayName("la detection de la montee en protocole ignore la casse et les espaces")
        void detectionInsensibleALaCasse(String valeur) {
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(CHEMIN).header("Upgrade", valeur).build());

            assertThat(RelaisWebSocket.estUneMonteeEnProtocole(echange))
                    .withFailMessage("""
                            « %s » n'a pas ete reconnu. La specification ne \
                            contraint pas la casse de cette valeur, et \
                            « Upgrade: WebSocket » est emis par de vrais \
                            clients : une comparaison stricte refuserait leur \
                            abonnement.""", valeur)
                    .isTrue();
        }
    }
}
