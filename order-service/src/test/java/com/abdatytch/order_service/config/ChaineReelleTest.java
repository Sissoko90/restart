package com.abdatytch.order_service.config;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.commons.security.DecodeurJwtDeLaPlateforme;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.order_service.constants.CheminsDesCommandes;
import com.abdatytch.order_service.security.GatewayAccessWebFilter;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La chaine de securite d'order-service, du pair TCP jusqu'au role.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Les controleurs, le filtre de passerelle et la chaine de securite n'etaient
 * verifies que par la COMPILATION. Une regle d'autorisation placee apres
 * {@code anyExchange()} ne serait jamais atteinte, un chemin public oublie
 * exposerait un panier, et rien ne le dirait : le service demarre, repond, et
 * la faille ne se voit qu'en l'exploitant.
 *
 * <h2>Un jeton signe par une cle de TEST</h2>
 *
 * La paire RSA est engendree a l'execution, et le decodeur du contexte de test
 * porte la cle publique correspondante. Aucun secret reel n'est lu : obtenir un
 * vrai jeton d'administrateur demanderait le second facteur TOTP de la
 * personne, et lire {@code totp_secret} en base pour le calculer serait
 * contourner un controle d'authentification.
 *
 * <p>Ce montage teste de surcroit PLUS qu'un appel manuel : il peut forger un
 * jeton expire, un jeton d'un autre emetteur, ou un jeton sans role — ce qu'un
 * vrai jeton ne permet pas.
 *
 * <h2>Les routes sont remplacees par un attrape-tout</h2>
 *
 * Le but est la CHAINE, pas les controleurs : un {@code RouterFunction} unique
 * rend 200 sur n'importe quel chemin. Tout ce qui n'est pas 200 vient donc d'un
 * controle de securite, et non d'un defaut de routage ou de validation.
 */
