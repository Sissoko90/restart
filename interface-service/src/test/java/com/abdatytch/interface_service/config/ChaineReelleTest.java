package com.abdatytch.interface_service.config;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.interface_service.filter.AdminSecretFilter;
import com.abdatytch.interface_service.filter.GatewayAccessFilter;
import com.abdatytch.interface_service.filter.IpBlacklistFilter;
import com.abdatytch.interface_service.filter.MaintenanceJwtFilter;
import com.abdatytch.interface_service.filter.RateLimitFilter;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La chaîne de sécurité d'interface-service, exercée pour de VRAI.
 *
 * <h2>Le point aveugle que ce test ferme</h2>
 *
 * Ce module avait <b>neuf</b> filtres, chacun avec son test — et <b>aucun</b>
 * test de la chaîne qu'ils forment ensemble. Or ce qui casse un service n'est
 * presque jamais un filtre fautif : c'est leur composition, l'ordre dans lequel
 * ils s'exécutent, et la couche déclarative qui décide à côté d'eux.
 *
 * <p>C'est la lacune qui a coûté le plus cher à ce dépôt : media-service était
 * <b>entièrement injoignable</b> — deux défauts indépendants, chacun suffisant
 * — et tous ses tests unitaires passaient.
 *
 * <p>Et ce module est celui où cela compte le plus : il est l'<b>autorité
 * JWT</b> de la plateforme, seul détenteur des clés privées. Ses 36 endpoints
 * n'avaient jamais été appelés comme la passerelle les appelle.
 *
 * <h2>Le vrai décodeur, pas un substitut</h2>
 *
 * {@code SecurityConfig} construit un {@code NimbusReactiveJwtDecoder} qui va
 * <b>chercher le JWKS par HTTP</b>. Le remplacer par un décodeur local aurait
 * été plus simple, et aurait cessé de mesurer la chaîne réelle : c'est ce
 * décodeur-là qui décide quelles vérifications s'appliquent à un jeton.
 *
 * <p>Un serveur HTTP local sert donc un JWKS contenant la clé publique
 * d'essai, et {@code jwt.jwks.url} y pointe. Ce qui révèle au passage ce que ce
 * décodeur vérifie réellement — voir {@link RoleEtJetonExiges}.
 *
 * <h2>Ce qui est écarté, et pourquoi</h2>
 *
 * La liste noire d'adresses et le plafond de débit sont de vrais beans, mais
 * désactivés par leur propre propriété ({@code security.ip-blacklist.enabled},
 * {@code rate-limit.enabled}). Ils ont chacun leur test, ils dépendent de
 * Redis, et ce test porte sur l'authentification et l'autorisation. Les laisser
 * actifs ferait échouer des cas pour une raison étrangère à ce qu'ils mesurent.
 */
