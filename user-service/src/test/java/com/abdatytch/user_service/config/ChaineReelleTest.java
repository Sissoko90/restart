package com.abdatytch.user_service.config;

import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.JwksKeyProvider;
import com.abdatytch.user_service.filter.GatewayAccessFilter;
import com.abdatytch.user_service.security.JwtTokenValidator;
import com.abdatytch.user_service.service.TokenBlacklistService;
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
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
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
 * La chaîne de sécurité de user-service, exercée pour de VRAI.
 *
 * <h2>Le point aveugle que ce test ferme</h2>
 *
 * Ce module n'avait <b>aucun</b> test de sa chaîne HTTP, malgré vingt endpoints
 * et une histoire chargée : un IDOR sur {@code GET /users/{id}}, des fichiers
 * déclarés {@code permitAll} dont le chemin portait l'identifiant du
 * propriétaire, et un {@code ServiceTokenFilter} qui gardait un préfixe que ce
 * service ne sert pas — donc une surface inter-services entièrement refusée.
 *
 * <p>C'est la lacune qui a coûté le plus cher à ce dépôt : media-service était
 * <b>entièrement injoignable</b>, et aucun test ne le disait, parce qu'aucun
 * test n'appelait un endpoint comme la passerelle l'appelle.
 *
 * <h2>Trois différences réelles avec setting et quotas</h2>
 *
 * Ce module ne se contente pas d'être un de plus. Sa chaîne diffère, et ce test
 * mesure ces différences au lieu de les supposer :
 *
 * <ol>
 *   <li><b>OAuth2 resource server</b> et non un {@code WebFilter} maison : le
 *       jeton est décodé par un {@code ReactiveJwtDecoder} qui délègue à
 *       {@link JwtTokenValidator}, lequel consulte en plus la liste de
 *       révocation. Un jeton révoqué est donc refusé <b>bien que sa signature
 *       soit valide</b>, ce qu'aucun autre module ne fait ;</li>
 *   <li>{@link JwtTokenValidator} <b>n'exige pas d'audience</b>, contrairement
 *       aux filtres des autres services. C'est le contrat existant, et ce test
 *       le constate — il ne le corrige pas ;</li>
 *   <li>le filtre d'accès porte {@code @Order(1)}, alors que la chaîne Spring
 *       Security est ordonnée à <b>-100</b>. L'autorisation est donc décidée
 *       <b>avant</b> la vérification de la signature, à l'inverse de setting et
 *       quotas où le filtre d'accès passe en premier.</li>
 * </ol>
 *
 * <p>La troisième n'ouvre rien — une requête non signée munie d'un vrai jeton
 * est bien refusée, par le filtre d'accès, après coup. Mais elle change le
 * <b>code</b> rendu, et surtout elle fait décoder le jeton et interroger la
 * liste de révocation sur une connexion directe, hors passerelle. Les tests
 * ci-dessous nomment précisément ce que chaque couche rend, pour qu'un
 * alignement ultérieur de cet ordre soit une décision visible et non une
 * surprise.
 *
 * <h2>Ce que ce test ne fait pas</h2>
 *
 * Il ne vérifie pas la logique des endpoints — leurs services ont leurs propres
 * suites, et l'absence d'accès par identifiant sur la surface cliente est gardée
 * structurellement par {@code AccessControlIdorTest}. Il répond à une seule
 * question : <b>est-ce que ces endpoints répondent quand on les appelle comme la
 * passerelle les appellera</b>.
 */
