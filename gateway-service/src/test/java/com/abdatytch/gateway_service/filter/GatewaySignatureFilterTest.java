package com.abdatytch.gateway_service.filter;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.gateway_service.routing.CheminCible;
import com.abdatytch.gateway_service.security.ClientIpResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature HMAC du Gateway et purge des en-tetes d'identite.
 *
 * <h2>Ce que ce filtre porte</h2>
 *
 * C'est le seul mecanisme qui distingue un appel passe par le Gateway d'un appel
 * direct a un service. Les services aval exigent la signature pour tout ce qui
 * n'est pas une sonde, et {@code /internal/**} n'est protege QUE par elle.
 *
 * <p>Il assure aussi une seconde fonction, aussi importante : <b>purger les
 * en-tetes d'identite fournis par le client</b>.
 * {@code X-Auth-User-Id}, {@code X-User-Role} et {@code X-Client-Ip} sont
 * derives du jeton valide et de l'adresse resolue ; les laisser passer depuis le
 * client reviendrait a lui laisser declarer qui il est.
 *
 * <h2>Les deux defauts corriges</h2>
 *
 * <ol>
 *   <li>Sur un chemin public, le filtre retournait <b>avant la purge</b>. Les
 *       routes re-excluent bien ces en-tetes au moment de transmettre, donc la
 *       defense en profondeur tenait — mais faire reposer la purge sur une
 *       seconde couche alors qu'elle coute une ligne ici est indefendable.</li>
 *   <li>{@code isPublicEndpoint} employait {@code contains} et non
 *       {@code startsWith} : tout chemin comportant {@code /v3/api-docs}
 *       n'importe ou etait exempte de signature, y compris
 *       {@code /api/v1/auth/x/v3/api-docs} qui correspond a une vraie route. La
 *       requete partait alors sans signature et echouait en aval — donc ferme,
 *       pas ouvert. Mais un controle de contournement doit designer exactement
 *       les chemins voulus.</li>
 * </ol>
 */
@DisplayName("GatewaySignatureFilter - signature et purge")
class GatewaySignatureFilterTest {

    private static final String CLE = "cle-de-test-gateway-suffisamment-longue";

    private GatewaySignatureFilter filtre;
    private HMACSignatureUtil hmac;

    @BeforeEach
    void setUp() {
        hmac = new HMACSignatureUtil();
        ClientIpResolver resolveur = Mockito.mock(ClientIpResolver.class);
        Mockito.when(resolveur.resolve(Mockito.any())).thenReturn("203.0.113.7");

        filtre = new GatewaySignatureFilter(hmac, resolveur);
        ReflectionTestUtils.setField(filtre, "secretKey", CLE);
        ReflectionTestUtils.setField(filtre, "adminSecretKey", "cle-admin");
    }

    /** Fait passer une requete dans le filtre et rend la requete transmise. */
    private ServerHttpRequest transmise(MockServerHttpRequest requete) {
        MockServerWebExchange echange = MockServerWebExchange.from(requete);
        AtomicReference<ServerHttpRequest> vue = new AtomicReference<>();

        filtre.filter(echange, suite -> {
            vue.set(suite.getRequest());
            return Mono.empty();
        }).block();

        return vue.get();
    }

    // ==================================================================
    @Nested
    @DisplayName("Signature")
    class Signature {

        @Test
        @DisplayName("les trois en-tetes de signature sont poses")
        void enTetesPoses() {
            var r = transmise(MockServerHttpRequest.post("/api/v1/auth/login").build());

            assertThat(r.getHeaders().getFirst("X-Gateway-Timestamp")).isNotBlank();
            assertThat(r.getHeaders().getFirst("X-Gateway-Signature")).isNotBlank();
            assertThat(r.getHeaders().getFirst("X-Gateway-Request-Id")).isNotBlank();
        }

        @Test
        @DisplayName("la signature est verifiable avec la meme cle et le chemin TRANSFORME")
        void signatureVerifiable() {
            // Le point le plus facile a casser de tout le mecanisme : le Gateway
            // signe le chemin APRES retrait du prefixe /api/v1, parce que c'est
            // ce chemin-la que le service recevra. Signer le chemin d'origine
            // ferait echouer toutes les verifications en aval, et le symptome
            // serait un 403 generalise sans rapport apparent avec la cause.
            var r = transmise(MockServerHttpRequest.post("/api/v1/auth/login").build());

            String horodatage = r.getHeaders().getFirst("X-Gateway-Timestamp");
            String requestId = r.getHeaders().getFirst("X-Gateway-Request-Id");
            String signature = r.getHeaders().getFirst("X-Gateway-Signature");

            String attendue = hmac.generateSignature(CLE,
                    hmac.buildSignatureData(horodatage, "/auth/login", "POST", requestId));

            assertThat(signature)
                    .withFailMessage("la signature ne porte pas sur le chemin transforme : "
                            + "les services aval la refuseront tous")
                    .isEqualTo(attendue);
        }