@DisplayName("order-service — la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET_PASSERELLE = "secret-de-passerelle-pour-test";
    private static final String SECRET_INTERNE = "secret-interne-pour-test";
    private static final String ACHETEUR = "22222222-2222-2222-2222-222222222222";
    private static final String EMETTEUR = "interface-service";

    private static final String PANIER = CheminsDesCommandes.PANIER;
    private static final String COMMANDES = CheminsDesCommandes.COMMANDES;
    private static final String BOUTIQUE = CheminsDesCommandes.BOUTIQUE + "/commandes";
    private static final String ADMIN_COMMANDES = CheminsDesCommandes.ADMIN_COMMANDES;
    private static final String ADMIN_RETOURS = CheminsDesCommandes.ADMIN_RETOURS;
    private static final String INTERNE = "/internal/quelque-chose";
    private static final String SONDE = "/actuator/health";

    private static KeyPair paireDeCles;
    private WebTestClient client;

    @BeforeAll
    static void engendrerLesCles() {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();
    }

    @EnableWebFlux
    @Import({SecurityConfig.class, GatewayAccessWebFilter.class})
    @TestConfiguration
    static class ConfigurationDeTest {

        /**
         * Le decodeur de test porte la cle PUBLIQUE de la paire engendree.
         *
         * <p>{@code @Primary} : {@code SecurityConfig} declare son propre
         * decodeur, qui irait chercher un JWKS a une adresse inexistante en
         * test.
         *
         * <p>Il passe par {@code DecodeurJwtDeLaPlateforme} et non par un
         * decodeur nu : c'est lui qui laisse {@code iss} en chaine de
         * caracteres. Sans cela, Spring convertit le claim en {@code URL} AU
         * DECODAGE et un emetteur qui est un NOM — « interface-service » —
         * leve une exception avant toute validation, donc un 500 au lieu d'un
         * 401.
         */
        @Bean
        @Primary
        ReactiveJwtDecoder decodeur() {
            return DecodeurJwtDeLaPlateforme.appliquerLesRegles(
                    NimbusReactiveJwtDecoder
                            .withPublicKey((RSAPublicKey) paireDeCles.getPublic())
                            .build(),
                    EMETTEUR);
        }

        /** L'attrape-tout : ce qui n'est pas 200 vient d'un controle. */
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route(RequestPredicates.all(),
                    requete -> ServerResponse.ok().bodyValue("ok"));
        }

        /**
         * Pose l'adresse du pair TCP, que {@code WebTestClient} ne fournit
         * pas.
         *
         * <p>{@code GatewayAccessWebFilter} exempte les chemins internes
         * APRES avoir verifie que l'appel vient d'un reseau prive. Sans pair,
         * la verification echoue et tous les tests internes rendraient 403
         * pour la mauvaise raison.
         */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        WebFilter posePairLocal() {
            return (echange, suite) -> suite.filter(echange.mutate()
                    .request(new ServerHttpRequestDecorator(echange.getRequest()) {
                        @Override
                        public InetSocketAddress getRemoteAddress() {
                            return new InetSocketAddress("127.0.0.1", 54321);
                        }
                    })
                    .build());
        }
    }

    @BeforeEach
    void monterLeContexte() {
        var contexte = new AnnotationConfigReactiveWebApplicationContext();
        // Les proprietes sont fournies explicitement : hors d'un demarrage
        // Spring Boot, aucun config-repo n'est lu. Le test connait donc le
        // secret EFFECTIF du filtre, et signe avec celui-la.
        contexte.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "proprietes-de-test", Map.of(
                        "gateway.secret.key", SECRET_PASSERELLE,
                        "security.internal-secret", SECRET_INTERNE)));
        contexte.getBeanFactory()
                .registerSingleton("hmacSignatureUtil", new HMACSignatureUtil());
        contexte.register(ConfigurationDeTest.class);
        contexte.refresh();
        client = WebTestClient.bindToApplicationContext(contexte).build();
    }

    // ==================================================================
    // Outillage
    // ==================================================================

    private String jeton(Object roles) {
        return jeton(roles, EMETTEUR, Instant.now().plusSeconds(600));
    }

    private String jeton(Object roles, String emetteur, Instant expiration) {
        var constructeur = Jwts.builder()
                .subject(ACHETEUR)
                .issuer(emetteur)
                .issuedAt(new Date())
                .expiration(Date.from(expiration));
        if (roles != null) {
            constructeur.claim("roles", roles);
        }
        return constructeur.signWith(paireDeCles.getPrivate()).compact();
    }

    private WebTestClient.ResponseSpec appel(HttpMethod methode, String chemin,
                                             String jeton, boolean signee,
                                             String secretInterne) {
        var requete = client.method(methode).uri(chemin);
        if (signee) {
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            var hmac = new HMACSignatureUtil();
            String donnees = hmac.buildSignatureData(
                    horodatage, chemin, methode.name(), idRequete);
            requete = requete
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            hmac.generateSignature(SECRET_PASSERELLE, donnees));
        }
        if (jeton != null) {
            requete = requete.header("Authorization", "Bearer " + jeton);
        }
        if (secretInterne != null) {
            requete = requete.header(AutorisationInterne.EN_TETE_SECRET, secretInterne);
        }
        return requete.exchange();
    }

    private WebTestClient.ResponseSpec signe(String chemin, String jeton) {
        return appel(HttpMethod.GET, chemin, jeton, true, null);
    }

    // ==================================================================

    @Nested
    @DisplayName("Rien n'est public, sauf les sondes")
    class RienNEstPublic {

        /**
         * Un panier et une commande appartiennent a quelqu'un : ils n'ont
         * AUCUN sens sans porteur. Contrairement au catalogue de
         * market-service, la question d'un acces anonyme ne se pose meme pas.
         */
        @Test
        @DisplayName("le panier sans jeton rend 401")
        void panierSansJeton() {
            signe(PANIER, null).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("les commandes sans jeton rendent 401")
        void commandesSansJeton() {
            signe(COMMANDES, null).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("la vue boutique sans jeton rend 401")
        void boutiqueSansJeton() {
            signe(BOUTIQUE, null).expectStatus().isUnauthorized();
        }

        /**
         * La sonde reste publique ET dispensee de signature de passerelle :
         * Consul l'interroge en direct, sans jeton et sans passer par la
         * passerelle. L'exiger rendrait le service perpetuellement « hors
         * service » aux yeux de la decouverte.
         */
        @Test
        @DisplayName("la sonde de sante passe sans jeton NI signature")
        void sondePublique() {
            appel(HttpMethod.GET, SONDE, null, false, null).expectStatus().isOk();
        }
    }

    @Nested
    @DisplayName("Le filtre de passerelle")
    class FiltreDePasserelle {

        /**
         * Un appel direct, sans les en-tetes signes de la passerelle, est
         * refuse meme avec un jeton valide. C'est ce qui empeche de contourner
         * la passerelle — donc la limitation de debit, la liste noire d'IP et
         * le controle de chemin qu'elle porte.
         */
        @Test
        @DisplayName("un appel non signe est refuse meme avec un jeton valide")
        void appelNonSigneRefuse() {
            appel(HttpMethod.GET, PANIER, jeton(List.of("ROLE_USER")), false, null)
                    .expectStatus().isForbidden();
        }

        /** Une signature forgee avec un autre secret ne passe pas. */
        @Test
        @DisplayName("une signature invalide est refusee")
        void signatureInvalideRefusee() {
            String horodatage = String.valueOf(System.currentTimeMillis());
            client.get().uri(PANIER)
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", UUID.randomUUID().toString())
                    .header("X-Gateway-Signature", "signature-forgee")
                    .header("Authorization", "Bearer " + jeton(List.of("ROLE_USER")))
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    @Nested
    @DisplayName("Le role sur les chemins d'administration")
    class RoleAdministration {

        /**
         * {@code /admin/orders/**} porte le constat d'encaissement, le constat
         * de livraison et l'ARBITRAGE des retours : le pouvoir d'imposer un
         * remboursement a une boutique et de decider quand elle est payable.
         *
         * <p>C'est la surface la plus sensible de ce module, et la regle est
         * dans la CHAINE et pas seulement en {@code @PreAuthorize} — ce depot a
         * deja trouve trente-trois annotations rendues inertes par l'absence de
         * celle qui les active.
         */
        @Test
        @DisplayName("un simple utilisateur est refuse sur l'administration des commandes")
        void utilisateurRefuseSurAdminCommandes() {
            signe(ADMIN_COMMANDES, jeton(List.of("ROLE_USER")))
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un simple utilisateur est refuse sur l'arbitrage des retours")
        void utilisateurRefuseSurAdminRetours() {
            signe(ADMIN_RETOURS, jeton(List.of("ROLE_USER")))
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un administrateur passe sur l'administration")
        void administrateurPasse() {
            signe(ADMIN_COMMANDES, jeton(List.of("ROLE_ADMIN")))
                    .expectStatus().isOk();
        }

        /**
         * Le controle a vide de l'administration : un porteur valide passe bien
         * sur les chemins ordinaires. Sans ce test, une chaine qui refuserait
         * TOUT passerait tous les autres — et une protection qui refuse chacun
         * n'est pas une protection.
         */
        @Test
        @DisplayName("un utilisateur authentifie passe sur le panier et les commandes")
        void utilisateurPasseSurSesChemins() {
            signe(PANIER, jeton(List.of("ROLE_USER"))).expectStatus().isOk();
            signe(COMMANDES, jeton(List.of("ROLE_USER"))).expectStatus().isOk();
            signe(BOUTIQUE, jeton(List.of("ROLE_USER"))).expectStatus().isOk();
        }

        /**
         * Le claim de role existe sous DEUX formes dans ce depot : une liste et
         * une chaine unique. Le convertisseur doit accepter les deux, sinon la
         * moitie des jetons perdent leur role sans que rien ne le signale.
         */
        @Test
        @DisplayName("le role en CHAINE unique est reconnu comme la liste")
        void roleEnChaineReconnu() {
            signe(ADMIN_COMMANDES, jeton("ROLE_ADMIN")).expectStatus().isOk();
        }

        /**
         * Un jeton SANS claim de role reste authentifie — il passe sur le
         * panier — mais ne doit pas atteindre l'administration.
         */
        @Test
        @DisplayName("un jeton sans role est authentifie mais pas administrateur")
        void jetonSansRole() {
            signe(PANIER, jeton(null)).expectStatus().isOk();
            signe(ADMIN_COMMANDES, jeton(null)).expectStatus().isForbidden();
        }
    }

    @Nested
    @DisplayName("Ce que le decodeur refuse")
    class DecodeurRefuse {

        /**
         * Un jeton d'un AUTRE emetteur est refuse. Et le code doit etre 401 et
         * non 500 : c'est tout l'objet de {@code DecodeurJwtDeLaPlateforme}.
         * Sans lui, Spring convertit {@code iss} en {@code URL} au decodage, et
         * un emetteur qui est un nom leve une exception avant la validation.
         */
        @Test
        @DisplayName("un autre emetteur rend 401, jamais 500")
        void autreEmetteurRend401() {
            signe(PANIER, jeton(List.of("ROLE_USER"), "un-autre-service",
                    Instant.now().plusSeconds(600)))
                    .expectStatus().isUnauthorized();
        }

        /**
         * Un jeton expire est refuse. L'expiration est posee largement
         * au-dela de la tolerance d'horloge de soixante secondes que
         * {@code JwtTimestampValidator} applique par defaut : a une seconde
         * pres, le jeton serait ACCEPTE, et le test echouerait pour une raison
         * qui n'a rien a voir avec la securite.
         */
        @Test
        @DisplayName("un jeton expire rend 401")
        void jetonExpireRend401() {
            signe(PANIER, jeton(List.of("ROLE_USER"), EMETTEUR,
                    Instant.now().minusSeconds(300)))
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton signe par une autre cle rend 401")
        void autreCleRend401() {
            KeyPair intruse = Jwts.SIG.RS256.keyPair().build();
            String forge = Jwts.builder()
                    .subject(ACHETEUR)
                    .issuer(EMETTEUR)
                    .issuedAt(new Date())
                    .expiration(Date.from(Instant.now().plusSeconds(600)))
                    .claim("roles", List.of("ROLE_ADMIN"))
                    .signWith(intruse.getPrivate())
                    .compact();

            signe(ADMIN_COMMANDES, forge).expectStatus().isUnauthorized();
        }
    }

    @Nested
    @DisplayName("Les chemins internes")
    class CheminsInternes {

        /**
         * Un chemin interne n'est pas atteignable avec un jeton
         * d'utilisateur : ce qui l'autorise est le secret partage, pas un
         * porteur. Un appel de service n'agit pour personne.
         */
        @Test
        @DisplayName("un jeton d'utilisateur ne suffit pas sur /internal")
        void jetonNeSuffitPasEnInterne() {
            appel(HttpMethod.POST, INTERNE, jeton(List.of("ROLE_USER")), false, null)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("le secret interne ouvre /internal")
        void secretOuvreInterne() {
            appel(HttpMethod.POST, INTERNE, null, false, SECRET_INTERNE)
                    .expectStatus().isOk();
        }

        /**
         * Un secret faux est refuse, et le code est 401 — PAS 403.
         *
         * <h3>Les deux codes disent deux choses differentes</h3>
         *
         * <ul>
         *   <li><b>401</b> : rien n'authentifie l'appelant. C'est le cas ici —
         *       pas de jeton, et un secret que {@code AutorisationInterne}
         *       rejette, donc aucune authentification n'est etablie ;</li>
         *   <li><b>403</b> : l'appelant EST authentifie mais n'a pas le droit.
         *       C'est le cas du test voisin, ou un jeton d'utilisateur valide
         *       se presente sur un chemin interne.</li>
         * </ul>
         *
         * <p>Ma premiere version attendait 403 dans les deux cas, et c'etait
         * l'attente qui etait fausse, pas le code. Aplatir les deux en un seul
         * code aurait fait perdre une information de diagnostic reelle : « tu
         * n'es personne » et « tu es quelqu'un, mais pas celui-la » n'appellent
         * pas la meme correction.
         *
         * <p>Aucun des deux ne fuit quoi que ce soit : ni l'un ni l'autre ne
         * dit si le chemin existe.
         */
        @Test
        @DisplayName("un secret interne faux rend 401, et non 403")
        void secretFauxRefuse() {
            appel(HttpMethod.POST, INTERNE, null, false, "pas-le-bon-secret")
                    .expectStatus().isUnauthorized();
        }

        /** Sans secret du tout : meme refus, meme code. */
        @Test
        @DisplayName("aucun secret rend 401 aussi")
        void aucunSecretRefuse() {
            appel(HttpMethod.POST, INTERNE, null, false, null)
                    .expectStatus().isUnauthorized();
        }

        /**
         * Meme un ADMINISTRATEUR n'entre pas par /internal sans le secret. Le
         * role et le secret sont deux mecanismes distincts, et confondre les
         * deux ouvrirait la surface interne a toute personne ayant le role.
         */
        @Test
        @DisplayName("un administrateur n'ouvre pas /internal sans le secret")
        void adminNOuvrePasInterne() {
            appel(HttpMethod.POST, INTERNE, jeton(List.of("ROLE_ADMIN")), false, null)
                    .expectStatus().isForbidden();
        }
    }
}
