package com.abdatytch.quotas_service.config;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.quotas_service.security.GatewayAccessWebFilter;
import com.abdatytch.quotas_service.security.JwtAuthenticationWebFilter;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;

/**
 * La chaîne de sécurité de quotas-service, exercée pour de VRAI.
 *
 * <h2>Le point aveugle que ce test ferme</h2>
 *
 * Ce module n'avait <b>aucun</b> test de sa chaîne HTTP. C'est la lacune qui a
 * coûté le plus cher à ce dépôt : media-service était <b>entièrement
 * injoignable</b> — deux défauts indépendants, chacun suffisant — et aucun test
 * ne le disait, parce qu'aucun test n'appelait un endpoint comme la passerelle
 * l'appelle. La leçon écrite alors : « pas de test de la chaîne HTTP » est un
 * symptôme plus grave que n'importe quelle règle suspecte.
 *
 * <h2>Ce qui est réellement branché</h2>
 *
 * La vraie {@link SecurityConfig} avec ses deux vrais filtres, un vrai
 * {@link HMACSignatureUtil}, et des requêtes signées <b>exactement comme la
 * passerelle les signe</b> — sur {@code horodatage|chemin|méthode|identifiant}.
 * Les jetons sont de vrais JWT RS256.
 *
 * <p>Ce test ne vérifie pas la logique des endpoints — leurs services ont leurs
 * propres suites. Il répond à une seule question : <b>est-ce que ces endpoints
 * répondent quand on les appelle comme la passerelle les appellera</b>.
 *
 * <h2>La matrice vérifiée</h2>
 *
 * Deux surfaces, deux niveaux. {@code /admin/quotas/**} exige le rôle ADMIN ;
 * {@code /quotas/**} exige seulement une identité. Les deux exigent la
 * signature de la passerelle.
 */