        /**
         * L'invariant le plus couteux a casser de la plateforme.
         *
         * <p>Le Gateway signe le chemin que le service recevra, et le service
         * recalcule la signature sur le chemin recu. Le filtre et le routeur
         * doivent donc calculer <b>exactement</b> le meme chemin. Ils le
         * faisaient chacun de leur cote, par un
         * {@code replaceFirst("/api/v1", "")} duplique ; des l'arrivee d'une
         * reecriture, un ecart d'un caractere refuse chaque requete concernee en
         * 403 — et le message parle de signature invalide, jamais de chemin.
         *
         * <p>Les deux passent desormais par {@code CheminCible}. Ce test
         * verifie que le filtre y passe vraiment, sur un chemin REECRIT et non
         * seulement prefixe : avec un simple retrait de prefixe, la signature
         * porterait sur {@code /auth-admin/...} et auth-service, qui recoit
         * {@code /admin/...}, refuserait tout.
         */
        @Test
        @DisplayName("la signature porte sur le chemin REECRIT, pas seulement deprefixe")
        void signatureSurLeCheminReecrit() {
            var r = transmise(MockServerHttpRequest
                    .post("/api/v1/auth-admin/accounts/42/unlock").build());

            String horodatage = r.getHeaders().getFirst("X-Gateway-Timestamp");
            String requestId = r.getHeaders().getFirst("X-Gateway-Request-Id");
            String cheminAttendu = CheminCible.pour("/api/v1/auth-admin/accounts/42/unlock");

            assertThat(cheminAttendu)
                    .as("la reecriture doit avoir eu lieu")
                    .isEqualTo("/admin/accounts/42/unlock");
            assertThat(r.getHeaders().getFirst("X-Gateway-Signature"))
                    .withFailMessage("la signature ne porte pas sur le chemin que "
                            + "auth-service recevra : il refusera tout en 403")
                    .isEqualTo(hmac.generateSignature(CLE, hmac.buildSignatureData(
                            horodatage, cheminAttendu, "POST", requestId)));
        }

        @Test
        @DisplayName("chaque requete recoit une signature differente")
        void signatureUnique() {
            // Horodatage et identifiant de requete entrent dans la signature :
            // deux appels identiques ne doivent pas produire la meme, sinon une
            // signature capturee serait rejouable a volonte.
            var a = transmise(MockServerHttpRequest.post("/api/v1/auth/login").build());
            var b = transmise(MockServerHttpRequest.post("/api/v1/auth/login").build());

            assertThat(a.getHeaders().getFirst("X-Gateway-Request-Id"))
                    .isNotEqualTo(b.getHeaders().getFirst("X-Gateway-Request-Id"));
            assertThat(a.getHeaders().getFirst("X-Gateway-Signature"))
                    .isNotEqualTo(b.getHeaders().getFirst("X-Gateway-Signature"));
        }

