package com.abdatytch.media_service.config;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.media_service.security.GatewayAccessWebFilter;
import com.abdatytch.media_service.security.JwtAuthenticationWebFilter;
import com.abdatytch.media_service.security.RoleAuthorizationWebFilter;
import com.abdatytch.media_service.service.TokenBlacklistService;
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
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * La chaîne de sécurité de media-service, exercée pour de VRAI.
 *
 * <h2>Le point aveugle que ce test ferme</h2>
 *
 * Ce module n'avait <b>aucun</b> test de sa chaîne HTTP — trois classes de test
 * en tout, portant sur l'effacement et le chiffrement. Et la chaîne était
 * cassée de <b>deux</b> façons indépendantes, chacune suffisant à rendre le
 * service entièrement injoignable :
 *
 * <ol>
 *   <li>{@code GatewayAccessWebFilter} exigeait l'en-tête {@code X-Secret-Key},
 *       que la passerelle <b>n'émet plus</b> et qu'elle <b>purge</b> des
 *       requêtes entrantes. Refus en 403 au premier filtre, pour toute
 *       requête ;</li>
 *   <li>{@code JwtAuthenticationWebFilter} validait le jeton puis transmettait
 *       <b>sans rien déposer dans le contexte de sécurité</b>. La couche
 *       déclarative exige {@code authenticated()} : ne trouvant aucune
 *       identité, elle refusait.</li>
 * </ol>
 *
 * <p>Les deux défauts ont la même origine : du code correct en apparence, dont
 * personne ne vérifiait qu'il se raccordait au reste. Le second est le plus
 * instructif — il vient d'un durcissement <b>souhaitable</b>, remplaçant
 * {@code anyExchange().permitAll()} par {@code authenticated()}, dont le
 * commentaire d'origine est long et juste, et qui supposait simplement que les
 * filtres maison peuplaient le contexte.
 *
 * <h2>Ce qui est réellement branché</h2>
 *
 * La vraie {@link SecurityConfig} avec ses trois vrais filtres, un vrai
 * {@link HMACSignatureUtil}, et des requêtes signées <b>exactement comme la
 * passerelle les signe</b> — sur {@code horodatage|chemin|méthode|identifiant}.
 * Les jetons sont de vrais JWT RS256, signés par une paire de clés engendrée ici
 * et servie par un {@code JwksKeyProvider} simulé.
 *
 * <p>C'est le test qui répond à « est-ce que les endpoints marchent quand on les
 * appelle comme la passerelle les appellera ».
 */
