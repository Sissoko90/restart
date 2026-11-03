package com.abdatytch.auth_service.filter;

import com.abdatytch.commons.security.HMACSignatureUtil;
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

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Le rempart qui garantit qu'aucune requête n'atteint auth-service sans passer
 * par le Gateway.
 *
 * <h2>Ce que ce filtre porte à lui seul</h2>
 *
 * L'architecture repose sur un invariant : le Gateway est la <b>seule</b> porte
 * d'entrée. Tout ce qui s'y applique — liste noire d'adresses, limitation de
 * débit, blocage géographique, détection d'injection — ne vaut que si personne
 * ne peut appeler auth-service en direct. Ce filtre est ce qui rend cet
 * invariant vrai, et c'est le seul.
 *
 * <h2>Les deux défauts trouvés ici</h2>
 *
 * <ol>
 *   <li>{@code endsWith("/error")} exemptait de signature <b>et</b> de contrôle
 *       d'adresse tout chemin se terminant ainsi, par exemple
 *       {@code /admin/accounts/error} ;</li>
 *   <li>{@code ip.startsWith("172.")} autorisait 172.0.0.0/8 — 16,7 millions
 *       d'adresses — alors que la plage privée s'arrête à 172.31. Le reste est
 *       de l'espace public routable.</li>
 * </ol>
 *
 * <p>Le second compte d'autant plus que, comme l'explique la javadoc du filtre,
 * le secret HMAC a été committé dans le dépôt et doit être tenu pour connu : le
 * contrôle d'adresse est le dernier rempart, pas une ceinture de sécurité.
 */
@DisplayName("GatewayAccessWebFilter - la seule porte d'entrée")
class GatewayAccessWebFilterTest {

    private static final String SECRET = "secret-de-test";
    private static final String HORODATAGE = "1700000000000";
    private static final String SIGNATURE = "signature-valide";
    private static final String ID_REQUETE = "req-1";

    private HMACSignatureUtil hmac;
    private GatewayAccessWebFilter filtre;

