package com.abdatytch.interface_service.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le rempart qui garantit qu'aucune requête n'atteint interface-service sans
 * passer par le Gateway.
 *
 * <h2>Le défaut trouvé ici rendait le service INATTEIGNABLE</h2>
 *
 * Après la validation réussie de la signature HMAC, le filtre vérifiait
 * « la requête vient-elle du Gateway ? » en comparant
 * {@code getClientIp(exchange)} à {@code 127.0.0.1}, {@code ::1} et
 * {@code 10.10.10.47}.
 *
 * <p>Or {@code getClientIp} délègue à {@code GatewayClientIp.of()}, qui rend en
 * priorité l'en-tête {@code X-Client-Ip} — et le Gateway y place l'adresse de
 * l'<b>utilisateur final</b> ({@code GatewaySignatureFilter}, lignes 119 et
 * 148). La comparaison portait donc sur l'adresse du client, jamais sur celle
 * du Gateway.
 *
 * <p>Conséquence mesurable : <b>toute requête venant d'un client distant,
 * acheminée par le Gateway et dont la signature était valide, répondait
 * « Accès direct interdit - Utilisez le Gateway »</b>. Seul un client situé sur
 * la machine passait, ce qui explique que le défaut soit resté invisible en
 * développement local.
 *
 * <p>Un contrôle censé prouver « cette requête vient du Gateway » ne peut pas
 * s'appuyer sur l'en-tête que le Gateway remplit avec l'adresse de quelqu'un
 * d'autre. Le filtre lit désormais le <b>pair TCP</b> : qui s'est réellement
 * connecté, information non forgeable.
 *
 * <h2>L'autre défaut : une adresse de machine en dur</h2>
 *
 * {@code 10.10.10.47} était écrite dans le code. Au-delà de la règle du projet,
 * cela rendait le service injoignable dès que le Gateway changeait d'adresse —
 * et notamment dès qu'il tournait en conteneur, où Docker attribue dans
 * 172.17-172.31. La notion de réseau privé vit maintenant dans
 * {@code platform-commons}.
 */
@DisplayName("GatewayAccessFilter - la seule porte d'entrée d'interface-service")
class GatewayAccessFilterTest {

    private static final String SECRET = "secret-gateway-de-test";
    private static final String ID_REQUETE = "req-42";
    private static final String IP_CLIENT_FINAL = "203.0.113.7";

    private GatewayAccessFilter filtre;

    @BeforeEach
    void setUp() {
        filtre = new GatewayAccessFilter();
        // Le secret arrive par @Value. Ici le filtre est invoque directement sur
        // CETTE instance, donc la reflexion suffit — contrairement a un
        // controleur passe a bindToController, qui n'utilise pas l'instance
        // fournie.
        ReflectionTestUtils.setField(filtre, "gatewaySecretKey", SECRET);
    }

