package com.abdatytch.notification_service.config;

import com.abdatytch.commons.security.AutorisationInterne;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.notification_service.security.GatewayAccessWebFilter;
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
 * La chaine de securite de notification-service, exercee pour de VRAI.
 *
 * <h2>Le defaut trouve en ecrivant ce test</h2>
 *
 * {@code /sms/accuses/**} — le rappel par lequel l'operateur SMS annonce qu'un
 * message a bien ete remis — n'etait pas declare public dans ce module. Il
 * tombait donc dans {@code anyExchange().authenticated()}. Mesure a travers la
 * passerelle, sans jeton :
 *
 * <pre>
 * GET  /api/v1/sms/accuses/afriksms  -&gt; 401
 * POST /api/v1/sms/accuses/afriksms  -&gt; 401
 * </pre>
 *
 * <p>Un operateur SMS n'a pas de jeton et ne peut pas en avoir. Aucun accuse ne
 * pouvait donc arriver, et le service etait incapable de distinguer « remis au
 * fournisseur » de « remis au telephone ». La passerelle, elle, ouvrait bien ce
 * chemin : les deux moities de la decision divergeaient.
 *
 * <h2>Ce qui est reellement branche</h2>
 *
 * La vraie {@link SecurityConfig}, le vrai {@link GatewayAccessWebFilter}, un
 * vrai {@link HMACSignatureUtil}, et des requetes signees EXACTEMENT comme la
 * passerelle les signe — sur {@code horodatage|chemin|methode|identifiant}. Les
 * jetons sont de vrais JWT RS256, verifies par un vrai décodeur Nimbus.
 *
 * <p>C'est le test qui repond a « est-ce que les endpoints repondent quand on
 * les appelle comme la passerelle les appellera ».
 *
 * <h2>UN 200 NE PROUVE PAS QUE L'ENDPOINT EXISTE</h2>
 *
 * Le montage installe une route attrape-tout : 200 signifie « la requete a
 * franchi la securite », et rien de plus. Ce depot s'est deja fait prendre —
 * six chemins {@code /dev/auth/*} etaient declares verts par un test de chaine
 * alors qu'aucun controleur ne les servait.
 *
 * <p>{@link CheminsExerces} ferme ce trou en verifiant que chaque chemin cite
 * ici correspond bien a un {@code @RequestMapping} du module.
 */
@DisplayName("notification-service - la chaine de securite complete")
class ChaineReelleTest {

    private static final String SECRET_PASSERELLE = "secret-de-passerelle-pour-test";
    private static final String SECRET_INTERNE = "secret-interne-pour-test";
    private static final String UTILISATEUR = "22222222-2222-2222-2222-222222222222";

    /** Les chemins TELS QUE LE SERVICE LES RECOIT : la passerelle retire /api/v1. */
    private static final String BOITE = "/notifications";
    private static final String APPAREILS = "/notifications/appareils";
    private static final String ADMIN_MODELES = "/admin/modeles";
    private static final String ADMIN_ENVOIS = "/admin/notifications";
    private static final String ADMIN_SMS = "/admin/sms/solde";
    private static final String INTERNE = "/internal/message";
    private static final String ACCUSES = "/sms/accuses/afriksms";

    private static KeyPair paireDeCles;
    private WebTestClient client;

    @BeforeAll
    static void engendrerLesCles() {
        paireDeCles = Jwts.SIG.RS256.keyPair().build();
    }

    /**
     * La vraie configuration, le vrai filtre.
     *
     * <p>Le filtre est importe comme BEAN et non construit a la main : son
     * {@code @Value("${gateway.secret.key:}")} n'est injecte que sur un bean.
     * Construit par {@code new}, il aurait eu un secret nul — donc refuse tout,
     * et le test aurait mesure autre chose que la production.
     */
    @EnableWebFlux
    @Import({SecurityConfig.class, GatewayAccessWebFilter.class})
    @TestConfiguration
    static class ConfigurationDeTest {

        /**
         * Le decodeur de jetons.
         *
         * <p>En production il est construit par l'autoconfiguration a partir de
         * {@code jwk-set-uri}, qui irait chercher les cles sur le reseau. Ici il
         * verifie avec la cle publique engendree par le test : c'est une VRAIE
         * verification de signature, pas un decodeur permissif.
         */
        @Bean
        ReactiveJwtDecoder decodeur() {
            return NimbusReactiveJwtDecoder
                    .withPublicKey((RSAPublicKey) paireDeCles.getPublic())
                    .build();
        }

        /**
         * Une route attrape-tout qui rend 200.
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
         * <p>Sans elle, {@code GatewayClientIp.pairTcp} rend nul, le controle
         * d'adresse refuse tout en 403 AVANT meme de regarder la signature, et
         * tous les tests passeraient pour la mauvaise raison.
         *
         * <p>A {@code HIGHEST_PRECEDENCE}, donc devant la chaine de Spring
         * Security qui est a -100.
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

    /** Un vrai JWT RS256, tel qu'interface-service en emet. */
    private String jeton(Object roles) {
        return Jwts.builder()
                .subject(UTILISATEUR)
                .claim("roles", roles)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(paireDeCles.getPrivate())
                .compact();
    }

    /** Appelle un endpoint comme la passerelle le ferait. */
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
    @DisplayName("Le defaut corrige : le rappel de l'operateur SMS passe")
    class RappelDeLOperateur {

        /**
         * Le test qui ferme le point aveugle.
         *
         * <p>Avant correction : 401. L'operateur n'a pas de jeton et ne peut pas
         * en avoir — il appelle l'URL qu'on lui a declaree. Ce qui protege cet
         * endpoint est le parametre {@code cle}, compare a temps constant dans
         * le controleur, et il n'etait jamais atteint.
         */
        @Test
        @DisplayName("GET sans jeton est accepte par la chaine")
        void accusesEnGetSansJeton() {
            signe(ACCUSES, null).expectStatus().isOk();
        }

        @Test
        @DisplayName("POST sans jeton est accepte par la chaine")
        void accusesEnPostSansJeton() {
            appel(HttpMethod.POST, ACCUSES, null, true, null)
                    .expectStatus().isOk();
        }

        /**
         * Public ne veut pas dire hors passerelle.
         *
         * <p>{@code GatewayAccessWebFilter} porte sa PROPRE liste de chemins
         * publics, qui ne contient que les sondes et la documentation. Un rappel
         * d'operateur arrive par la passerelle, donc signe.
         */
        @Test
        @DisplayName("mais un appel NON signe reste refuse en 403")
        void accusesNonSignesRefuses() {
            appel(HttpMethod.GET, ACCUSES, null, false, null)
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La boite de reception")
    class BoiteDeReception {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {BOITE, APPAREILS})
        @DisplayName("un porteur authentifie atteint sa boite et ses appareils")
        void porteurAuthentifieAtteint(String chemin) {
            signe(chemin, jeton(List.of("ROLE_USER"))).expectStatus().isOk();
        }

        @Test
        @DisplayName("sans jeton : 401")
        void sansJeton() {
            signe(BOITE, null).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("sans signature de passerelle : 403, avant toute question de jeton")
        void sansSignature() {
            appel(HttpMethod.GET, BOITE, jeton(List.of("ROLE_USER")), false, null)
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un jeton signe par une AUTRE cle est refuse")
        void jetonEtranger() {
            String etranger = Jwts.builder()
                    .subject(UTILISATEUR)
                    .claim("roles", List.of("ROLE_USER"))
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(Jwts.SIG.RS256.keyPair().build().getPrivate())
                    .compact();
            signe(BOITE, etranger).expectStatus().isUnauthorized();
        }

        /**
         * LA TOLERANCE D'HORLOGE EST DE SOIXANTE SECONDES, ET C'EST VOULU.
         *
         * <p>Premiere version de ce test : un jeton expire depuis UNE seconde,
         * attendu en 401. Il rend 200, et le code a raison.
         * {@code NimbusReactiveJwtDecoder} installe un
         * {@code JwtTimestampValidator} dont la derive toleree vaut soixante
         * secondes par defaut — sans quoi deux machines dont les horloges
         * different d'une poignee de secondes se refuseraient mutuellement des
         * jetons parfaitement valides.
         *
         * <p>Les deux cas sont donc pinces : ce qui est DANS la tolerance
         * passe, ce qui est au-dela ne passe pas. N'exercer que le second
         * laisserait un durcissement de la tolerance a zero passer inapercu —
         * et ce durcissement casserait la production par intermittence, le plus
         * couteux des modes de panne.
         */
        private String jetonExpireDepuis(long secondes) {
            return Jwts.builder()
                    .subject(UTILISATEUR)
                    .claim("roles", List.of("ROLE_USER"))
                    .expiration(new Date(System.currentTimeMillis() - secondes * 1_000))
                    .signWith(paireDeCles.getPrivate())
                    .compact();
        }

        @Test
        @DisplayName("un jeton expire AU-DELA de la tolerance est refuse")
        void jetonExpireAuDela() {
            signe(BOITE, jetonExpireDepuis(600)).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton expire DANS la tolerance d'horloge est accepte")
        void jetonExpireDansLaTolerance() {
            signe(BOITE, jetonExpireDepuis(5)).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'administration exige le role, pas seulement un jeton")
    class Administration {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ADMIN_MODELES, ADMIN_ENVOIS, ADMIN_SMS})
        @DisplayName("un administrateur atteint les trois surfaces")
        void administrateurAtteint(String chemin) {
            signe(chemin, jeton(List.of("ROLE_ADMIN"))).expectStatus().isOk();
        }

        /**
         * La regle {@code /admin/**} manquait, et c'est elle qu'on exerce ici.
         *
         * <p>Sans elle, ces chemins tombaient dans
         * {@code anyExchange().authenticated()} : la chaine laissait passer TOUT
         * porteur authentifie, et seule l'annotation {@code @PreAuthorize}
         * refusait. Ce depot a deja trouve trente-trois {@code @PreAuthorize}
         * rendues inertes par l'absence d'une annotation d'activation.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {ADMIN_MODELES, ADMIN_ENVOIS, ADMIN_SMS})
        @DisplayName("un simple utilisateur est refuse en 403")
        void utilisateurRefuse(String chemin) {
            signe(chemin, jeton(List.of("ROLE_USER"))).expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le claim de role, dans ses DEUX formes")
    class FormesDuRole {

        /**
         * Le claim peut etre une CHAINE ou une LISTE selon l'emetteur.
         * N'en traiter qu'une refuserait la moitie des jetons.
         */
        @Test
        @DisplayName("une CHAINE vaut une liste")
        void roleEnChaine() {
            signe(ADMIN_MODELES, jeton("ROLE_ADMIN")).expectStatus().isOk();
        }

        /**
         * Le prefixe n'est ajoute que s'il manque : prefixer aveuglement
         * donnerait {@code ROLE_ROLE_ADMIN}, qui ne correspond a rien.
         */
        @Test
        @DisplayName("un role SANS prefixe ROLE_ est accepte")
        void roleSansPrefixe() {
            signe(ADMIN_MODELES, jeton(List.of("ADMIN"))).expectStatus().isOk();
        }

        @Test
        @DisplayName("un jeton SANS claim roles n'est pas administrateur")
        void aucunRole() {
            signe(ADMIN_MODELES, jeton(List.of())).expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La surface inter-services")
    class SurfaceInterne {

        /**
         * Pas de signature de passerelle, et c'est VOULU.
         *
         * <p>Aucune passerelle ne route {@code /internal/**}. Exiger la
         * signature ici serait un blocage net : interface-service l'a deja paye
         * — {@code POST /internal/jwt/sign} repondait 403, auth-service
         * n'obtenait aucun jeton, et plus aucune connexion n'aboutissait.
         */
        @Test
        @DisplayName("le secret partage suffit, sans signature ni jeton")
        void secretSuffit() {
            appel(HttpMethod.POST, INTERNE, null, false, SECRET_INTERNE)
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("sans le secret : refus")
        void sansSecret() {
            appel(HttpMethod.POST, INTERNE, null, false, null)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("avec un MAUVAIS secret : refus")
        void mauvaisSecret() {
            appel(HttpMethod.POST, INTERNE, null, false, "pas-le-bon")
                    .expectStatus().isUnauthorized();
        }

        /**
         * Un jeton d'utilisateur n'ouvre PAS la surface interne.
         *
         * <p>Un appel de service n'agit pour personne : ce qui l'authentifie est
         * le secret partage, pas une identite.
         *
         * <h4>403 et non 401, et la nuance compte</h4>
         *
         * Les deux cas precedents rendent 401 : sans jeton, la requete n'est pas
         * authentifiee. Ici elle l'est — le jeton d'administrateur est valide —
         * et c'est l'AUTORISATION qui refuse. Attendre 401 ici reviendrait a
         * exiger que la chaine oublie une identite qu'elle vient d'etablir.
         */
        @Test
        @DisplayName("un jeton d'administrateur ne remplace pas le secret")
        void jetonNeRemplacePasLeSecret() {
            appel(HttpMethod.POST, INTERNE, jeton(List.of("ROLE_ADMIN")), true, null)
                    .expectStatus().isForbidden();
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

        /**
         * Le reste d'actuator expose la configuration et les niveaux de
         * journalisation. {@code startsWith("/actuator")} les aurait tous
         * ouverts — c'est le defaut que {@code CheminsPublics} a ferme.
         */
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

        /**
         * Le garde-fou du garde-fou.
         *
         * <p>La route attrape-tout de ce montage rend 200 pour n'importe quel
         * chemin. Un test de chaine peut donc declarer vert un endpoint qui
         * n'existe pas — c'est arrive dans ce depot sur six chemins
         * {@code /dev/auth/*} qu'aucun controleur ne servait.
         *
         * <p>Ce test lit les {@code @RequestMapping} du module et verifie que
         * chaque chemin cite plus haut y correspond.
         */
        @Test
        @DisplayName("chaque chemin exerce correspond a un controleur du module")
        void cheminsAdossesAUnControleur() throws IOException {
            Set<String> bases = basesDesControleurs();

            for (String chemin : List.of(BOITE, APPAREILS, ADMIN_MODELES,
                    ADMIN_ENVOIS, ADMIN_SMS, INTERNE, ACCUSES)) {
                assertThat(bases.stream().anyMatch(
                                base -> chemin.equals(base) || chemin.startsWith(base + "/")))
                        .withFailMessage("""
                                Le chemin %s n'est servi par AUCUN controleur de ce module.

                                Bases trouvees : %s

                                Le montage de ce test installe une route attrape-tout :
                                un 200 signifie « la securite a laisse passer », jamais
                                « l'endpoint existe ». Six chemins /dev/auth/* ont deja
                                ete declares verts ainsi, alors que rien ne les servait.""",
                                chemin, bases)
                        .isTrue();
            }
        }

        /** Le garde-fou se garde : sans controleur lu, il ne verifie rien. */
        @Test
        @DisplayName("le balayage trouve bien les controleurs")
        void leBalayageFonctionne() throws IOException {
            assertThat(basesDesControleurs())
                    .withFailMessage("aucune base de controleur lue : le balayage "
                            + "est casse et le controle precedent ne protege plus rien")
                    .hasSizeGreaterThanOrEqualTo(6);
        }

        /**
         * Les {@code @RequestMapping} du module, constantes resolues.
         *
         * <p>Trois controleurs declarent leur chemin par une constante
         * ({@code AdminModeleController.CHEMIN}) : chercher la seule forme
         * litterale en manquerait la moitie.
         */
        private Set<String> basesDesControleurs() throws IOException {
            Path web = Path.of("src/main/java/com/abdatytch/notification_service/web");
            Set<String> bases = new LinkedHashSet<>();
            Pattern litteral = Pattern.compile("@RequestMapping\\(\"([^\"]+)\"\\)");
            Pattern constante = Pattern.compile("CHEMIN\\s*=\\s*\"([^\"]+)\"");

            try (Stream<Path> fichiers = Files.walk(web)) {
                for (Path source : fichiers
                        .filter(f -> f.toString().endsWith("Controller.java")).toList()) {
                    String code = Files.readString(source);
                    Matcher m = litteral.matcher(code);
                    while (m.find()) {
                        bases.add(m.group(1));
                    }
                    Matcher c = constante.matcher(code);
                    while (c.find()) {
                        bases.add(c.group(1));
                    }
                }
            }
            return bases;
        }
    }
}