    @BeforeEach
    void setUp() {
        hmac = Mockito.mock(HMACSignatureUtil.class);
        when(hmac.isTimestampValid(anyString())).thenReturn(true);
        when(hmac.buildSignatureData(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("donnees");
        when(hmac.validateSignature(anyString(), anyString(), anyString())).thenReturn(true);

        // Le secret arrive par le constructeur depuis que ce filtre n'est plus un
        // bean : un @Value sur l'un de ses champs ne serait plus renseigne.
        filtre = new GatewayAccessWebFilter(hmac, SECRET);
    }

    /**
     * Traverse le filtre depuis l'adresse donnée, avec ou sans en-têtes du
     * Gateway.
     */
    private MockServerWebExchange appel(String chemin, String ip, boolean signee) {
        MockServerHttpRequest.BaseBuilder<?> requete = MockServerHttpRequest.get(chemin)
                .remoteAddress(new InetSocketAddress(ip, 54321));
        if (signee) {
            requete.header("X-Gateway-Timestamp", HORODATAGE)
                    .header("X-Gateway-Signature", SIGNATURE)
                    .header("X-Gateway-Request-Id", ID_REQUETE);
        }

        MockServerWebExchange echange =
                MockServerWebExchange.from((MockServerHttpRequest) requete.build());
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

    private String corps(MockServerWebExchange echange) {
        return echange.getResponse().getBodyAsString().defaultIfEmpty("").block();
    }

    /** Extrait le champ {@code message} du corps JSON du refus. */
    private String messageDe(String json) {
        int debut = json.indexOf("\"message\":\"");
        if (debut < 0) {
            return "";
        }
        debut += "\"message\":\"".length();
        return json.substring(debut, json.indexOf('"', debut));
    }

    /** Une requête refusée doit l'être explicitement, jamais perdue. */
    private void refusee(MockServerWebExchange echange) {
        assertThat(passe(echange))
                .withFailMessage("la requête atteint le service")
                .isFalse();
        assertThat(echange.getResponse().getStatusCode())
                .withFailMessage("requête bloquée sans statut : le client attendrait "
                        + "jusqu'à son délai d'expiration")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ==================================================================
    @Nested
    @DisplayName("Un appel signé du Gateway passe")
    class AppelLegitime {

        @Test
        @DisplayName("adresse privée et signature valide")
        void appelDuGateway() {
            assertThat(passe(appel("/auth/login", "172.18.0.5", true))).isTrue();
        }

        @Test
        @DisplayName("la signature porte le chemin ET la méthode")
        void signaturePorteCheminEtMethode() {
            // Sans le chemin, une signature captee sur /auth/login serait
            // rejouable sur /admin/accounts. Sans la methode, un GET signe
            // autoriserait un DELETE.
            appel("/auth/login", "172.18.0.5", true);

            Mockito.verify(hmac).buildSignatureData(
                    HORODATAGE, "/auth/login", "GET", ID_REQUETE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un appel direct est refusé")
    class AppelDirect {

        @Test
        @DisplayName("une adresse publique est refusée, même signature valide")
        void adressePubliqueRefusee() {
            refusee(appel("/auth/login", "203.0.113.7", true));
        }

        @Test
        @DisplayName("sans les en-têtes du Gateway, l'appel est refusé")
        void sansEnTetes() {
            refusee(appel("/auth/login", "172.18.0.5", false));
        }

        @Test
        @DisplayName("un horodatage expiré est refusé - anti-rejeu")
        void horodatageExpire() {
            // Sans cette fenetre, une signature captee une fois resterait
            // valable indefiniment.
            when(hmac.isTimestampValid(anyString())).thenReturn(false);

            refusee(appel("/auth/login", "172.18.0.5", true));
        }

        @Test
        @DisplayName("une signature invalide est refusée")
        void signatureInvalide() {
            when(hmac.validateSignature(anyString(), anyString(), anyString()))
                    .thenReturn(false);

            refusee(appel("/auth/login", "172.18.0.5", true));
        }

        @Test
        @DisplayName("le corps du refus ne dit pas LEQUEL des contrôles a échoué")
        void refusNonBavard() {
            // DEFAUT CORRIGE. Le corps portait « reason » avec le motif exact :
            // « Missing required security headers », « Request timestamp
            // expired », « Invalid request signature ».
            //
            // Le plus parlant etait l'horodatage expire : il confirmait a
            // l'appelant que sa signature ET son adresse, elles, etaient
            // acceptees. Autrement dit, il indiquait qu'il ne restait qu'a
            // rafraichir l'horodatage.
            String sansEnTetes = corps(appel("/auth/login", "172.18.0.5", false));

            when(hmac.isTimestampValid(anyString())).thenReturn(false);
            String horodatageExpire = corps(appel("/auth/login", "172.18.0.5", true));

            when(hmac.isTimestampValid(anyString())).thenReturn(true);
            when(hmac.validateSignature(anyString(), anyString(), anyString()))
                    .thenReturn(false);
            String signatureInvalide = corps(appel("/auth/login", "172.18.0.5", true));

            // Le champ `timestamp` du corps est l'horodatage de la REPONSE : il
            // ne renseigne sur rien. Ce qui ne doit pas y figurer, c'est le
            // motif — donc le champ `reason` et le texte qu'il portait.
            assertThat(sansEnTetes)
                    .doesNotContain("reason")
                    .doesNotContain("Missing required security headers");
            assertThat(horodatageExpire)
                    .doesNotContain("reason")
                    .doesNotContain("Request timestamp expired");
            assertThat(signatureInvalide)
                    .doesNotContain("reason")
                    .doesNotContain("Invalid request signature");
        }

        @Test
        @DisplayName("trois causes différentes donnent le même message")
        void messageUniforme() {
            // L'assertion decisive : toute difference entre ces trois reponses
            // est un oracle sur l'etat d'avancement de l'appelant.
            String a = messageDe(corps(appel("/auth/login", "203.0.113.7", true)));

            String b = messageDe(corps(appel("/auth/login", "172.18.0.5", false)));

            when(hmac.validateSignature(anyString(), anyString(), anyString()))
                    .thenReturn(false);
            String c = messageDe(corps(appel("/auth/login", "172.18.0.5", true)));

            assertThat(a)
                    .withFailMessage("le message varie selon la cause du refus")
                    .isEqualTo(b)
                    .isEqualTo(c)
                    .isNotEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : les plages d'adresses")
    class PlagesDAdresses {

        @ParameterizedTest(name = "{0} est privée - acceptée")
        @ValueSource(strings = {
                "10.0.0.1", "10.255.255.254",
                "192.168.1.10",
                "172.16.0.1", "172.17.0.2", "172.31.255.254",
                "127.0.0.1"
        })
        @DisplayName("les plages réellement privées restent acceptées")
        void plagesPriveesAcceptees(String ip) {
            // Resserrer le controle ne doit pas couper le Gateway lui-meme :
            // Docker attribue par defaut dans 172.17 a 172.31.
            assertThat(passe(appel("/auth/login", ip, true)))
                    .withFailMessage("%s est refusée alors qu'elle est privée : "
                            + "le Gateway ne peut plus joindre le service", ip)
                    .isTrue();
        }

        @ParameterizedTest(name = "{0} est PUBLIQUE - refusée")
        @ValueSource(strings = {
                "172.0.0.1",
                "172.15.255.254",
                "172.32.0.1",
                "172.217.16.1",
                "172.255.255.255"
        })
        @DisplayName("172.x HORS de 172.16-172.31 est de l'espace public")
        void plage172PubliqueRefusee(String ip) {
            // LE DEFAUT. `startsWith("172.")` autorisait 172.0.0.0/8, soit 16,7
            // millions d'adresses, alors que la plage privee est 172.16.0.0/12.
            // 172.217.16.1 appartient a Google.
            //
            // La javadoc du filtre explique que le secret HMAC a ete committe et
            // doit etre tenu pour connu : ce controle est le dernier rempart.
            // Il acceptait 93 % d'une plage publique.
            refusee(appel("/auth/login", ip, true));
        }

        @Test
        @DisplayName("une adresse publique proche d'une privée n'est pas acceptée")
        void voisinageTrompeur() {
            // 100.64.x.x ne doit pas passer le test de « 10. » : le point
            // compte. Ce test verrouille le prefixe qui, lui, etait correct.
            refusee(appel("/auth/login", "100.64.0.1", true));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : les chemins exemptés")
    class CheminsExemptes {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/actuator/health/readiness",
                "/actuator/info",
                "/actuator/prometheus",
                "/error"
        })
        @DisplayName("les chemins réellement publics passent sans signature")
        void cheminsPublics(String chemin) {
            // Consul interroge /actuator/health toutes les dix secondes et
            // Prometheus racle /actuator/prometheus : ni l'un ni l'autre ne
            // porte de signature. Les refuser ferait croire le service mort.
            assertThat(passe(appel(chemin, "203.0.113.7", false)))
                    .withFailMessage("%s est refusé : la sonde est cassée, et "
                            + "Consul croira le service mort", chemin)
                    .isTrue();
        }

        /**
         * La documentation OpenAPI est désormais <b>fermée</b>.
         *
         * <p>Ces chemins passaient sans signature. Le changement est délibéré,
         * et ce test l'atteste au lieu de disparaître — une fermeture dont plus
         * aucun test ne parle se rouvre au premier qui trouve la documentation
         * injoignable.
         *
         * <p>Deux raisons distinctes :
         * <ul>
         *   <li>la passerelle sert <b>sa propre</b> documentation et n'agrège
         *       pas celle des services ({@code springdoc.swagger-ui.urls} n'est
         *       configuré nulle part). Exempter {@code /v3/api-docs} ici ne
         *       l'ouvrait donc que par <b>accès direct au port</b> — soit
         *       exactement ce que ce filtre existe pour empêcher, et pour un
         *       document qui décrit toute la surface d'API, chemins
         *       d'administration compris ;</li>
         *   <li>{@code /.well-known/jwks.json} n'est <b>pas servi par ce
         *       service</b>. Son seul détenteur est
         *       {@code interface-service.DevKeyManagementController}, l'autorité
         *       JWT ; auth-service ne fait que le consommer. L'exemption
         *       n'ouvrait rien, et c'est pourquoi elle devait partir : une route
         *       {@code /.well-known} ajoutée ici demain naîtrait sans signature
         *       ni jeton, en toute bonne foi puisque la règle semblait prévue
         *       pour elle.</li>
         * </ul>
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html",
                "/webjars/swagger-ui/favicon.png",
                "/.well-known/jwks.json"
        })
        @DisplayName("la documentation et le JWKS ne sont PLUS exemptes de signature")
        void documentationFermee(String chemin) {
            assertThat(passe(appel(chemin, "203.0.113.7", false)))
                    .withFailMessage("%s est exempte de signature. La documentation "
                            + "decrit toute la surface d'API et ne serait joignable que "
                            + "par acces direct au port ; et le JWKS n'est pas servi par "
                            + "ce service.", chemin)
                    .isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/admin/accounts/error",
                "/auth/login/error",
                "/admin/error"
        })
        @DisplayName("un chemin finissant par /error N'EST PAS exempté")
        void cheminFinissantParError(String chemin) {
            // LE DEFAUT. `endsWith("/error")` exemptait de signature ET de
            // controle d'adresse tout chemin se terminant ainsi. Spring n'expose
            // qu'un seul chemin d'erreur, /error.
            //
            // C'est la troisieme occurrence dans ce fichier d'un controle de
            // contournement plus large que son intention, apres deux `contains`
            // deja corriges.
            refusee(appel(chemin, "203.0.113.7", false));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/env",
                "/actuator/loggers",
                "/actuator/heapdump",
                "/actuator/httpexchanges",
                "/actuator/configprops"
        })
        @DisplayName("les actuators sensibles ne sont PAS exemptés")
        void actuatorsSensibles(String chemin) {
            // /actuator/env rendrait les variables d'environnement, dont les
            // secrets ; /actuator/heapdump, la memoire du processus. Exempter
            // /actuator/ en bloc — ce que faisait le code — les ouvrait tous.
            refusee(appel(chemin, "203.0.113.7", false));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/auth/x/actuator/health",
                "/auth/v3/api-docs/secret",
                "/admin/swagger-ui/x"
        })
        @DisplayName("un chemin CONTENANT un chemin public n'est pas exempté")
        void cheminContenantUnCheminPublic(String chemin) {
            // Les `contains` d'origine exemptaient n'importe quel chemin
            // comportant la chaine, ou qu'elle se trouve.
            refusee(appel(chemin, "203.0.113.7", false));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Entrées dégradées")
    class EntreesDegradees {

        @Test
        @DisplayName("une adresse distante absente est refusée, pas acceptée par défaut")
        void adresseAbsente() {
            // getClientIp rend « unknown » : cette valeur ne doit satisfaire
            // aucune plage. Un appelant derriere un transport qui ne renseigne
            // pas le pair ne doit pas beneficier du doute.
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/auth/login")
                            .header("X-Gateway-Timestamp", HORODATAGE)
                            .header("X-Gateway-Signature", SIGNATURE)
                            .header("X-Gateway-Request-Id", ID_REQUETE)
                            .build());
            AtomicBoolean atteinte = new AtomicBoolean(false);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();

            assertThat(atteinte.get())
                    .withFailMessage("une adresse inconnue est acceptée")
                    .isFalse();
        }

        @Test
        @DisplayName("un X-Forwarded-For forgé ne franchit pas le contrôle")
        void enTeteForge() {
            // Le filtre lisait X-Forwarded-For avant l'adresse reelle : envoyer
            // « X-Forwarded-For: 127.0.0.1 » suffisait a faire passer un appel
            // direct pour un appel du Gateway. L'en-tete est fourni par
            // l'appelant ; aucun proxy ne s'interpose ici.
            MockServerWebExchange echange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/auth/login")
                            .remoteAddress(new InetSocketAddress("203.0.113.7", 54321))
                            .header("X-Forwarded-For", "127.0.0.1")
                            .header("X-Real-IP", "127.0.0.1")
                            .header("X-Gateway-Timestamp", HORODATAGE)
                            .header("X-Gateway-Signature", SIGNATURE)
                            .header("X-Gateway-Request-Id", ID_REQUETE)
                            .build());
            AtomicBoolean atteinte = new AtomicBoolean(false);

            filtre.filter(echange, suite -> {
                atteinte.set(true);
                return Mono.empty();
            }).block();

            assertThat(atteinte.get())
                    .withFailMessage("un en-tête forgé par l'appelant franchit le "
                            + "contrôle d'adresse : l'appel direct redevient possible")
                    .isFalse();
        }

        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = {"172.x.0.1", "172.", "172", "172.16", "172.16.0.1.2"})
        @DisplayName("une adresse mal formée est refusée sans erreur 500")
        void adresseMalFormee(String ip) {
            // Integer.parseInt sur un octet non numerique leverait une
            // NumberFormatException non rattrapee : une entree que l'appelant
            // controle ne doit pas produire une defaillance serveur.
            //
            // Ces adresses n'arrivent pas par le reseau mais par la resolution
            // du pair ; le filtre ne doit pas en dependre pour ne pas casser.
            var echange = appel("/auth/login", ip, true);

            assertThat(echange.getResponse().getStatusCode())
                    .withFailMessage("%s produit %s", ip, echange.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