@DisplayName("media-service - la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET = "secret-de-passerelle-pour-test";
    private static final String EMETTEUR = "interface-service";
    private static final String AUDIENCE = "media-service";
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    /** Engendrée une fois : une paire RSA coûte cher, et son contenu importe peu. */
    private static KeyPair paireDeCles;

    /** Partagés avec la configuration de test, qui les expose en beans. */
    private static JwksKeyProvider jwks;
    private static TokenBlacklistService listeNoire;

    private WebTestClient client;

    @BeforeAll
    static void engendrerLesCles() {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();
    }

    /**
     * La vraie configuration, les vrais filtres.
     *
     * <p>Les trois filtres sont importés comme beans et non construits à la
     * main : leurs valeurs {@code @Value} — le secret de la passerelle,
     * l'émetteur et l'audience attendus — ne sont injectées que sur un bean.
     * Un filtre construit par {@code new} les aurait laissées nulles, et le test
     * aurait mesuré autre chose que la production.
     */
    @EnableWebFlux
    @Import({SecurityConfig.class, GatewayAccessWebFilter.class,
            JwtAuthenticationWebFilter.class, RoleAuthorizationWebFilter.class})
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
         * signifie « la requête est parvenue au-delà de la sécurité », et c'est
         * la seule chose qui nous intéresse ici.
         */
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route(RequestPredicates.all(),
                    requete -> ServerResponse.ok().bodyValue("ok"));
        }

        /**
         * Pose l'adresse du pair TCP, que {@code WebTestClient} ne fournit pas.
         *
         * <h3>Pourquoi un bean, et prioritaire</h3>
         *
         * Sans adresse, {@code GatewayClientIp} se rabat sur le pair TCP, qui
         * est nul : le contrôle de réseau refuserait tout en 403 <b>avant
         * même</b> de regarder la signature, et tous les tests passeraient pour
         * la mauvaise raison.
         *
         * <p>Déclaré en bean à {@code HIGHEST_PRECEDENCE}, il passe devant la
         * chaîne de Spring Security, ordonnée à -100. Posé via
         * {@code MockServerSpec.webFilter(...)}, il s'exécuterait <b>après</b>
         * elle — donc après le filtre qu'il doit renseigner.
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
        jwks = Mockito.mock(JwksKeyProvider.class);
        when(jwks.getDefaultPublicKey()).thenReturn((RSAPublicKey) paireDeCles.getPublic());
        when(jwks.keyLocator()).thenReturn(entete -> paireDeCles.getPublic());

        listeNoire = Mockito.mock(TokenBlacklistService.class);
        when(listeNoire.isBlacklisted(anyString())).thenReturn(Mono.just(false));

        var contexte = new AnnotationConfigReactiveWebApplicationContext();
        // Les proprietes sont fournies explicitement : hors d'un demarrage
        // Spring Boot, aucun config-repo n'est lu. Le test connait donc le
        // secret EFFECTIF du filtre, et signe avec celui-la.
        contexte.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "proprietes-de-test", Map.of(
                        "security.gateway.secret-key", SECRET,
                        "jwt.issuer", EMETTEUR,
                        "jwt.audience", AUDIENCE)));
        // Les collaborateurs sont enregistres comme SINGLETONS, et non exposes
        // par des methodes @Bean.
        //
        // Un objet rendu par une methode @Bean passe par
        // AutowiredAnnotationBeanPostProcessor, qui applique l'injection @Value
        // aux champs de sa classe — et la classe d'un mock Mockito HERITE des
        // champs annotes de la classe simulee. Spring tentait donc de resoudre
        // `jwt.jwks.url`, `jwt.jwks.refresh-interval-seconds` et
        // `jwt.jwks.retry-attempts` sur le mock de JwksKeyProvider, et le
        // contexte ne demarrait pas.
        //
        // Un singleton enregistre a la main ne traverse pas ce
        // post-processeur : le mock reste un mock. Il demeure resolvable par
        // type pour l'injection des filtres, qui est tout ce qu'on lui demande.
        contexte.getBeanFactory().registerSingleton("hmacSignatureUtil", new HMACSignatureUtil());
        contexte.getBeanFactory().registerSingleton("jwksKeyProvider", jwks);
        contexte.getBeanFactory().registerSingleton("tokenBlacklistService", listeNoire);

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
     * après retrait du préfixe public {@code /api/v1} — exactement ce que calcule
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
    @DisplayName("Le defaut corrige : le service est ATTEIGNABLE")
    class ServiceAtteignable {

        /**
         * Le test qui ferme le point aveugle.
         *
         * <p>Avant correction, cet appel recevait 403 : d'abord parce que
         * l'en-tête {@code X-Secret-Key} manquait — la passerelle ne l'émet plus
         * —, et ensuite, cet obstacle levé, parce qu'aucune identité n'était
         * déposée dans le contexte de sécurité.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/media/user/documents", "/media/user/status", "/media/requirements"
        })
        @DisplayName("un utilisateur authentifie atteint ses propres documents")
        void utilisateurAtteintSesDocuments(String chemin) {
            appelSigne(chemin, jeton(List.of("ROLE_USER")))
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un administrateur atteint /admin/documents")
        void administrateurAtteintLesDocuments() {
            appelSigne("/admin/documents/pending", jeton(List.of("ROLE_ADMIN")))
                    .expectStatus().isOk();
        }

        /**
         * Le second contrôleur d'administration, que le filtre de rôle ne
         * couvrait pas.
         *
         * <p>{@code ADMIN_PATHS} ne contenait que {@code /admin/documents}.
         * {@code /admin/document-types} — onze endpoints dont la suppression de
         * types et l'attribution de rôles — ne reposait donc que sur la règle
         * déclarative.
         */
        @Test
        @DisplayName("un administrateur atteint /admin/document-types")
        void administrateurAtteintLesTypes() {
            appelSigne("/admin/document-types", jeton(List.of("ROLE_ADMIN")))
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un SUPER_ADMIN atteint aussi l'administration")
        void superAdminAtteintAdmin() {
            // UserRole.expand() met les deux autorites dans le jeton. N'en
            // garder qu'une — ce que fait un `rolesList.get(0)` — retirerait au
            // SUPER_ADMIN ce que son subordonne obtient.
            appelSigne("/admin/documents/pending",
                    jeton(List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN")))
                    .expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le role est exige, sur les DEUX controleurs d'administration")
    class RoleExige {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/admin/documents/pending", "/admin/documents/all",
                "/admin/document-types", "/admin/document-types/roles/USER"
        })
        @DisplayName("un utilisateur ordinaire est refuse sur tout /admin/**")
        void utilisateurOrdinaireRefuse(String chemin) {
            appelSigne(chemin, jeton(List.of("ROLE_USER")))
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("le reste de l'actuator exige ADMIN")
        void actuatorExigeAdmin() {
            // /actuator/env expose la cartographie de la configuration, et
            // /actuator/loggers est modifiable par POST : passer la securite en
            // DEBUG pour faire journaliser des donnees sensibles, ou en OFF pour
            // aveugler la supervision.
            appelSigne("/actuator/env", jeton(List.of("ROLE_USER")))
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La signature de la passerelle est obligatoire")
    class SignatureObligatoire {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/media/user/documents", "/admin/documents/pending",
                "/admin/document-types"
        })
        @DisplayName("sans signature, l'appel direct est refuse")
        void sansSignature(String chemin) {
            // L'invariant de l'architecture : liste noire, limitation de debit et
            // blocage geographique vivent a la passerelle, et ne valent que si
            // personne ne peut appeler le service en direct.
            appel(HttpMethod.GET, chemin, jeton(List.of("ROLE_ADMIN")), false)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("une signature portant un AUTRE chemin est refusee")
        void signatureSurUnAutreChemin() {
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(horodatage, "/media/user/documents", "GET", idRequete);

            client.get().uri("/admin/documents/pending")
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(SECRET, donnees))
                    .header("Authorization", "Bearer " + jeton(List.of("ROLE_ADMIN")))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("une signature d'un AUTRE secret est refusee")
        void signatureAvecUnAutreSecret() {
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(horodatage, "/media/user/documents", "GET", idRequete);

            client.get().uri("/media/user/documents")
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature("autre-secret", donnees))
                    .header("Authorization", "Bearer " + jeton(List.of("ROLE_USER")))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un horodatage expire est refuse - anti-rejeu")
        void horodatageExpire() {
            String vieux = String.valueOf(System.currentTimeMillis() - 600_000);
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(vieux, "/media/user/documents", "GET", idRequete);

            client.get().uri("/media/user/documents")
                    .header("X-Gateway-Timestamp", vieux)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(SECRET, donnees))
                    .header("Authorization", "Bearer " + jeton(List.of("ROLE_USER")))
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le jeton est exige, et verifie")
    class JetonExige {

        @Test
        @DisplayName("sans jeton, un endpoint protege rend 401 et non 403")
        void sansJeton() {
            // La distinction compte pour le client : 401 veut dire
            // « authentifie-toi », 403 « tu n'as pas le droit ».
            appelSigne("/media/user/documents", null)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton revoque est refuse")
        void jetonRevoque() {
            when(listeNoire.isBlacklisted(anyString())).thenReturn(Mono.just(true));

            appelSigne("/media/user/documents", jeton(List.of("ROLE_USER")))
                    .expectStatus().isUnauthorized();
        }

        /**
         * Le piège du {@code Mono} vide — et sa forme la plus trompeuse.
         *
         * <h3>Ce que j'attendais, et ce qui se produit réellement</h3>
         *
         * J'avais écrit que la requête « pendrait » jusqu'au délai d'expiration
         * du client. <b>Mesuré : elle rend 200 OK avec un corps vide.</b>
         *
         * <p>Quand la liste noire rend un {@code Mono} vide, le
         * {@code flatMap} du filtre ne s'exécute pas et le filtre rend un
         * {@code Mono<Void>} vide. L'échange se termine alors sans que personne
         * n'ait écrit de statut, et le défaut est 200. La requête n'a
         * <b>jamais atteint le gestionnaire</b>, et l'appelant la croit réussie.
         *
         * <p>C'est pire qu'une requête qui pend : un blocage se voit, un faux
         * succès ne se voit pas. Sur {@code POST /media/upload}, ce serait un
         * document non enregistré, rapporté comme enregistré.
         *
         * <p>Deux gardes le ferment, et elles ne disent pas la même chose :
         * {@code TokenBlacklistServiceImpl} détient la <b>politique</b>
         * (inconnu valant révoqué), et le filtre protège son propre <b>flot de
         * contrôle</b> contre une implémentation qui n'honorerait pas le
         * contrat. Ce test exerce la seconde, puisqu'il simule le service.
         */
        @Test
        @DisplayName("une liste noire muette ne rend PAS un faux 200")
        void listeNoireMuette() {
            when(listeNoire.isBlacklisted(anyString())).thenReturn(Mono.empty());

            appelSigne("/media/user/documents", jeton(List.of("ROLE_USER")))
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton signe par une AUTRE cle est refuse")
        void jetonMalSigne() {
            KeyPair autre = Jwts.SIG.RS256.keyPair().build();
            String force = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .audience().add(AUDIENCE).and()
                    .claim("roles", List.of("ROLE_ADMIN"))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(autre.getPrivate())
                    .compact();

            appelSigne("/admin/documents/pending", force)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton d'une AUTRE audience est refuse")
        void audienceEtrangere() {
            String autreAudience = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .audience().add("auth-service").and()
                    .claim("roles", List.of("ROLE_ADMIN"))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne("/admin/documents/pending", autreAudience)
                    .expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemins publics, et celui qui ne doit plus l'etre")
    class CheminsPublics {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/prometheus"})
        @DisplayName("les trois sondes passent sans signature ni jeton")
        void sondesPubliques(String chemin) {
            // Consul interroge /actuator/health toutes les dix secondes et
            // Prometheus racle /actuator/prometheus : ni l'un ni l'autre ne porte
            // de jeton ni de signature.
            appel(HttpMethod.GET, chemin, null, false)
                    .expectStatus().isOk();
        }

        /**
         * La documentation, <b>signée mais sans jeton</b>, n'est plus refusée.
         *
         * <h3>Le défaut que ce test fixe</h3>
         *
         * {@code JwtAuthenticationWebFilter} rendait 401 dès qu'un chemin hors
         * de sa propre liste arrivait sans en-tête {@code Authorization}. La
         * documentation est pourtant {@code permitAll} dans
         * {@code SecurityConfig} — à dessein, un navigateur qui charge Swagger
         * ne porte aucun jeton. Mais ce filtre s'exécute à
         * {@code AUTHENTICATION}, donc <b>avant</b> la décision d'autorisation,
         * et gagnait.
         *
         * <p>Résultat : la documentation agrégée par la passerelle était
         * <b>injoignable</b>, alors que tout le dispositif venait d'être
         * construit pour la rendre lisible par là, et par là seulement. Une
         * permission écrite, présente, jamais atteinte.
         *
         * <h3>Pourquoi l'assertion est « pas 401 » et non « 200 »</h3>
         *
         * springdoc n'est pas monté dans ce contexte de test, qui n'embarque que
         * {@code SecurityConfig} et les trois filtres. Un 404 y est donc le
         * résultat normal, et il prouve ce qui compte : la requête a
         * <b>traversé</b> la chaîne de sécurité. Exiger 200 ferait dépendre ce
         * test de la présence de springdoc, qui n'est pas son sujet.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html",
                "/webjars/swagger-ui/favicon.png"
        })
        @DisplayName("la documentation signee passe la chaine SANS jeton")
        void documentationSigneeSansJeton(String chemin) {
            appel(HttpMethod.GET, chemin, null, true)
                    .expectStatus().value(statut -> org.assertj.core.api.Assertions
                            .assertThat(statut)
                            .withFailMessage("""
                                    %s est refuse en 401 par la chaine de securite.

                                    La documentation est permitAll dans SecurityConfig, mais
                                    un filtre s'executant a AUTHENTICATION la refuse avant
                                    que l'autorisation ne soit evaluee. La documentation
                                    agregee par la passerelle redevient injoignable.
                                    """.formatted(chemin))
                            .isNotEqualTo(401));
        }

        /**
         * Et la documentation <b>non signée</b> reste refusée.
         *
         * <p>C'est l'autre moitié de la règle, celle qui fait de la passerelle
         * la seule porte : exempter la documentation du jeton ne l'exempte pas
         * de la signature. L'attaquer directement sur le port du service
         * échoue.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html"})
        @DisplayName("la documentation NON signee est refusee")
        void documentationNonSigneeRefusee(String chemin) {
            appel(HttpMethod.GET, chemin, null, false)
                    .expectStatus().isForbidden();
        }

        /**
         * {@code /api/v1/internal/**} n'est plus exempté.
         *
         * <p>La règle figurait à <b>trois</b> endroits : dans
         * {@code SecurityConfig} et dans {@code PUBLIC_ENDPOINTS}, lu par les
         * deux filtres. Elle exemptait donc du contrôle de la passerelle
         * <b>et</b> de la validation du jeton.
         *
         * <p>Elle n'ouvrait rien, faute de chemin correspondant — ce module ne
         * sert aucun {@code /internal/**}. Mais un endpoint interne ajouté
         * demain serait né sans aucune protection. Ce test le constate : un tel
         * chemin, signé mais sans jeton, est désormais refusé.
         */
        @Test
        @DisplayName("un chemin /api/v1/internal/** exige un jeton comme les autres")
        void internePlusExempte() {
            appelSigne("/api/v1/internal/documents/x", null)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("et sans signature, il est refuse comme les autres")
        void interneExigeLaSignature() {
            appel(HttpMethod.GET, "/api/v1/internal/documents/x", null, false)
                    .expectStatus().isForbidden();
        }
    }
}