@DisplayName("user-service - la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET_PASSERELLE = "secret-de-passerelle-pour-test";
    private static final String SECRET_INTERNE = "secret-inter-services-pour-test";
    private static final String EMETTEUR = "interface-service";
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    private static final String SURFACE_UTILISATEUR = "/users/me";
    private static final String SURFACE_ADMIN = "/admin/users";
    private static final String SURFACE_FICHIERS = "/files/" + UTILISATEUR + "/avatar.png";
    private static final String SURFACE_INTERNE = "/internal/users/" + UTILISATEUR;

    /** Engendree une fois : une paire RSA coute cher, et son contenu importe peu. */
    private static KeyPair paireDeCles;

    private WebTestClient client;
    private TokenBlacklistService revocation;

    @BeforeAll
    static void engendrerLesCles() {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();
    }

    /**
     * La vraie configuration, les vrais filtres, le vrai gestionnaire interne.
     *
     * <p>Tous sont importés comme beans et non construits à la main : leurs
     * valeurs {@code @Value} — secret de la passerelle, secret inter-services,
     * émetteur attendu — ne sont injectées que sur un bean, et l'analyseur JWT
     * de {@link JwtTokenValidator} est construit par {@code @PostConstruct}. Un
     * objet construit par {@code new} les aurait laissées nulles, et le test
     * aurait mesuré un refus au lieu d'une validation.
     */
    // AutorisationInterne n'est plus listee ici : elle est passee dans
    // platform-commons, ou elle n'est pas un @Component, et c'est desormais
    // SecurityConfig qui la declare en @Bean. L'importer en plus creerait un
    // second exemplaire, celui-la sans secret injecte.
    @EnableWebFlux
    @Import({SecurityConfig.class, GatewayAccessFilter.class, JwtTokenValidator.class})
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
         * CORS neutre.
         *
         * <p>{@code SecurityConfig} exige ce bean. La vraie configuration lit
         * des origines autorisées depuis l'environnement, et les faire entrer
         * ici ne mesurerait que la configuration de développement : le sujet du
         * test est l'authentification, pas la politique de navigateur.
         */
        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new UrlBasedCorsConfigurationSource();
        }

        /**
         * Pose l'adresse du pair TCP, que {@code WebTestClient} ne fournit pas.
         *
         * <p>Sans adresse, le contrôle de réseau du filtre d'accès refuserait
         * tout <b>avant même</b> de regarder la signature, et tous les tests
         * passeraient pour la mauvaise raison.
         *
         * <p>Déclaré à {@code HIGHEST_PRECEDENCE}, il passe devant la chaîne de
         * Spring Security (-100) comme devant le filtre d'accès ({@code @Order(1)}).
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

        // Par defaut, rien n'est revoque : chaque test qui veut le contraire le
        // dit lui-meme. Un defaut « tout revoque » ferait echouer les cas de
        // succes pour une raison etrangere a ce qu'ils mesurent.
        revocation = Mockito.mock(TokenBlacklistService.class);
        when(revocation.isTokenRevoked(anyString())).thenReturn(Mono.just(false));
        when(revocation.hasRevokedTokens(anyString())).thenReturn(Mono.just(false));

        var contexte = new AnnotationConfigReactiveWebApplicationContext();
        // Les proprietes sont fournies explicitement : hors d'un demarrage
        // Spring Boot, aucun config-repo n'est lu. Le test connait donc les
        // secrets EFFECTIFS de la chaine, et signe avec ceux-la.
        contexte.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "proprietes-de-test", Map.of(
                        "gateway.secret.key", SECRET_PASSERELLE,
                        "security.internal-secret", SECRET_INTERNE,
                        "jwt.issuer", EMETTEUR)));

        // Enregistres comme SINGLETONS, et non exposes par des methodes @Bean.
        //
        // Un objet rendu par une methode @Bean passe par
        // AutowiredAnnotationBeanPostProcessor, qui applique l'injection @Value
        // aux champs de sa classe — et la classe d'un mock Mockito HERITE des
        // champs annotes de la classe simulee. Spring tenterait donc de
        // resoudre les proprietes de ces classes sur le mock, et le contexte ne
        // demarrerait pas.
        contexte.getBeanFactory().registerSingleton("hmacSignatureUtil", new HMACSignatureUtil());
        contexte.getBeanFactory().registerSingleton("jwksKeyProvider", jwks);
        contexte.getBeanFactory().registerSingleton("tokenBlacklistService", revocation);

        contexte.register(ConfigurationDeTest.class);
        contexte.refresh();

        client = WebTestClient.bindToApplicationContext(contexte).build();
    }

    /**
     * Un vrai JWT RS256, tel qu'interface-service en émet.
     *
     * <p>Un identifiant {@code jti} est posé : c'est lui que la liste de
     * révocation consulte, et sans lui cette branche ne serait jamais exercée.
     */
    private String jeton(List<String> roles) {
        return Jwts.builder()
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
     * <p>La signature porte sur le chemin <b>reçu</b> par le service, c'est-à-dire
     * après retrait du préfixe public — exactement ce que calcule
     * {@code CheminCible} côté passerelle.
     */
    private WebTestClient.ResponseSpec appel(HttpMethod methode, String chemin,
                                             String jeton, boolean signee, String secretInterne) {
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
                            new HMACSignatureUtil().generateSignature(SECRET_PASSERELLE, donnees));
        }
        if (jeton != null) {
            requete = requete.header("Authorization", "Bearer " + jeton);
        }
        if (secretInterne != null) {
            requete = requete.header("X-Internal-Secret", secretInterne);
        }
        return requete.exchange();
    }

    private WebTestClient.ResponseSpec appelSigne(String chemin, String jeton) {
        return appel(HttpMethod.GET, chemin, jeton, true, null);
    }

    // ==================================================================
    @Nested
    @DisplayName("Les surfaces sont ATTEIGNABLES")
    class SurfacesAtteignables {

        @Test
        @DisplayName("ADMIN atteint l'administration ET la surface utilisateur")
        void adminAtteintTout() {
            String jetonAdmin = jeton(List.of("ADMIN"));

            appelSigne(SURFACE_ADMIN, jetonAdmin).expectStatus().isOk();
            appelSigne(SURFACE_UTILISATEUR, jetonAdmin).expectStatus().isOk();
        }

        @Test
        @DisplayName("Un utilisateur ordinaire atteint sa surface")
        void utilisateurAtteintSaSurface() {
            appelSigne(SURFACE_UTILISATEUR, jeton(List.of("USER"))).expectStatus().isOk();
        }

        /**
         * Les fichiers exigent une identité, et c'est le correctif qui compte.
         *
         * <p>{@code GET /files/**} était {@code permitAll}. Le chemin porte
         * l'identifiant du propriétaire, et il vient du client : y substituer
         * celui d'un autre suffisait à lire ses fichiers, <b>sans aucune
         * authentification</b>.
         */
        @Test
        @DisplayName("Les fichiers sont joignables avec un jeton, refuses sans")
        void fichiers() {
            appelSigne(SURFACE_FICHIERS, jeton(List.of("USER"))).expectStatus().isOk();
            appelSigne(SURFACE_FICHIERS, null).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Les ecritures passent aussi : PUT et DELETE")
        void ecrituresJoignables() {
            String jetonAdmin = jeton(List.of("ADMIN"));

            appel(HttpMethod.PUT, SURFACE_ADMIN, jetonAdmin, true, null).expectStatus().isOk();
            appel(HttpMethod.DELETE, SURFACE_ADMIN, jetonAdmin, true, null).expectStatus().isOk();
        }

        @Test
        @DisplayName("Le role porte en chaine simple est accepte aussi")
        void roleEnChaineSimple() {
            // Le convertisseur de SecurityConfig admet les deux formes — une
            // chaine ou une liste. N'en couvrir qu'une laisserait l'autre se
            // casser en silence.
            String avecChaine = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", "ADMIN")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, avecChaine).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La surface inter-services : le secret, et rien d'autre")
    class SurfaceInterne {

        /**
         * {@code /internal/**} exige le secret, et <b>plus</b> la signature.
         *
         * <h3>Le jour annonce est arrive</h3>
         *
         * La version precedente de cette javadoc disait ceci, mot pour mot :
         *
         * <blockquote>
         * La double exigence n'est pas voulue : un appel de service a service ne
         * passe pas par la passerelle et n'a donc aucune signature a presenter.
         * (...) Ce test fixe l'etat CONSTATE plutot qu'un etat souhaite. Aucun
         * appelant n'emprunte aujourd'hui cette surface (...) Mais le jour ou un
         * service l'appellera, c'est ici que le refus se verra.
         * </blockquote>
         *
         * <p>Ce jour est arrive : notification-service doit lire les coordonnees
         * d'une personne par {@code GET /internal/users/auth/{sub}} pour savoir
         * ou la joindre. Le refus s'est bien vu ici, exactement comme annonce.
         *
         * <p>L'exemption est donc posee dans le filtre d'acces, comme
         * interface-service le faisait deja — et la comparaison du prefixe vit
         * desormais dans {@code CheminInterne} de platform-commons, parce que
         * quatre filtres l'ecrivaient ou l'oubliaient chacun a sa facon.
         *
         * <h3>Ce qui protege ce chemin</h3>
         *
         * Deux controles, et ils restent tous les deux :
         *
         * <ol>
         *   <li>le <b>pair TCP</b> doit etre une adresse privee — verifie AVANT
         *       l'exemption dans le filtre ;</li>
         *   <li>{@code X-Internal-Secret}, compare a temps constant par
         *       {@code AutorisationInterne} dans la chaine de securite. Les
         *       tests qui suivent le verifient.</li>
         * </ol>
         */
        @Test
        @DisplayName("Avec le secret ET la signature : joignable")
        void secretEtSignature() {
            appel(HttpMethod.GET, SURFACE_INTERNE, null, true, SECRET_INTERNE)
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("Avec le secret et SANS signature : joignable, car c'est un appel interne")
        void secretSansSignature() {
            appel(HttpMethod.GET, SURFACE_INTERNE, null, false, SECRET_INTERNE)
                    .expectStatus().isOk();
        }

        /**
         * Un secret erroné est refusé en <b>401</b>, et non 403.
         *
         * <p>Mesuré, pas supposé — j'attendais 403 et la chaîne rend 401. La
         * raison tient à Spring Security : un refus sans aucune identité passe
         * par le point d'entrée d'authentification, qui rend 401 ; un refus
         * d'une requête <b>authentifiée</b> rend 403. L'appel inter-services ne
         * porte pas de jeton, donc aucune identité.
         *
         * <p>La distinction se voit en comparant avec
         * {@link #jetonAdminNeSuffitPas()}, où le même refus rend bien 403
         * parce qu'un jeton était présent. Aucun des deux ne divulgue quoi que
         * ce soit : les deux refusent.
         */
        @ParameterizedTest(name = "secret = {0}")
        @ValueSource(strings = {"", "mauvais-secret"})
        @DisplayName("Un secret absent ou errone est refuse, meme signee")
        void mauvaisSecret(String secret) {
            appel(HttpMethod.GET, SURFACE_INTERNE, null, true, secret)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Un jeton d'utilisateur ADMIN n'ouvre PAS la surface interne")
        void jetonAdminNeSuffitPas() {
            // Le secret est le seul laissez-passer. Accepter un jeton ADMIN
            // ferait de la surface inter-services une surface d'administration
            // de plus, atteignable depuis l'extérieur par la passerelle.
            appel(HttpMethod.GET, SURFACE_INTERNE, jeton(List.of("ADMIN")), true, null)
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Sans la passerelle, rien ne passe")
    class PassageObligatoireParLaPasserelle {

        /**
         * Non signée mais munie d'un vrai jeton ADMIN : refusée.
         *
         * <p>C'est le cas qui prouve que le filtre d'accès porte, malgré son
         * {@code @Order(1)} : la chaîne Security accorde l'accès, puis le filtre
         * refuse faute de signature. Le refus est un <b>403</b>, et c'est le
         * filtre d'accès qui le rend.
         */
        @ParameterizedTest(name = "GET {0} sans signature")
        @ValueSource(strings = {"/users/me", "/admin/users"})
        @DisplayName("Non signee avec jeton ADMIN : 403 du filtre d'acces")
        void sansSignatureAvecJeton(String chemin) {
            appel(HttpMethod.GET, chemin, jeton(List.of("ADMIN")), false, null)
                    .expectStatus().isForbidden();
        }

        /**
         * Non signée et sans jeton : <b>401</b>, pas 403.
         *
         * <p>La différence avec setting et quotas est ici, et elle est réelle :
         * leur filtre d'accès précède la chaîne Security et rend 403 ; celui-ci
         * la suit, donc Security répond d'abord et rend 401. Rien n'est ouvert,
         * mais le code diffère — et le jeton est décodé, donc la liste de
         * révocation interrogée, sur une connexion directe hors passerelle.
         */
        @Test
        @DisplayName("Non signee et sans jeton : 401, car Security repond avant")
        void sansSignatureSansJeton() {
            appel(HttpMethod.GET, SURFACE_UTILISATEUR, null, false, null)
                    .expectStatus().isUnauthorized();
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
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", List.of("ADMIN"))
                    .issuedAt(new Date(System.currentTimeMillis() - 60_000))
                    .expiration(new Date(System.currentTimeMillis() - 1_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, perime).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Un jeton emis par un AUTRE emetteur est refuse")
        void mauvaisEmetteur() {
            // C'est le controle que ce validateur porte reellement. Il n'exige
            // PAS d'audience, contrairement aux filtres des autres modules : un
            // jeton emis pour media-service passerait ici. Constate, non
            // corrige — l'aligner est une decision a prendre, pas un detail de
            // test.
            String autreEmetteur = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer("un-emetteur-inconnu")
                    .claim("roles", List.of("ADMIN"))
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, autreEmetteur).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Un jeton signe par une AUTRE cle est refuse")
        void mauvaiseSignature() {
            KeyPair autre = Jwts.SIG.RS256.keyPair().build();
            String forge = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", List.of("ADMIN"))
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(autre.getPrivate())
                    .compact();

            appelSigne(SURFACE_ADMIN, forge).expectStatus().isUnauthorized();
        }

        /**
         * Un jeton <b>révoqué</b> est refusé, bien que sa signature soit valide.
         *
         * <p>C'est ce que ce module fait de plus que les autres : la déconnexion
         * ne se contente pas d'oublier le jeton côté client, elle le rend
         * inutilisable. Sans ce contrôle, un jeton volé resterait valable
         * jusqu'à son expiration, et « se déconnecter » serait un geste sans
         * effet.
         */
        @Test
        @DisplayName("Un jeton revoque est refuse malgre une signature valide")
        void jetonRevoque() {
            when(revocation.isTokenRevoked(anyString())).thenReturn(Mono.just(true));

            appelSigne(SURFACE_ADMIN, jeton(List.of("ADMIN"))).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Tous les jetons d'un utilisateur revoques : refuse")
        void tousLesJetonsRevoques() {
            when(revocation.hasRevokedTokens(anyString())).thenReturn(Mono.just(true));

            appelSigne(SURFACE_ADMIN, jeton(List.of("ADMIN"))).expectStatus().isUnauthorized();
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
            appel(HttpMethod.GET, chemin, null, false, null).expectStatus().isOk();
        }

        /**
         * {@code /actuator/env} n'est <b>pas</b> une sonde.
         *
         * <p>La règle était {@code contains("/actuator/")}, dans le filtre comme
         * dans la chaîne : env, loggers et httptrace passaient sans jeton ni
         * signature. httptrace livre les en-têtes des derniers échanges —
         * jetons compris — et loggers est modifiable par POST.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/env", "/actuator/loggers", "/actuator/httptrace"})
        @DisplayName("Le reste d'actuator exige la signature")
        void actuatorNestPasOuvert(String chemin) {
            appel(HttpMethod.GET, chemin, null, false, null).expectStatus().isUnauthorized();
            appel(HttpMethod.GET, chemin, jeton(List.of("USER")), true, null)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("La documentation exige la SIGNATURE mais pas le jeton")
        void documentation() {
            // L'asymetrie voulue : permise par la couche declarative, exigee par
            // le filtre d'acces. L'attaquer sur le port du service en direct
            // echoue, et c'est ce qui fait de la passerelle la seule porte.
            appel(HttpMethod.GET, "/v3/api-docs", null, true, null).expectStatus().isOk();
            appel(HttpMethod.GET, "/v3/api-docs", null, false, null).expectStatus().isForbidden();
        }
    }
}