@DisplayName("interface-service - la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET_PASSERELLE = "secret-de-passerelle-pour-test";
    private static final String SECRET_ADMIN = "secret-admin-pour-test";
    private static final String EMETTEUR = "interface-service";
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";
    private static final String IDENTIFIANT_DE_CLE = "cle-de-test";

    /** Engendree une fois : une paire RSA coute cher, et son contenu importe peu. */
    private static KeyPair paireDeCles;

    /** Sert le JWKS au vrai decodeur Nimbus. */
    private static HttpServer serveurJwks;
    private static String urlJwks;

    private WebTestClient client;

    @BeforeAll
    static void demarrerLeServeurJwks() throws Exception {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();

        RSAKey cle = new RSAKey.Builder((RSAPublicKey) paireDeCles.getPublic())
                .keyID(IDENTIFIANT_DE_CLE)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();
        byte[] corps = new JWKSet(cle).toString(true).getBytes(StandardCharsets.UTF_8);

        // Port 0 : le systeme en choisit un libre. Un port fixe ferait echouer
        // le test des qu'autre chose l'occupe, et en integration continue ce
        // genre de collision est intermittent donc tres couteux a diagnostiquer.
        serveurJwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serveurJwks.createContext("/jwks.json", echange -> {
            echange.getResponseHeaders().add("Content-Type", "application/json");
            echange.sendResponseHeaders(200, corps.length);
            try (OutputStream sortie = echange.getResponseBody()) {
                sortie.write(corps);
            }
        });
        serveurJwks.start();
        urlJwks = "http://127.0.0.1:" + serveurJwks.getAddress().getPort() + "/jwks.json";
    }

    @AfterAll
    static void arreterLeServeurJwks() {
        if (serveurJwks != null) {
            serveurJwks.stop(0);
        }
    }

    /**
     * La vraie configuration, les vrais filtres.
     *
     * <p>Tous sont importés comme beans et non construits à la main : leurs
     * valeurs {@code @Value} — secret de la passerelle, secret d'administration,
     * URL du JWKS — ne sont injectées que sur un bean. Un filtre construit par
     * {@code new} les aurait laissées nulles, et le test aurait mesuré un refus
     * au lieu d'une validation.
     */
    @EnableWebFlux
    @Import({SecurityConfig.class, GatewayAccessFilter.class, AdminSecretFilter.class,
            MaintenanceJwtFilter.class, IpBlacklistFilter.class, RateLimitFilter.class})
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
         * <p>Sans adresse, le contrôle de réseau privé du filtre d'accès
         * refuserait tous les appels internes, et ces cas passeraient pour la
         * mauvaise raison.
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
        var contexte = new AnnotationConfigReactiveWebApplicationContext();
        // Les proprietes sont fournies explicitement : hors d'un demarrage
        // Spring Boot, aucun config-repo n'est lu. Le test connait donc les
        // secrets EFFECTIFS de la chaine, et signe avec ceux-la.
        Map<String, Object> proprietes = new HashMap<>();
        proprietes.put("jwt.jwks.url", urlJwks);
        proprietes.put("gateway.secret.key", SECRET_PASSERELLE);
        proprietes.put("admin.secret.key", SECRET_ADMIN);
        proprietes.put("security.ip-blacklist.enabled", "false");
        proprietes.put("rate-limit.enabled", "false");
        proprietes.put("cors.allowed-origins", "http://localhost:3000");
        contexte.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("proprietes-de-test", proprietes));

        // Enregistres comme SINGLETONS, et non exposes par des methodes @Bean.
        //
        // Un objet rendu par une methode @Bean passe par
        // AutowiredAnnotationBeanPostProcessor, qui applique l'injection @Value
        // aux champs de sa classe — et la classe d'un mock Mockito HERITE des
        // champs annotes de la classe simulee. Spring tenterait donc de
        // resoudre les proprietes de ces classes sur le mock, et le contexte ne
        // demarrerait pas.
        contexte.getBeanFactory().registerSingleton("hmacSignatureUtil", new HMACSignatureUtil());
        contexte.getBeanFactory().registerSingleton("reactiveStringRedisTemplate",
                Mockito.mock(ReactiveStringRedisTemplate.class));
        contexte.getBeanFactory().registerSingleton("devJwtService",
                Mockito.mock(IDevJwtService.class));

        contexte.register(ConfigurationDeTest.class);
        contexte.refresh();

        client = WebTestClient.bindToApplicationContext(contexte).build();
    }

    /** Un vrai JWT RS256, portant le {@code kid} que sert le JWKS local. */
    private String jeton(List<String> roles) {
        return Jwts.builder()
                .header().keyId(IDENTIFIANT_DE_CLE).and()
                .id(UUID.randomUUID().toString())
                .subject(UTILISATEUR)
                .issuer(EMETTEUR)
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(paireDeCles.getPrivate())
                .compact();
    }

    /**
     * Appelle un endpoint comme la passerelle le ferait.
     *
     * <h3>Un quatrième en-tête, propre à ce module</h3>
     *
     * {@code X-Gateway-Source: spring-cloud-gateway} s'ajoute aux trois en-têtes
     * de signature. Ce module est le <b>seul</b> à le vérifier
     * ({@code GatewayAccessFilter.isRequestFromGateway}), et la passerelle est
     * bien le seul à le poser ({@code GatewaySignatureFilter:155}).
     *
     * <p>Son propre commentaire dit qu'il ne prouve rien — l'appelant le
     * fournit, donc il est forgeable, et c'est la signature HMAC qui établit la
     * provenance. Il reste un filtre grossier. Mais il est <b>obligatoire</b>,
     * et l'omettre produit un 403 dont le message parle d'accès direct : sans ce
     * test, l'écart ne se serait vu qu'en appelant le service pour de vrai.
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
                    .header("X-Gateway-Source", "spring-cloud-gateway")
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(SECRET_PASSERELLE, donnees));
        }
        if (jeton != null) {
            requete = requete.header("Authorization", "Bearer " + jeton);
        }
        return requete.exchange();
    }

    private WebTestClient.ResponseSpec appelSigne(String chemin, String jeton) {
        return appel(HttpMethod.GET, chemin, jeton, true);
    }

    /**
     * Appel signé portant en plus le secret d'administration.
     *
     * <p>{@code AdminSecretFilter} exige {@code X-Admin-Secret} sur tout
     * {@code /admin/**}, y compris sur les chemins que {@code SecurityConfig}
     * déclare {@code permitAll} : c'est un {@code WebFilter} global, donc en
     * amont de Spring Security.
     */
    private WebTestClient.ResponseSpec appelAdmin(HttpMethod methode, String chemin) {
        String horodatage = String.valueOf(System.currentTimeMillis());
        String idRequete = UUID.randomUUID().toString();
        String donnees = new HMACSignatureUtil()
                .buildSignatureData(horodatage, chemin, methode.name(), idRequete);

        return client.method(methode).uri(chemin)
                .header("X-Gateway-Timestamp", horodatage)
                .header("X-Gateway-Request-Id", idRequete)
                .header("X-Gateway-Source", "spring-cloud-gateway")
                .header("X-Gateway-Signature",
                        new HMACSignatureUtil().generateSignature(SECRET_PASSERELLE, donnees))
                .header("X-Admin-Secret", SECRET_ADMIN)
                .exchange();
    }

    // ==================================================================
    @Nested
    @DisplayName("Les surfaces sont ATTEIGNABLES")
    class SurfacesAtteignables {

        @Test
        @DisplayName("Un porteur de jeton atteint la surface des projets")
        void surfaceAuthentifiee() {
            appelSigne("/projects", jeton(List.of("DEVELOPER"))).expectStatus().isOk();
        }

        /**
         * Les six endpoints d'authentification sont joignables <b>sans jeton</b>.
         *
         * <p>Évident, et c'est pour cela qu'il faut le vérifier : si l'un d'eux
         * se refermait, personne ne pourrait plus ouvrir de session, et aucun
         * test unitaire de contrôleur ne le dirait.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/auth/register", "/auth/login", "/auth/refresh",
                "/auth/verify-email", "/auth/forgot-password", "/auth/reset-password"})
        @DisplayName("Les endpoints d'authentification passent sans jeton")
        void authentificationSansJeton(String chemin) {
            appel(HttpMethod.POST, chemin, null, true).expectStatus().isOk();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/dev/auth/register", "/dev/auth/login", "/dev/auth/refresh",
                "/dev/auth/verify-email", "/dev/auth/forgot-password", "/dev/auth/reset-password"})
        @DisplayName("La surface developpeur aussi")
        void authentificationDeveloppeurSansJeton(String chemin) {
            appel(HttpMethod.POST, chemin, null, true).expectStatus().isOk();
        }

        /**
         * Les entrées d'administration : sans jeton, mais avec le secret
         * d'administration.
         *
         * <p>Trois endpoints sont {@code permitAll} dans {@code SecurityConfig}
         * — {@code /admin/login} (mot de passe et code TOTP),
         * {@code /admin/totp/enroll} (refuse si le second facteur est déjà
         * actif) et {@code /admin/confirm} (jeton à usage unique reçu par
         * courriel). Mais {@code AdminSecretFilter} est un {@code WebFilter}
         * global, donc <b>en amont</b> de Spring Security : il exige
         * {@code X-Admin-Secret} sur tout {@code /admin/**}.
         *
         * <p>Mesuré, pas supposé : j'attendais 200 sans ce secret, la chaîne
         * rend 401. Les deux couches ne disent donc pas la même chose, et c'est
         * la plus basse qui tranche.
         *
         * <p>Une seule exception, et elle est voulue : {@code /admin/confirm}.
         * C'est l'endpoint « C'est moi / Ce n'est pas moi », atteint par un clic
         * dans un courriel — un navigateur suivant un lien ne peut pas ajouter
         * d'en-tête. L'exemption avait d'abord été posée dans
         * {@code SecurityConfig} seul, une couche trop bas : le lien de
         * confirmation répondait 401 et le compte administrateur restait bloqué
         * sans recours.
         */
        @Test
        @DisplayName("Les entrees d'administration exigent le secret d'administration")
        void entreesAdministrationAvecSecret() {
            appelAdmin(HttpMethod.POST, "/admin/login").expectStatus().isOk();
            appelAdmin(HttpMethod.POST, "/admin/totp/enroll").expectStatus().isOk();

            appel(HttpMethod.POST, "/admin/login", null, true).expectStatus().isUnauthorized();
            appel(HttpMethod.POST, "/admin/totp/enroll", null, true)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("La confirmation par courriel passe SANS le secret : elle le doit")
        void confirmationDepuisUnCourriel() {
            // Un navigateur qui suit un lien ne peut poser aucun en-tete. Exiger
            // le secret ici fermait le seul recours d'un compte bloque.
            appel(HttpMethod.POST, "/admin/confirm", null, true).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le JWKS est public, et il doit l'etre")
    class JwksPublic {

        /**
         * Le JWKS est lisible <b>sans signature ni jeton</b>.
         *
         * <p>Ce n'est pas une tolérance, c'est une nécessité : huit services
         * valident les jetons en lisant ces clés publiques, et aucun ne passe
         * par la passerelle pour le faire. Le fermer arrêterait toute
         * authentification sur la plateforme entière.
         *
         * <p>Une clé publique n'est pas un secret — c'est précisément ce que son
         * nom dit.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/dev/.well-known/jwks.json", "/.well-known/jwks.json"})
        @DisplayName("Lisible sans signature ni jeton")
        void jwksLisible(String chemin) {
            appel(HttpMethod.GET, chemin, null, false).expectStatus().isOk();
        }

        @Test
        @DisplayName("La sonde de sante aussi, et elle seule dans actuator")
        void sondeDeSante() {
            // Consul interroge celle-ci sans jeton. La fermer ferait sortir
            // toutes les instances du pool, et l'orchestrateur les redemarrerait
            // en boucle.
            appel(HttpMethod.GET, "/actuator/health", null, false).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le materiel cryptographique est reserve au SUPER_ADMIN")
    class MaterielCryptographique {

        /**
         * {@code /dev/admin/keys/**} doit être <b>atteignable</b> par un
         * SUPER_ADMIN.
         *
         * <p>Ces trois endpoints portaient le commentaire « Réservé aux
         * administrateurs » sans qu'aucune règle ne l'applique : ils tombaient
         * sous {@code anyExchange().authenticated()}, et n'importe quel porteur
         * de jeton pouvait faire tourner les clés de signature de la plateforme
         * ou supprimer une clé encore en période de grâce — ce qui invalide tous
         * les jetons en circulation.
         *
         * <p>La règle {@code hasRole("SUPER_ADMIN")} a été ajoutée. Ce test
         * vérifie l'autre moitié, celle qu'on oublie : que la règle laisse
         * encore passer <b>celui qui y a droit</b>. Une protection qui refuse
         * tout le monde n'est pas une protection, c'est une panne.
         */
        @Test
        @DisplayName("Un SUPER_ADMIN atteint la gestion des cles")
        void superAdminAtteintLesCles() {
            appel(HttpMethod.POST, "/dev/admin/keys/rotate",
                    jeton(List.of("SUPER_ADMIN")), true).expectStatus().isOk();
        }

        @ParameterizedTest(name = "role {0}")
        @ValueSource(strings = {"DEVELOPER", "USER", "ADMIN"})
        @DisplayName("Tout autre role est refuse, ADMIN compris")
        void autresRolesRefuses(String role) {
            // ADMIN y figure volontairement : la rotation des cles de signature
            // n'est pas un geste d'administration courante. Un ADMIN qui
            // supprime une cle en periode de grace invalide tous les jetons en
            // circulation.
            appel(HttpMethod.POST, "/dev/admin/keys/rotate", jeton(List.of(role)), true)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Un ADMIN atteint le reste d'actuator, et un USER non")
        void actuatorReserveALAdmin() {
            appelSigne("/actuator/env", jeton(List.of("ADMIN"))).expectStatus().isOk();
            appelSigne("/actuator/env", jeton(List.of("USER"))).expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Sans la passerelle, rien ne passe")
    class PassageObligatoireParLaPasserelle {

        @Test
        @DisplayName("Non signee avec un vrai jeton : refusee")
        void sansSignature() {
            appel(HttpMethod.GET, "/projects", jeton(List.of("DEVELOPER")), false)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Signature forgee : refusee")
        void signatureForgee() {
            // X-Gateway-Source est pose : sans lui le refus viendrait du
            // controle grossier de provenance, et ce test passerait pour la
            // mauvaise raison. Ici, seule la signature est en cause.
            client.get().uri("/projects")
                    .header("X-Gateway-Timestamp", String.valueOf(System.currentTimeMillis()))
                    .header("X-Gateway-Request-Id", UUID.randomUUID().toString())
                    .header("X-Gateway-Source", "spring-cloud-gateway")
                    .header("X-Gateway-Signature", "une-signature-inventee")
                    .header("Authorization", "Bearer " + jeton(List.of("DEVELOPER")))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Horodatage perime : refusee, meme signee correctement")
        void horodatagePerime() {
            // La fenetre anti-rejeu est de 5 secondes. Sans elle, une requete
            // interceptee resterait rejouable indefiniment.
            String vieux = String.valueOf(System.currentTimeMillis() - 60_000);
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(vieux, "/projects", "GET", idRequete);

            client.get().uri("/projects")
                    .header("X-Gateway-Timestamp", vieux)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Source", "spring-cloud-gateway")
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(SECRET_PASSERELLE, donnees))
                    .header("Authorization", "Bearer " + jeton(List.of("DEVELOPER")))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        /**
         * La surface inter-services est exemptée de signature, et doit l'être.
         *
         * <p>interface-service est l'autorité JWT : tout jeton de la plateforme
         * est signé ici. Les autres services l'appellent directement, sans
         * passer par la passerelle — exiger une signature de passerelle fermerait
         * cette voie. Le contrôle qui porte est double : pair TCP sur un réseau
         * privé ici, et {@code X-Internal-Secret} vérifié à temps constant dans
         * {@code InternalJwtController}.
         */
        @Test
        @DisplayName("Les appels internes passent sans signature, depuis un reseau prive")
        void appelInterneSansSignature() {
            appel(HttpMethod.POST, "/internal/jwt/validate", null, false)
                    .expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le jeton est verifie, et on nomme par QUOI")
    class RoleEtJetonExiges {

        @Test
        @DisplayName("Sans jeton : refusee, meme signee par la passerelle")
        void sansJeton() {
            appelSigne("/projects", null).expectStatus().isUnauthorized();
        }

        /**
         * Un jeton expiré est refusé — <b>passé la tolérance d'horloge</b>.
         *
         * <p>Mesuré : un jeton périmé d'une seconde est <b>accepté</b>.
         * {@code JwtTimestampValidator}, le validateur que Nimbus installe par
         * défaut, tolère <b>60 secondes</b> de décalage d'horloge. Ce n'est pas
         * un défaut — deux machines ne sont jamais parfaitement synchrones, et
         * sans cette marge un léger écart NTP refuserait des jetons valides.
         *
         * <p>Mais la marge est réelle et il faut la connaître : la durée de vie
         * effective d'un jeton est sa date d'expiration <b>plus une minute</b>.
         * Une révocation qui compterait sur l'expiration seule laisse donc une
         * fenêtre d'une minute. C'est aussi pourquoi la liste de révocation de
         * user-service existe.
         *
         * <p>Dix minutes ici, pour mesurer le refus et non la tolérance.
         */
        @Test
        @DisplayName("Un jeton expire depuis longtemps est refuse")
        void jetonExpire() {
            String perime = Jwts.builder()
                    .header().keyId(IDENTIFIANT_DE_CLE).and()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", List.of("DEVELOPER"))
                    .issuedAt(new Date(System.currentTimeMillis() - 1_200_000))
                    .expiration(new Date(System.currentTimeMillis() - 600_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne("/projects", perime).expectStatus().isUnauthorized();
        }

        /**
         * La tolérance de 60 secondes, nommée explicitement.
         *
         * <p>Un invariant qu'on a découvert en le heurtant mérite son propre
         * cas : le jour où quelqu'un resserrera cette marge, ce test dira
         * laquelle il change et de combien.
         */
        @Test
        @DisplayName("La tolerance d'horloge est de 60 s : un jeton perime d'une seconde passe")
        void toleranceDHorloge() {
            String aPeinePerime = Jwts.builder()
                    .header().keyId(IDENTIFIANT_DE_CLE).and()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", List.of("DEVELOPER"))
                    .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                    .expiration(new Date(System.currentTimeMillis() - 1_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne("/projects", aPeinePerime).expectStatus().isOk();
        }

        @Test
        @DisplayName("Un jeton signe par une AUTRE cle est refuse")
        void mauvaiseSignature() {
            KeyPair autre = Jwts.SIG.RS256.keyPair().build();
            String forge = Jwts.builder()
                    .header().keyId(IDENTIFIANT_DE_CLE).and()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", List.of("SUPER_ADMIN"))
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(autre.getPrivate())
                    .compact();

            appelSigne("/projects", forge).expectStatus().isUnauthorized();
        }

        /**
         * Un jeton d'un <b>autre émetteur</b> est accepté — et c'est un constat,
         * pas un souhait.
         *
         * <p>{@code NimbusReactiveJwtDecoder.withJwkSetUri(...).build()} n'ajoute
         * que les validateurs par défaut : {@code exp} et {@code nbf}. Ni
         * l'émetteur ni l'audience ne sont vérifiés, alors que les filtres des
         * sept autres modules exigent les deux.
         *
         * <p>La portée réelle est limitée : la clé de signature est détenue par
         * ce service seul, donc un jeton acceptable ici a de toute façon été
         * émis ici. L'écart compte le jour où une seconde autorité signerait
         * avec une clé publiée dans le même JWKS. Le nommer dans un test vaut
         * mieux que de le découvrir ce jour-là.
         */
        @Test
        @DisplayName("L'emetteur n'est PAS verifie par ce decodeur : constate")
        void emetteurNonVerifie() {
            String autreEmetteur = Jwts.builder()
                    .header().keyId(IDENTIFIANT_DE_CLE).and()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer("un-emetteur-inconnu")
                    .claim("roles", List.of("DEVELOPER"))
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne("/projects", autreEmetteur).expectStatus().isOk();
        }
    }
}
