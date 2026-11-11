package com.abdatytch.market_service.config;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.commons.security.DecodeurJwtDeLaPlateforme;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.market_service.constants.CheminsDuMarche;
import com.abdatytch.market_service.security.GatewayAccessWebFilter;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La chaine de securite de market-service, exercee pour de VRAI.
 *
 * <h2>Le decodeur applique les REGLES DE PRODUCTION</h2>
 *
 * La source des cles differe — une cle publique engendree ici plutot qu'un
 * JWKS joint par le reseau — mais les regles sont celles de
 * {@code DecodeurJwtDeLaPlateforme} : signature, horodatages, et emetteur
 * verifie par son NOM.
 *
 * <p>Ce detail n'est pas cosmetique. Le test de chaine de notification-service
 * ne verifiait que la signature, et passait au vert pendant que la production
 * refusait TOUS les jetons de la plateforme — parce que {@code issuer-uri}
 * faisait comparer {@code iss} a une URL quand l'autorite emet un nom.
 *
 * <h2>UN 200 NE PROUVE PAS QUE L'ENDPOINT EXISTE</h2>
 *
 * Le montage installe une route attrape-tout. {@link CheminsExerces} verifie
 * donc que chaque chemin cite ici correspond a un controleur du module — six
 * chemins {@code /dev/auth/*} ont deja ete declares verts dans ce depot alors
 * que rien ne les servait.
 */
@DisplayName("market-service - la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET_PASSERELLE = "secret-de-passerelle-pour-test";
    private static final String SECRET_INTERNE = "secret-interne-pour-test";
    private static final String UTILISATEUR = "33333333-3333-3333-3333-333333333333";

    /** L'emetteur de cette plateforme : un NOM, et non une URL. */
    private static final String EMETTEUR = "interface-service";

    /** Les chemins TELS QUE LE SERVICE LES RECOIT : la passerelle retire /api/v1. */
    private static final String CATALOGUE = CheminsDuMarche.PRODUITS;
    private static final String BOUTIQUES = CheminsDuMarche.BOUTIQUES;
    private static final String CATEGORIES = CheminsDuMarche.CATEGORIES;
    private static final String ADMIN = "/admin/market/boutiques";

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

        /** {@code @Primary} : SecurityConfig declare aussi le sien. */
        @Bean
        @Primary
        ReactiveJwtDecoder decodeur() {
            return DecodeurJwtDeLaPlateforme.appliquerLesRegles(
                    NimbusReactiveJwtDecoder
                            .withPublicKey((RSAPublicKey) paireDeCles.getPublic())
                            .build(),
                    EMETTEUR);
        }

        /**
         * Route attrape-tout.
         *
         * <p>Un {@code RouterFunction} et non un {@code @RestController} : un
         * controleur annote, meme imbrique dans une classe de test, reste
         * visible du balayage de l'application.
         */
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route(RequestPredicates.all(),
                    requete -> ServerResponse.ok().bodyValue("ok"));
        }

        /**
         * Pose l'adresse du pair TCP, que {@code WebTestClient} ne fournit pas.
         *
         * <p>Sans elle, le controle de reseau refuse tout en 403 AVANT de
         * regarder la signature, et tous les tests passeraient pour la mauvaise
         * raison.
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

    private String jeton(Object roles) {
        return jeton(roles, EMETTEUR);
    }

    private String jeton(Object roles, String emetteur) {
        var constructeur = Jwts.builder()
                .subject(UTILISATEUR)
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(paireDeCles.getPrivate());
        if (emetteur != null) {
            constructeur.issuer(emetteur);
        }
        return constructeur.compact();
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
    @DisplayName("Le catalogue n'est PAS public")
    class CatalogueFerme {

        /**
         * Ce que montre une vitrine depend de QUI regarde.
         *
         * <p>Le pays du demandeur et sa qualite de professionnel decident de ce
         * qu'il voit. Un acces anonyme devrait donc montrer soit tout, soit
         * rien — et les deux sont faux.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {CATALOGUE, BOUTIQUES, CATEGORIES})
        @DisplayName("sans jeton : 401")
        void sansJeton(String chemin) {
            signe(chemin, null).expectStatus().isUnauthorized();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {CATALOGUE, BOUTIQUES, CATEGORIES})
        @DisplayName("avec un jeton valide : la chaine laisse passer")
        void avecJeton(String chemin) {
            signe(chemin, jeton(List.of("ROLE_USER"))).expectStatus().isOk();
        }

        @Test
        @DisplayName("sans signature de passerelle : 403, avant toute question de jeton")
        void sansSignature() {
            appel(HttpMethod.GET, CATALOGUE, jeton(List.of("ROLE_USER")), false, null)
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'emetteur du jeton est verifie")
    class Emetteur {

        /**
         * Le defaut mesure sur notification-service, pince ici d'avance.
         *
         * <p>{@code issuer-uri} faisait comparer {@code iss} a une URL alors
         * que l'autorite emet un NOM : tous les jetons etaient refuses, et
         * toute la surface HTTP du service etait injoignable.
         */
        @Test
        @DisplayName("un emetteur etranger est refuse")
        void emetteurEtranger() {
            signe(CATALOGUE, jeton(List.of("ROLE_USER"), "https://autorite.invalid"))
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton SANS emetteur est refuse")
        void sansEmetteur() {
            signe(CATALOGUE, jeton(List.of("ROLE_USER"), null))
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton signe par une AUTRE cle est refuse")
        void jetonEtranger() {
            String etranger = Jwts.builder()
                    .subject(UTILISATEUR)
                    .issuer(EMETTEUR)
                    .claim("roles", List.of("ROLE_USER"))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(Jwts.SIG.RS256.keyPair().build().getPrivate())
                    .compact();
            signe(CATALOGUE, etranger).expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La moderation exige le role, pas seulement un jeton")
    class Moderation {

        @Test
        @DisplayName("un administrateur atteint la file")
        void administrateur() {
            signe(ADMIN, jeton(List.of("ROLE_ADMIN"))).expectStatus().isOk();
        }

        /**
         * La regle {@code /admin/**} de la chaine, exercee ici.
         *
         * <p>Sans elle, ces chemins tomberaient dans
         * {@code anyExchange().authenticated()} : tout porteur authentifie
         * pourrait approuver une boutique, et seule l'annotation refuserait.
         */
        @Test
        @DisplayName("un simple utilisateur est refuse en 403")
        void utilisateurRefuse() {
            signe(ADMIN, jeton(List.of("ROLE_USER"))).expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un role SANS prefixe ROLE_ est accepte")
        void roleSansPrefixe() {
            signe(ADMIN, jeton(List.of("ADMIN"))).expectStatus().isOk();
        }

        @Test
        @DisplayName("un claim de role en CHAINE vaut une liste")
        void roleEnChaine() {
            signe(ADMIN, jeton("ROLE_ADMIN")).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Actuator : les sondes passent, le reste non")
    class Actuator {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness",
                "/actuator/prometheus"})
        @DisplayName("une sonde repond sans jeton ET sans signature")
        void sondesOuvertes(String chemin) {
            appel(HttpMethod.GET, chemin, null, false, null).expectStatus().isOk();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/env", "/actuator/loggers", "/actuator/beans"})
        @DisplayName("le reste d'actuator exige le role ADMIN")
        void actuatorProtege(String chemin) {
            signe(chemin, jeton(List.of("ROLE_USER"))).expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les chemins exerces existent vraiment")
    class CheminsExerces {

        @Test
        @DisplayName("chaque chemin exerce correspond a un controleur du module")
        void cheminsAdossesAUnControleur() throws IOException {
            Set<String> bases = basesDesControleurs();

            for (String chemin : List.of(CATALOGUE, BOUTIQUES, CATEGORIES, ADMIN)) {
                assertThat(bases.stream().anyMatch(
                                base -> chemin.equals(base) || chemin.startsWith(base + "/")))
                        .withFailMessage("""
                                Le chemin %s n'est servi par AUCUN controleur du module.

                                Bases trouvees : %s

                                Le montage installe une route attrape-tout : un 200 \
                                signifie « la securite a laisse passer », jamais \
                                « l'endpoint existe ».""", chemin, bases)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("le balayage trouve bien les controleurs")
        void leBalayageFonctionne() throws IOException {
            assertThat(basesDesControleurs()).hasSizeGreaterThanOrEqualTo(4);
        }

        /** Les {@code @RequestMapping}, constantes resolues. */
        private Set<String> basesDesControleurs() throws IOException {
            Path web = Path.of("src/main/java/com/abdatytch/market_service/web");
            Path constantes = Path.of("src/main/java/com/abdatytch/market_service/"
                    + "constants/CheminsDuMarche.java");
            Set<String> bases = new LinkedHashSet<>();

            Matcher c = Pattern.compile("=\\s*\"(/[^\"]+)\"")
                    .matcher(Files.readString(constantes));
            while (c.find()) {
                bases.add(c.group(1));
            }

            Pattern litteral = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
            try (Stream<Path> fichiers = Files.walk(web)) {
                for (Path source : fichiers
                        .filter(f -> f.toString().endsWith("Controller.java")).toList()) {
                    Matcher m = litteral.matcher(Files.readString(source));
                    while (m.find()) {
                        bases.add(m.group(1));
                    }
                }
            }
            return bases;
        }
    }
}