        @Test
        @DisplayName("X-Admin-Secret n'est pose que sur les chemins d'administration")
        void secretAdminCible() {
            var admin = transmise(MockServerHttpRequest.get("/api/v1/admin/accounts/x").build());
            var ordinaire = transmise(MockServerHttpRequest.post("/api/v1/auth/login").build());

            assertThat(admin.getHeaders().getFirst("X-Admin-Secret")).isEqualTo("cle-admin");
            assertThat(ordinaire.getHeaders().getFirst("X-Admin-Secret"))
                    .withFailMessage("le secret d'administration part sur un chemin ordinaire")
                    .isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Purge des en-tetes d'identite")
    class Purge {

        @ParameterizedTest(name = "{0} fourni par le client est purge")
        @ValueSource(strings = {
                "X-Auth-User-Id",
                "X-User-Role",
                "X-Gateway-Source",
                "X-Secret-Key",
                "X-Admin-Secret",
                "X-Forwarded-For",
                "X-Real-IP",
                "X-Client-Ip"
        })
        @DisplayName("un en-tete d'identite fourni par le client ne survit pas")
        void enTeteClientPurge(String enTete) {
            var r = transmise(MockServerHttpRequest.post("/api/v1/auth/login")
                    .header(enTete, "valeur-de-l-attaquant")
                    .build());

            assertThat(r.getHeaders().getFirst(enTete))
                    .withFailMessage("%s fourni par le client a survecu : "
                            + "il pourrait declarer sa propre identite", enTete)
                    .isNotEqualTo("valeur-de-l-attaquant");
        }

        @Test
        @DisplayName("X-Client-Ip est remplace par l'adresse resolue, jamais celle du client")
        void ipResolueRemplaceCelleDuClient() {
            // Les services aval journalisent cette adresse dans le registre
            // d'authentification et s'en servent pour les plafonds par IP. Si
            // elle venait du client, la piste d'audit ne vaudrait rien et tout
            // plafond se contournerait en faisant varier l'en-tete.
            var r = transmise(MockServerHttpRequest.post("/api/v1/auth/login")
                    .header("X-Client-Ip", "1.2.3.4")
                    .header("X-Forwarded-For", "5.6.7.8")
                    .build());

            assertThat(r.getHeaders().getFirst("X-Client-Ip")).isEqualTo("203.0.113.7");
            assertThat(r.getHeaders().getFirst("X-Forwarded-For")).isNull();
        }

        @Test
        @DisplayName("un en-tete ordinaire est conserve")
        void enTeteOrdinaireConserve() {
            // La purge doit rester chirurgicale : retirer l'Authorization ou le
            // Content-Type casserait tout.
            var r = transmise(MockServerHttpRequest.post("/api/v1/auth/login")
                    .header("Authorization", "Bearer jeton")
                    .header("X-Correlation-Id", "trace-1")
                    .build());

            assertThat(r.getHeaders().getFirst("Authorization")).isEqualTo("Bearer jeton");
            assertThat(r.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("trace-1");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemins publics")
    class Publics {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/v3/api-docs",
                "/swagger-ui/index.html",
                "/webjars/x.js"
        })
        @DisplayName("un chemin public n'est pas signe")
        void pasDeSignature(String chemin) {
            var r = transmise(MockServerHttpRequest.get(chemin).build());

            assertThat(r.getHeaders().getFirst("X-Gateway-Signature"))
                    .withFailMessage("%s est signe alors qu'il est public", chemin)
                    .isNull();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/actuator/health",
                "/v3/api-docs",
                "/swagger-ui/index.html"
        })
        @DisplayName("un chemin public est QUAND MEME purge")
        void purgeMemeSurCheminPublic(String chemin) {
            // LE premier defaut corrige : le filtre retournait avant la purge.
            var r = transmise(MockServerHttpRequest.get(chemin)
                    .header("X-Auth-User-Id", "victime")
                    .header("X-User-Role", "ADMIN")
                    .build());

            assertThat(r.getHeaders().getFirst("X-Auth-User-Id"))
                    .withFailMessage("%s laisse passer X-Auth-User-Id du client", chemin)
                    .isNull();
            assertThat(r.getHeaders().getFirst("X-User-Role")).isNull();
        }

        @ParameterizedTest(name = "{0} EST signe")
        @ValueSource(strings = {
                "/api/v1/auth/x/v3/api-docs",
                "/api/v1/users/swagger-ui/me",
                "/api/v1/auth/webjars/profile"
        })
        @DisplayName("un chemin de service comportant une chaine de documentation reste signe")
        void cheminDeServiceToujoursSigne(String chemin) {
            // LE second defaut corrige. Avec `contains`, ces chemins etaient
            // exemptes de signature tout en correspondant a de vraies routes.
            // Ils echouaient en aval faute de signature — donc ferme — mais la
            // portee de l'exemption ne correspondait pas a son intention.
            var r = transmise(MockServerHttpRequest.get(chemin).build());

            assertThat(r.getHeaders().getFirst("X-Gateway-Signature"))
                    .withFailMessage("%s n'est pas signe : l'exemption de "
                            + "documentation s'applique a un chemin de service", chemin)
                    .isNotBlank();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ordre d'execution")
    class Ordre {

        @Test
        @DisplayName("le filtre passe avant Spring Security")
        void avantSpringSecurity() {
            // Spring Security s'installe a -100. A egalite, l'ordre entre les
            // deux n'etait pas defini, et les en-tetes d'identite doivent etre
            // purges avant que quoi que ce soit ne les lise.
            assertThat(filtre.getOrder())
                    .withFailMessage("le filtre ne passe pas avant Spring Security (-100)")
                    .isLessThan(-100);
        }
    }
}