@DisplayName("quotas-service - la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET = "secret-de-passerelle-pour-test";
    private static final String EMETTEUR = "interface-service";
    private static final String AUDIENCE = "quotas-service";
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    private static final String SURFACE_UTILISATEUR = "/quotas/consommation";
    private static final String SURFACE_ADMIN = "/admin/quotas/plans";

    /** Engendree une fois : une paire RSA coute cher, et son contenu importe peu. */
    private static KeyPair paireDeCles;

    private WebTestClient client;

    @BeforeAll
    static void engendrerLesCles() {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();
    }

    /**
     * La vraie configuration, les vrais filtres.
     *
     * <p>Les deux filtres sont importés comme beans et non construits à la
     * main : leurs valeurs {@code @Value} — le secret de la passerelle,
     * l'émetteur et l'audience attendus — ne sont injectées que sur un bean, et
     * l'analyseur JWT est construit par {@code @PostConstruct}. Un filtre
     * construit par {@code new} les aurait laissées nulles, et le test aurait
     * mesuré un refus au lieu d'une validation.
     */
    @EnableWebFlux
    @Import({SecurityConfig.class, GatewayAccessWebFilter.class,
            JwtAuthenticationWebFilter.class})
    @TestConfiguration
    static class ConfigurationDeTest {

        /**
         * Une route attrape-tout qui rend 200.
         *
         * <p>Un {@code RouterFunction} et non un {@code @RestController} : un
         * contrôleur annoté, même imbriqué dans une classe de test, reste
         * visible du balayage de l'application et rendrait la cartographie
         * ambiguë. Ce dépôt s'est déjà fait prendre ainsi.
         *
         * <p>Ce que le test mesure est le <b>verdict de la chaîne</b> : 200
         * signifie « la requête est parvenue au-delà de la sécurité ».
         */
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route(RequestPredicates.all(),
                    requete -> ServerResponse.ok().bodyValue("ok"));
        }

        /**
         * Pose l'adresse du pair TCP, que {@code WebTestClient} ne fournit pas.
         *
         * <p>Sans adresse, le contrôle de réseau du filtre d'accès refuserait
         * tout en 403 <b>avant même</b> de regarder la signature, et tous les
         * tests passeraient pour la mauvaise raison.
         *
         * <p>Déclaré à {@code HIGHEST_PRECEDENCE}, il passe devant le filtre
         * d'accès, ordonné à {@code HIGHEST_PRECEDENCE + 10}.
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
    void setUp() {
        JwksKeyProvider jwks = Mockito.mock(JwksKeyProvider.class);
        when(jwks.getDefaultPublicKey()).thenReturn((RSAPublicKey) paireDeCles.getPublic());
        when(jwks.keyLocator()).thenReturn(entete -> paireDeCles.getPublic());

        var contexte = new AnnotationConfigReactiveWebApplicationContext();
        // Les proprietes sont fournies explicitement : hors d'un demarrage
        // Spring Boot, aucun config-repo n'est lu. Le test connait donc le
        // secret EFFECTIF du filtre, et signe avec celui-la.
        contexte.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "proprietes-de-test", Map.of(
                        "gateway.secret.key", SECRET,
                        "jwt.issuer", EMETTEUR,
                        "jwt.audience", AUDIENCE)));

        // Enregistres comme SINGLETONS, et non exposes par des methodes @Bean.
        //
        // Un objet rendu par une methode @Bean passe par
        // AutowiredAnnotationBeanPostProcessor, qui applique l'injection @Value
        // aux champs de sa classe — et la classe d'un mock Mockito HERITE des
        // champs annotes de la classe simulee. Spring tenterait donc de
        // resoudre les proprietes JWKS sur le mock, et le contexte ne
        // demarrerait pas.
        contexte.getBeanFactory().registerSingleton("hmacSignatureUtil", new HMACSignatureUtil());
        contexte.getBeanFactory().registerSingleton("jwksKeyProvider", jwks);

        contexte.register(ConfigurationDeTest.class);
        contexte.refresh();

        client = WebTestClient.bindToApplicationContext(contexte).build();
    }

    /** Un vrai JWT RS256, tel qu'interface-service en émet. */
    private String jeton(List<String> roles) {
        return Jwts.builder()
                .subject(UTILISATEUR)
                .issuer(EMETTEUR)
                .audience().add(AUDIENCE).and()
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(paireDeCles.getPrivate())
                .compact();
    }

    /**
     * Appelle un endpoint comme la passerelle le ferait.
     *
     * <p>La signature porte sur le chemin <b>reçu</b> par le service, c'est-à-dire
     * après retrait du préfixe public — exactement ce que calcule
     * {@code CheminCible} côté passerelle.
     */
    private WebTestClient.ResponseSpec appel(HttpMethod methode, String chemin,
                                             String jeton, boolean signee) {
        var requete = client.method(methode).uri(chemin);

        if (signee) {
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(horodatage, chemin, methode.name(), idRequete);
            requete = requete
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(SECRET, donnees));
        }
        if (jeton != null) {
            requete = requete.header("Authorization", "Bearer " + jeton);
        }
        return requete.exchange();
    }

    private WebTestClient.ResponseSpec appelSigne(String chemin, String jeton) {
        return appel(HttpMethod.GET, chemin, jeton, true);
    }

    // ==================================================================
    @Nested
    @DisplayName("Les deux surfaces sont ATTEIGNABLES")
    class SurfacesAtteignables {

        @Test
        @DisplayName("ADMIN atteint l'administration ET la surface utilisateur")
        void adminAtteintTout() {
            String jetonAdmin = jeton(List.of("ADMIN"));

            appelSigne(SURFACE_ADMIN, jetonAdmin).expectStatus().isOk();
            appelSigne(SURFACE_UTILISATEUR, jetonAdmin).expectStatus().isOk();
        }

        @Test
        @DisplayName("Un utilisateur ordinaire atteint la surface utilisateur")
        void utilisateurAtteintSaSurface() {
            appelSigne(SURFACE_UTILISATEUR, jeton(List.of("USER"))).expectStatus().isOk();
        }

        @Test
        @DisplayName("Les ecritures passent aussi : POST et DELETE")
        void ecrituresJoignables() {
            String jetonAdmin = jeton(List.of("ADMIN"));

            appel(HttpMethod.POST, SURFACE_ADMIN, jetonAdmin, true).expectStatus().isOk();
            appel(HttpMethod.DELETE, SURFACE_ADMIN, jetonAdmin, true).expectStatus().isOk();
        }

        @Test
        @DisplayName("Le role porte en chaine simple est accepte aussi")
        void roleEnChaineSimple() {
            // Le contrat JWT admet les deux formes — une chaine ou une liste.
            // N'en couvrir qu'une laisserait l'autre se casser en silence.
            String avecChaine = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .audience().add(AUDIENCE).and()
                    .claim("roles", "ADMIN")
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, avecChaine).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Sans la passerelle, rien ne passe")
    class PassageObligatoireParLaPasserelle {

        @ParameterizedTest(name = "GET {0} sans signature")
        @ValueSource(strings = {"/quotas/consommation", "/admin/quotas/plans"})
        @DisplayName("Non signee : refusee, meme avec un jeton ADMIN valide")
        void sansSignature(String chemin) {
            // C'est ce qui fait de la passerelle la seule porte. Un appel direct
            // sur le port du service, meme muni d'un vrai jeton, n'aboutit pas.
            appel(HttpMethod.GET, chemin, jeton(List.of("ADMIN")), false)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Signature forgee : refusee")
        void signatureForgee() {
            client.get().uri(SURFACE_ADMIN)
                    .header("X-Gateway-Timestamp", String.valueOf(System.currentTimeMillis()))
                    .header("X-Gateway-Request-Id", UUID.randomUUID().toString())
                    .header("X-Gateway-Signature", "une-signature-inventee")
                    .header("Authorization", "Bearer " + jeton(List.of("ADMIN")))
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le role et le jeton sont exiges, et verifies")
    class RoleEtJetonExiges {

        @Test
        @DisplayName("Sans jeton : refusee, meme signee par la passerelle")
        void sansJeton() {
            appelSigne(SURFACE_UTILISATEUR, null).expectStatus().isUnauthorized();
        }

        @ParameterizedTest(name = "role {0}")
        @ValueSource(strings = {"USER", "DEVELOPER", "OAUTH2_TEMP"})
        @DisplayName("L'administration refuse un role insuffisant")
        void roleInsuffisantSurAdmin(String role) {
            appelSigne(SURFACE_ADMIN, jeton(List.of(role))).expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Un jeton expire est refuse")
        void jetonExpire() {
            String perime = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .audience().add(AUDIENCE).and()
                    .claim("roles", List.of("ADMIN"))
                    .expiration(new Date(System.currentTimeMillis() - 1_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, perime).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Un jeton emis pour un AUTRE service est refuse")
        void mauvaiseAudience() {
            // Sans ce controle, un jeton obtenu pour media-service ouvrirait les
            // quotas de la plateforme.
            String pourAutrui = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .audience().add("media-service").and()
                    .claim("roles", List.of("ADMIN"))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, pourAutrui).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Un jeton signe par une AUTRE cle est refuse")
        void mauvaiseSignature() {
            KeyPair autre = Jwts.SIG.RS256.keyPair().build();
            String forge = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .audience().add(AUDIENCE).and()
                    .claim("roles", List.of("ADMIN"))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(autre.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, forge).expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les sondes et la documentation")
    class SondesEtDocumentation {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/health", "/actuator/info"})
        @DisplayName("Les sondes passent sans signature ni jeton")
        void sondes(String chemin) {
            // Consul et Prometheus ne portent aucun jeton. Les fermer ferait
            // sortir toutes les instances du pool, et l'orchestrateur les
            // redemarrerait en boucle.
            appel(HttpMethod.GET, chemin, null, false).expectStatus().isOk();
        }

        @Test
        @DisplayName("La documentation exige la SIGNATURE mais pas le jeton")
        void documentation() {
            // L'asymetrie voulue : permise par la couche declarative, exigee par
            // le filtre d'acces. L'attaquer sur le port du service en direct
            // echoue, et c'est ce qui fait de la passerelle la seule porte.
            appel(HttpMethod.GET, "/v3/api-docs", null, true).expectStatus().isOk();
            appel(HttpMethod.GET, "/v3/api-docs", null, false).expectStatus().isForbidden();
        }
    }
}