    /** Signature que le Gateway calculerait : {@code timestamp|path|method|requestId}. */
    private String signer(String horodatage, String chemin, String methode) {
        try {
            String donnees = String.format("%s|%s|%s|%s",
                    horodatage, chemin, methode, ID_REQUETE);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(donnees.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Requête telle que le Gateway l'émet : signature valide, en-tête de source,
     * et {@code X-Client-Ip} portant l'adresse de l'utilisateur final.
     *
     * @param ipDuPair adresse du pair TCP — celle du Gateway lui-même
     * @param ipClient valeur de {@code X-Client-Ip}, ou {@code null} pour l'omettre
     */
    private MockServerWebExchange depuisLeGateway(String chemin, String ipDuPair,
                                                  String ipClient) {
        String horodatage = String.valueOf(System.currentTimeMillis());
        MockServerHttpRequest.BaseBuilder<?> requete = MockServerHttpRequest.get(chemin)
                .remoteAddress(new InetSocketAddress(ipDuPair, 54321))
                .header("X-Gateway-Timestamp", horodatage)
                .header("X-Gateway-Request-Id", ID_REQUETE)
                .header("X-Gateway-Signature", signer(horodatage, chemin, "GET"))
                .header("X-Gateway-Source", "spring-cloud-gateway");
        if (ipClient != null) {
            requete.header("X-Client-Ip", ipClient);
        }
        return MockServerWebExchange.from((MockServerHttpRequest) requete.build());
    }

    /** Traverse le filtre et rend {@code true} si la suite a été atteinte. */
    private boolean passe(MockServerWebExchange echange) {
        AtomicBoolean atteinte = new AtomicBoolean(false);
        filtre.filter(echange, suite -> {
            atteinte.set(true);
            return Mono.empty();
        }).block();

        if (!atteinte.get()) {
            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("requête bloquée sans statut de refus : "
                            + "le client attendrait jusqu'à son délai d'expiration")
                    .isNotNull();
        }
        return atteinte.get();
    }

    /**
     * Appel de service a service, SANS aucun en-tete du Gateway.
     *
     * @param ipDuPair adresse du pair TCP — celle du service appelant
     */
    private MockServerWebExchange appelInterne(String chemin, String ipDuPair) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(chemin)
                .remoteAddress(new InetSocketAddress(ipDuPair, 54321))
                .build());
    }

    // ==================================================================
    @Nested
    @DisplayName("Appels de service a service : /internal/**")
    class AppelsInternes {

        /**
         * Le blocage structurel que cette exemption leve.
         *
         * <p>interface-service est l'autorite JWT : <b>tout</b> jeton de la
         * plateforme est signe par {@code POST /internal/jwt/sign}, appele
         * directement par auth-service. Ce filtre exigeait les en-tetes du
         * Gateway sur ce chemin, et le Gateway n'expose aucune route vers
         * {@code /internal/**} — il n'y avait donc <b>aucun</b> chemin par
         * lequel un jeton pouvait etre signe.
         *
         * <p>Mesure faite sur l'environnement de developpement :
         * {@code POST /internal/jwt/sign} rendait 403 « Acces direct interdit »,
         * et aucune connexion n'aboutissait — ni administrateur, ni utilisateur
         * final. Le symptome visible etait un 500 a la connexion.
         */
        @ParameterizedTest(name = "pair prive {0}")
        @ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.5", "172.21.0.2", "192.168.1.10"})
        @DisplayName("un pair prive passe sans en-tete du Gateway")
        void pairPriveSansEnTete(String ipDuPair) {
            assertThat(passe(appelInterne("/internal/jwt/sign", ipDuPair)))
                    .withFailMessage("l'appel interne est refuse : aucun jeton ne peut "
                            + "etre signe, donc aucune connexion n'aboutit")
                    .isTrue();
        }

        @ParameterizedTest(name = "pair public {0}")
        @ValueSource(strings = {"203.0.113.7", "8.8.8.8", "172.32.0.1"})
        @DisplayName("un pair PUBLIC est refuse, meme sur /internal")
        void pairPublicRefuse(String ipDuPair) {
            // L'exemption ne doit pas devenir une porte ouverte si le port du
            // service se retrouve expose sur Internet.
            assertThat(passe(appelInterne("/internal/jwt/sign", ipDuPair))).isFalse();
        }

        @Test
        @DisplayName("l'adresse vient du PAIR, pas d'un en-tete que l'appelant choisit")
        void lAdresseVientDuPair() {
            // X-Client-Ip et X-Forwarded-For sont fournis par l'appelant : s'y
            // fier laisserait n'importe qui se declarer sur la boucle locale.
            var echange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/internal/jwt/sign")
                    .remoteAddress(new InetSocketAddress("203.0.113.7", 54321))
                    .header("X-Client-Ip", "127.0.0.1")
                    .header("X-Forwarded-For", "127.0.0.1")
                    .build());

            assertThat(passe(echange)).isFalse();
        }

        @ParameterizedTest(name = "{0} n''est PAS un chemin interne")
        @ValueSource(strings = {
                "/internalement/x",
                "/internal-x/y",
                "/admin/internal/x",
        })
        @DisplayName("l'exemption ne depasse pas le segment /internal")
        void exemptionLimiteeAuSegment(String chemin) {
            // `startsWith("/internal")` nu exempterait /internalement et
            // /internal-x, c'est-a-dire des chemins que personne n'a declares.
            assertThat(passe(appelInterne(chemin, "127.0.0.1"))).isFalse();
        }

        @Test
        @DisplayName("/internal exactement est exempte")
        void cheminInterneNu() {
            assertThat(passe(appelInterne("/internal", "127.0.0.1"))).isTrue();
        }

        @Test
        @DisplayName("un chemin NON interne exige toujours la signature")
        void lesAutresCheminsResentProteges() {
            // L'exemption ne doit rien relacher ailleurs.
            assertThat(passe(appelInterne("/admin/users", "127.0.0.1"))).isFalse();
            assertThat(passe(appelInterne("/dev/projects", "127.0.0.1"))).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : un client DISTANT passe")
    class ClientDistant {

        @Test
        @DisplayName("une requête d'un client distant, relayée par le Gateway, PASSE")
        void clientDistantRelayeParLeGateway() {
            // CE TEST A TROUVE LE DEFAUT, et c'est l'assertion qui compte.
            //
            // Le pair TCP est le Gateway (adresse privee), et X-Client-Ip porte
            // l'adresse de l'utilisateur final (publique). L'ancien controle
            // comparait cette derniere a localhost et refusait — donc tout
            // client reel etait rejete malgre une signature valide.
            assertThat(passe(depuisLeGateway("/admin/users", "172.21.0.2", IP_CLIENT_FINAL)))
                    .withFailMessage("un client distant relayé par le Gateway est "
                            + "refusé : le service est inatteignable pour tout le "
                            + "monde sauf en local")
                    .isTrue();
        }

        @Test
        @DisplayName("le Gateway sur la boucle locale passe aussi - cas du développement")
        void gatewayEnLocal() {
            // Le seul cas qui fonctionnait avant la correction. Il doit
            // continuer : c'est la configuration de developpement.
            assertThat(passe(depuisLeGateway("/admin/users", "127.0.0.1", "127.0.0.1")))
                    .isTrue();
        }

        @ParameterizedTest(name = "pair {0}")
        @ValueSource(strings = {"172.17.0.3", "172.21.0.2", "10.0.0.5", "192.168.1.20"})
        @DisplayName("le Gateway en conteneur ou sur le réseau local passe")
        void gatewaySurReseauPrive(String ipDuPair) {
            // 172.17-172.31 est la plage que Docker attribue : l'ancien controle
            // n'acceptait que localhost et une adresse en dur, donc la mise en
            // conteneur aurait tout casse.
            assertThat(passe(depuisLeGateway("/admin/users", ipDuPair, IP_CLIENT_FINAL)))
                    .withFailMessage("le Gateway depuis %s est refusé", ipDuPair)
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un appel direct reste refusé")
    class AppelDirect {

        @Test
        @DisplayName("un pair TCP public est refusé, même signature valide")
        void pairPublicRefuse() {
            // Le point essentiel : la correction ne doit pas avoir ouvert la
            // porte. Un appelant direct, meme en possession du secret HMAC,
            // doit etre refuse parce qu'il n'est pas sur le reseau interne.
            var echange = depuisLeGateway("/admin/users", "203.0.113.9", IP_CLIENT_FINAL);

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("un X-Client-Ip forgé en 127.0.0.1 ne fait PAS passer un appel direct")
        void xClientIpForgeNeSuffitPas() {
            // L'ancien controle lisait cet en-tete : un appelant direct le
            // posant a 127.0.0.1 franchissait donc le controle d'adresse. La
            // correction ne lit plus que le pair TCP, que l'appelant ne choisit
            // pas.
            var echange = depuisLeGateway("/admin/users", "203.0.113.9", "127.0.0.1");

            assertThat(passe(echange))
                    .withFailMessage("un en-tête forgé par l'appelant franchit le "
                            + "contrôle : l'appel direct redevient possible")
                    .isFalse();
        }

        @Test
        @DisplayName("sans en-têtes Gateway, l'appel est refusé")
        void sansEnTetes() {
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/admin/users")
                            .remoteAddress(new InetSocketAddress("172.21.0.2", 54321))
                            .build());

            assertThat(passe(echange)).isFalse();
        }

        @Test
        @DisplayName("une signature invalide est refusée")
        void signatureInvalide() {
            String horodatage = String.valueOf(System.currentTimeMillis());
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/admin/users")
                            .remoteAddress(new InetSocketAddress("172.21.0.2", 54321))
                            .header("X-Gateway-Timestamp", horodatage)
                            .header("X-Gateway-Request-Id", ID_REQUETE)
                            .header("X-Gateway-Signature", "signature-forgee")
                            .header("X-Gateway-Source", "spring-cloud-gateway")
                            .build());

            assertThat(passe(echange)).isFalse();
        }

        @Test
        @DisplayName("un horodatage expiré est refusé - anti-rejeu")
        void horodatageExpire() {
            // Sans cette fenetre de cinq secondes, une signature captee une fois
            // resterait valable indefiniment sur le meme chemin.
            String vieux = String.valueOf(System.currentTimeMillis() - 60_000);
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/admin/users")
                            .remoteAddress(new InetSocketAddress("172.21.0.2", 54321))
                            .header("X-Gateway-Timestamp", vieux)
                            .header("X-Gateway-Request-Id", ID_REQUETE)
                            .header("X-Gateway-Signature", signer(vieux, "/admin/users", "GET"))
                            .header("X-Gateway-Source", "spring-cloud-gateway")
                            .build());

            assertThat(passe(echange)).isFalse();
        }

        @Test
        @DisplayName("un horodatage non numérique ne provoque pas de 500")
        void horodatageNonNumerique() {
            // Entree entierement controlee par l'appelant : Long.parseLong y
            // leverait, et un 500 renseigne sur la pile d'appels.
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/admin/users")
                            .remoteAddress(new InetSocketAddress("172.21.0.2", 54321))
                            .header("X-Gateway-Timestamp", "pas-un-nombre")
                            .header("X-Gateway-Request-Id", ID_REQUETE)
                            .header("X-Gateway-Signature", "x")
                            .header("X-Gateway-Source", "spring-cloud-gateway")
                            .build());

            assertThat(passe(echange)).isFalse();
            assertThat(echange.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemins publics")
    class CheminsPublics {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/dev/.well-known/jwks.json",
                "/.well-known/jwks.json"
        })
        @DisplayName("les trois chemins publics passent sans signature")
        void cheminsPublics(String chemin) {
            // JWKS doit rester ouvert : les autres services y lisent les cles de
            // validation, sans jeton ni signature. Le refuser casserait
            // l'authentification de toute la plateforme.
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(chemin)
                            .remoteAddress(new InetSocketAddress("203.0.113.9", 54321))
                            .build());

            assertThat(passe(echange))
                    .withFailMessage("%s est refusé : la découverte de clés ou la "
                            + "sonde de santé est cassée", chemin)
                    .isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/env",
                "/actuator/loggers",
                "/actuator/heapdump",
                "/admin/users/.well-known/jwks.json"
        })
        @DisplayName("un chemin sensible ou contenant un chemin public n'est PAS exempté")
        void cheminsNonExemptes(String chemin) {
            // /actuator/env rendrait les variables d'environnement. Et la liste
            // est comparee par startsWith : un chemin CONTENANT un chemin public
            // ne doit pas en heriter.
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(chemin)
                            .remoteAddress(new InetSocketAddress("203.0.113.9", 54321))
                            .build());

            assertThat(passe(echange))
                    .withFailMessage("%s est exempté de signature", chemin)
                    .isFalse();
        }
    }
}
