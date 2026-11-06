package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.filter.GatewayAccessWebFilter;
import com.abdatytch.auth_service.security.JwtAuthenticationWebFilter;
import com.abdatytch.auth_service.security.JwtServerAuthenticationEntryPoint;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * La chaîne de sécurité d'auth-service, exercée pour de VRAI.
 *
 * <h2>Le point aveugle que ce test ferme</h2>
 *
 * Les 530 autres tests d'auth-service passent par
 * {@code WebTestClient.bindToController(...)} — qui n'installe <b>aucun
 * filtre</b> — ou par {@code SecurityConfigTest}, qui remplace les deux filtres
 * du service par des passe-plats pour n'exercer que la matrice d'autorisation.
 *
 * <p>Aucun n'exerçait donc la chaîne complète. Et c'est précisément là que
 * vivait le pire défaut de ce service : {@code JwtAuthenticationWebFilter}
 * exigeait {@code type == "developer"} alors que les jetons émis portent
 * {@code type == "user"}, si bien que <b>toute</b> la surface
 * d'administration répondait 401 — avec un jeton pourtant valide. Les tests de
 * contrôleurs passaient, ceux de la matrice passaient, et l'administration était
 * inaccessible.
 *
 * <h2>Ce qui est réellement branché ici</h2>
 *
 * <ul>
 *   <li>la vraie {@link SecurityConfig}, donc la vraie matrice d'autorisation ;</li>
 *   <li>le vrai {@link GatewayAccessWebFilter}, avec un vrai
 *       {@link HMACSignatureUtil} : les requêtes sont <b>signées comme le
 *       Gateway les signe</b>, sur {@code horodatage|chemin|méthode|identifiant} ;</li>
 *   <li>le vrai {@link JwtAuthenticationWebFilter}, qui lit les claims et pose
 *       les autorités.</li>
 * </ul>
 *
 * Seul {@code IJwtService} est simulé — il appelle interface-service pour signer,
 * ce qui sort du périmètre d'un test unitaire. Les <b>claims</b> qu'il rend sont
 * ceux qu'interface-service produit réellement
 * ({@code DevJwtServiceImpl.generateTokenForUser}).
 *
 * <p>C'est le test qui répond à la question « est-ce que les endpoints marchent
 * quand on les appelle comme Postman les appellera ».
 */
@DisplayName("auth-service - la chaîne de sécurité complète")
class ChaineReelleTest {

    /**
     * Secret EFFECTIF du filtre, lu après démarrage du contexte.
     *
     * <p>Il n'est pas imposé : voir le constructeur de
     * {@code ConfigurationReelle}.
     */
    private String secretEffectif;
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    /** Partagé avec la configuration de test : le simulacre de service JWT. */
    private static IJwtService jwtService;

    private WebTestClient client;

    // Le controleur muet est celui de SecurityConfigTest, reutilise.
    //
    // En declarer un second, annote @RestController et mappe sur /**, dans le
    // meme paquet, rendait la cartographie AMBIGUE : le balayage de
    // l'application prend les deux, et `AuthServiceApplicationTests` ne
    // demarrait plus. Un @RestController imbrique dans une classe de test reste
    // visible du balayage — contrairement a une @TestConfiguration, que le
    // TypeExcludeFilter de Spring Boot ecarte.

    /**
     * {@link SecurityConfig} avec ses VRAIS filtres.
     *
     * <p>C'est toute la différence avec {@code SecurityConfigTest}, qui les
     * remplace par des passe-plats. Ici le HMAC est réellement vérifié et les
     * claims réellement lus.
     */
    @TestConfiguration
    static class ConfigurationReelle extends SecurityConfig {

        ConfigurationReelle() {
            super(new HMACSignatureUtil(), jwtService,
                    new JwtServerAuthenticationEntryPoint(new ObjectMapper()));
            // Le secret n'est PAS pose ici.
            //
            // J'avais commence par le forcer par reflexion. Mesure : il valait
            // ensuite `TEST_ONLY_gateway_secret`, et non ma valeur. Le POM
            // parent fournit `GATEWAY_SECRET_KEY` en variable d'environnement
            // aux tests, et le @Value de SecurityConfig l'y resout — en
            // ECRASANT ce que le constructeur avait pose.
            //
            // Les quinze echecs initiaux venaient de la : le test signait avec
            // un secret que le filtre n'utilisait pas. Le test lit desormais le
            // secret EFFECTIF apres demarrage du contexte, ce qui le rend
            // indifferent a sa provenance.
        }
    }

    @EnableWebFlux
    @Import({ConfigurationReelle.class, SecurityConfigTest.ControleurMuet.class})
    static class ConfigurationDeTest {

        /**
         * Pose l'adresse du pair TCP, que {@code WebTestClient} ne fournit pas.
         *
         * <h3>Pourquoi un bean, et prioritaire</h3>
         *
         * Sans adresse, {@code getRemoteAddress()} rend {@code null},
         * {@code GatewayAccessWebFilter} en déduit {@code "unknown"} et refuse
         * tout en 403 <b>avant même</b> de regarder la signature.
         *
         * <p>Posé d'abord via {@code MockServerSpec.webFilter(...)}, le
         * décorateur ne servait à rien : ces filtres s'exécutent <b>après</b> le
         * {@code WebFilterChainProxy} de Spring Security, donc après le filtre
         * qu'ils devaient renseigner. Déclaré comme bean à
         * {@code HIGHEST_PRECEDENCE}, il passe devant — la chaîne de Spring
         * Security étant ordonnée à -100.
         *
         * <p>{@code 127.0.0.1} est la situation réelle en développement : le
         * Gateway et le service tournent sur la même machine. En conteneur ce
         * serait une adresse 172.x, couverte par {@code PrivateNetworks}.
         */
        @org.springframework.context.annotation.Bean
        @org.springframework.core.annotation.Order(
                org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        org.springframework.web.server.WebFilter posePairLocal() {
            return (echange, suite) -> suite.filter(echange.mutate()
                    .request(new org.springframework.http.server.reactive
                            .ServerHttpRequestDecorator(echange.getRequest()) {
                        @Override
                        public java.net.InetSocketAddress getRemoteAddress() {
                            return new java.net.InetSocketAddress("127.0.0.1", 54321);
                        }
                    })
                    .build());
        }
    }

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(IJwtService.class);
        when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
        when(jwtService.getUserIdFromToken(anyString())).thenReturn(UTILISATEUR);
        claims("user", List.of());

        var contexte = new AnnotationConfigReactiveWebApplicationContext();
        contexte.register(ConfigurationDeTest.class);
        contexte.refresh();
        secretEffectif = (String) org.springframework.test.util.ReflectionTestUtils
                .getField(contexte.getBean(ConfigurationReelle.class), "gatewaySecretKey");
        client = WebTestClient.bindToApplicationContext(contexte).build();
    }


    /** Pose les claims que le jeton présenté portera. */
    private void claims(String type, List<String> roles) {
        Map<String, Object> claims = new HashMap<>();
        if (type != null) {
            claims.put("type", type);
        }
        if (!roles.isEmpty()) {
            claims.put("roles", roles);
        }
        when(jwtService.extractAllClaims(anyString())).thenReturn(claims);
    }

    /**
     * Appelle un endpoint comme le Gateway le ferait.
     *
     * @param jeton valeur de l'en-tête {@code Authorization}, ou {@code null}
     * @param signee faut-il poser les en-têtes HMAC du Gateway
     */
    private WebTestClient.ResponseSpec appel(HttpMethod methode, String chemin,
                                             String jeton, boolean signee) {
        var requete = client.method(methode).uri(chemin);

        if (signee) {
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            // Signature calculee EXACTEMENT comme le Gateway la calcule, sur le
            // chemin que le service recoit — c'est-a-dire apres retrait de
            // /api/v1. Signer le chemin d'origine ferait echouer toutes les
            // verifications aval, avec un 403 generalise sans rapport apparent
            // avec la cause.
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(horodatage, chemin, methode.name(), idRequete);
            requete = requete
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(secretEffectif, donnees));
        }
        if (jeton != null) {
            requete = requete.header("Authorization", jeton);
        }
        return requete.exchange();
    }

    private WebTestClient.ResponseSpec appelSigne(String chemin, String jeton) {
        return appel(HttpMethod.GET, chemin, jeton, true);
    }

    /**
     * Appel signé sur un chemin donné <b>verbatim</b>, sans ré-encodage.
     *
     * <p>{@code uri(String)} de WebTestClient traite son argument comme un
     * gabarit d'URI et le ré-encode : un {@code %2B} y deviendrait
     * {@code %252B}, et le test porterait sur autre chose que ce qu'il croit.
     * Ce dépôt s'est déjà fait prendre par cette forme, avec
     * {@code MockServerHttpRequest.get(String)} et un {@code %3C} devenu
     * {@code %253C}. L'{@code URI} est donc construit explicitement.
     */
    private WebTestClient.ResponseSpec appelSigneVerbatim(String chemin, String jeton) {
        String horodatage = String.valueOf(System.currentTimeMillis());
        String idRequete = UUID.randomUUID().toString();
        // Signe sur le chemin BRUT, exactement comme GatewaySignatureFilter.
        String donnees = new HMACSignatureUtil()
                .buildSignatureData(horodatage, chemin, "GET", idRequete);

        var requete = client.get().uri(java.net.URI.create(chemin))
                .header("X-Gateway-Timestamp", horodatage)
                .header("X-Gateway-Request-Id", idRequete)
                .header("X-Gateway-Signature",
                        new HMACSignatureUtil().generateSignature(secretEffectif, donnees));
        if (jeton != null) {
            requete = requete.header("Authorization", jeton);
        }
        return requete.exchange();
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemin encodé : les deux côtés doivent lire la même forme")
    class CheminEncode {

        /**
         * Le défaut que ce test existe pour empêcher.
         *
         * <p>La signature HMAC porte sur le chemin. Le Gateway signe la forme
         * <b>brute</b>, telle qu'elle circule. Ce filtre lisait
         * {@code getURI().getPath()}, qui la <b>décode</b> : dès qu'un chemin
         * contient un échappement pourcent, les deux côtés comparaient deux
         * chaînes différentes.
         *
         * <p>Mesuré sur l'environnement de développement :
         *
         * <pre>
         * /admin/accounts/+22399999999/lockout-status     -> 200
         * /admin/accounts/%2B22399999999/lockout-status   -> 403
         * </pre>
         *
         * Or {@code %2B} est l'encodage <b>correct</b> d'un {@code +} dans un
         * chemin, et celui que produit tout client HTTP correct. <b>Tous</b> les
         * endpoints prenant un numéro de téléphone en variable de chemin étaient
         * donc inatteignables depuis un client normal — alors qu'ils
         * répondaient à un {@code curl} écrit à la main.
         *
         * <p>Le refus était un 403 « signature invalide », qui ne désigne pas
         * l'encodage. C'est la forme la plus coûteuse à diagnostiquer : on
         * cherche du côté des clés.
         */
        @Test
        @DisplayName("un + encodé en %2B passe, comme sa forme brute")
        void plusEncodeAccepte() {
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(appelSigneVerbatim(
                    "/admin/accounts/%2B22399999999/lockout-status", "Bearer jeton")
                    .returnResult(String.class).getStatus().value())
                    .withFailMessage("le chemin encodé est refusé : le filtre et le "
                            + "Gateway ne lisent pas la même forme du chemin")
                    .isNotEqualTo(403);
        }

        @Test
        @DisplayName("la forme brute passe aussi — le contrôle")
        void plusBrutAccepte() {
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(appelSigneVerbatim(
                    "/admin/accounts/+22399999999/lockout-status", "Bearer jeton")
                    .returnResult(String.class).getStatus().value())
                    .isNotEqualTo(403);
        }

        @ParameterizedTest(name = "chemin avec {0}")
        @ValueSource(strings = {"%20", "%40", "%2E"})
        @DisplayName("tout échappement pourcent est accepté dans une variable de chemin")
        void toutEchappementAccepte(String echappement) {
            claims("user", List.of("ROLE_ADMIN"));

            assertThat(appelSigneVerbatim(
                    "/admin/accounts/a" + echappement + "b/lockout-status", "Bearer jeton")
                    .returnResult(String.class).getStatus().value())
                    .isNotEqualTo(403);
        }

        @Test
        @DisplayName("une signature portant sur la forme DÉCODÉE est refusée")
        void signatureSurLaFormeDecodeeRefusee() {
            claims("user", List.of("ROLE_ADMIN"));
            // L'inverse du defaut : si quelqu'un signait le chemin decode, le
            // service doit refuser. Sans cela, les deux formes seraient
            // acceptees et l'invariant ne voudrait plus rien dire.
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil().buildSignatureData(
                    horodatage, "/admin/accounts/+22399999999/lockout-status",
                    "GET", idRequete);

            client.get().uri(java.net.URI.create(
                            "/admin/accounts/%2B22399999999/lockout-status"))
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(secretEffectif, donnees))
                    .header("Authorization", "Bearer jeton")
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : l'administration est atteignable")
    class AdministrationAtteignable {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/admin/accounts/x", "/admin/audit/logs", "/auth-users/x"
        })
        @DisplayName("un jeton d'administrateur atteint les endpoints d'administration")
        void administrateurAtteintAdmin(String chemin) {
            // CE TEST FERME LE POINT AVEUGLE.
            //
            // Jeton tel qu'interface-service l'emet reellement : type "user" et
            // roles prefixes. L'ancien filtre exigeait type "developer", donc il
            // n'authentifiait RIEN et toute cette surface repondait 401 — avec
            // un jeton valide, et sans qu'aucun test ne le voie.
            claims("user", List.of("ROLE_ADMIN"));

            appelSigne(chemin, "Bearer jeton")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un SUPER_ADMIN atteint aussi /admin/** - la hiérarchie survit")
        void superAdminAtteintAdmin() {
            // UserRole.expand() met [ROLE_SUPER_ADMIN, ROLE_ADMIN] dans le
            // jeton. L'ancien `rolesList.get(0)` n'en gardait qu'un, si bien
            // qu'un SUPER_ADMIN se voyait refuser ce que son subordonne
            // obtenait.
            claims("user", List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));

            appelSigne("/admin/accounts/x", "Bearer jeton").expectStatus().isOk();
        }

        @Test
        @DisplayName("l'effacement RGPD exige SUPER_ADMIN, pas seulement ADMIN")
        void effacementExigeSuperAdmin() {
            // La faille la plus grave jamais trouvee dans ce service etait ici :
            // la regle generale sur /auth/** etait declaree AVANT celle de
            // l'effacement, si bien que n'importe quel porteur de jeton pouvait
            // effacer les donnees de n'importe qui.
            claims("user", List.of("ROLE_ADMIN"));
            appelSigne("/auth/erasure/subject/x", "Bearer jeton")
                    .expectStatus().isForbidden();

            claims("user", List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN"));
            appelSigne("/auth/erasure/subject/x", "Bearer jeton")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un utilisateur sans rôle n'atteint pas l'administration")
        void utilisateurOrdinaireRefuse() {
            claims("user", List.of("ROLE_USER"));

            appelSigne("/admin/accounts/x", "Bearer jeton")
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La signature du Gateway est obligatoire")
    class SignatureObligatoire {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/auth/login", "/admin/accounts/x"})
        @DisplayName("sans signature, l'appel direct est refusé en 403")
        void sansSignature(String chemin) {
            // L'invariant de toute l'architecture : liste noire, limitation de
            // debit et blocage geographique vivent au Gateway, et ne valent que
            // si personne ne peut appeler le service en direct.
            claims("user", List.of("ROLE_ADMIN"));

            appel(HttpMethod.GET, chemin, "Bearer jeton", false)
                    .expectStatus().isForbidden();
        }

        /**
         * {@code /internal/**} ne figure PLUS dans la liste ci-dessus, et c'est
         * une correction, non un relachement.
         *
         * <h3>Ce que l'ancienne attente figeait</h3>
         *
         * Elle exigeait 403 sur {@code /internal/auth/users/x} sans signature —
         * et c'etait exactement le defaut : la passerelle n'expose <b>aucune</b>
         * route vers {@code /internal/**}, donc aucun appelant ne peut obtenir
         * cette signature. L'exigence rendait le chemin injoignable pour tout le
         * monde.
         *
         * <p>Le defaut etait VIVANT : {@code user-service/ClientIdentiteAuth}
         * appelle {@code /internal/auth/users/{id}} et recevait 403 « Missing
         * required security headers ». Son propre test passait, parce qu'il
         * parle a un serveur simule — ce test-ci etait le seul endroit du depot
         * ou le probleme etait visible, et il l'affirmait comme un invariant
         * voulu.
         *
         * <h3>Ce qui protege ce chemin maintenant</h3>
         *
         * Deux controles, et ils restent tous les deux :
         *
         * <ol>
         *   <li>le <b>pair TCP</b> doit etre une adresse privee — verifie dans
         *       le filtre AVANT l'exemption, donc toujours actif ici ;</li>
         *   <li>{@code X-Internal-Secret}, compare a temps constant par
         *       {@code InternalAuthController} sur chacun de ses deux
         *       endpoints.</li>
         * </ol>
         *
         * <p>Ce test verifie la premiere moitie du changement : l'appel franchit
         * desormais la chaine de securite. Le controleur n'est pas monte ici,
         * donc la route attrape-tout rend 200 — ce qui signifie « la requete est
         * parvenue au-dela de la securite », et rien de plus.
         */
        @Test
        @DisplayName("un chemin /internal franchit la chaîne sans signature de passerelle")
        void cheminInterneExempteDeSignature() {
            claims("user", List.of("ROLE_ADMIN"));

            appel(HttpMethod.GET, "/internal/auth/users/x", "Bearer jeton", false)
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("une signature portant un AUTRE chemin est refusée")
        void signatureSurUnAutreChemin() {
            // La signature couvre le chemin : une signature captee sur
            // /auth/login ne doit pas etre rejouable sur /admin/accounts.
            claims("user", List.of("ROLE_ADMIN"));
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(horodatage, "/auth/login", "GET", idRequete);

            client.get().uri("/admin/accounts/x")
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(secretEffectif, donnees))
                    .header("Authorization", "Bearer jeton")
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("une signature d'un AUTRE secret est refusée")
        void signatureAvecUnAutreSecret() {
            claims("user", List.of("ROLE_ADMIN"));
            String horodatage = String.valueOf(System.currentTimeMillis());
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(horodatage, "/admin/accounts/x", "GET", idRequete);

            client.get().uri("/admin/accounts/x")
                    .header("X-Gateway-Timestamp", horodatage)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature("autre-secret", donnees))
                    .header("Authorization", "Bearer jeton")
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("un horodatage expiré est refusé - anti-rejeu")
        void horodatageExpire() {
            claims("user", List.of("ROLE_ADMIN"));
            String vieux = String.valueOf(System.currentTimeMillis() - 60_000);
            String idRequete = UUID.randomUUID().toString();
            String donnees = new HMACSignatureUtil()
                    .buildSignatureData(vieux, "/admin/accounts/x", "GET", idRequete);

            client.get().uri("/admin/accounts/x")
                    .header("X-Gateway-Timestamp", vieux)
                    .header("X-Gateway-Request-Id", idRequete)
                    .header("X-Gateway-Signature",
                            new HMACSignatureUtil().generateSignature(secretEffectif, donnees))
                    .header("Authorization", "Bearer jeton")
                    .exchange()
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les types de jetons recevables")
    class TypesDeJetons {

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"refresh", "refresh_user", "temp_2fa", "maintenance"})
        @DisplayName("un jeton qui n'est pas un jeton d'accès ne donne aucun droit")
        void typeNonRecevable(String type) {
            // Un jeton de rafraichissement porte la MEME signature et la meme
            // audience qu'un jeton d'acces, mais vit bien plus longtemps.
            // L'accepter accorderait une session de la duree du
            // rafraichissement. temp_2fa est pire : il n'atteste que du premier
            // facteur.
            claims(type, List.of("ROLE_ADMIN"));

            appelSigne("/admin/accounts/x", "Bearer jeton")
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton de DÉVELOPPEUR n'obtient pas les droits de la plateforme")
        void developpeurPasAdministrateur() {
            // L'AdminSeeder d'interface-service cree un compte de PORTAIL de
            // role « ADMIN », sans rapport avec UserRole.ADMIN. Accorder une
            // autorite d'apres le nom du role ferait d'un administrateur du
            // portail un administrateur de la plateforme, par simple homonymie.
            Map<String, Object> claims = new HashMap<>();
            claims.put("type", "developer");
            claims.put("role", "ADMIN");
            when(jwtService.extractAllClaims(anyString())).thenReturn(claims);

            appelSigne("/admin/accounts/x", "Bearer jeton")
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("sans jeton, un endpoint protégé rend 401 et non 403")
        void sansJeton() {
            // La distinction compte pour le client : 401 veut dire
            // « authentifie-toi », 403 « tu n'as pas le droit ». Les confondre
            // envoie le client dans la mauvaise reprise.
            appelSigne("/admin/accounts/x", null)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("le 401 ne dit pas POURQUOI le jeton est refusé")
        void refusNonBavard() {
            appelSigne("/admin/accounts/x", null)
                    .expectBody()
                    .jsonPath("$.details").doesNotExist();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemins publics et interservice")
    class CheminsPublics {

        @ParameterizedTest(name = "{1} {0}")
        @CsvSource({
                "/auth/admin/login, POST",
                "/auth/admin/totp/enroll, POST",
                "/auth/admin/totp/reset, POST"
        })
        @DisplayName("la connexion d'administrateur est atteignable SANS jeton")
        void connexionAdministrateurPublique(String chemin, String methode) {
            // Exiger un jeton pour obtenir un jeton rendrait la connexion
            // impossible. Ces endpoints portent leur propre verification : mot
            // de passe et code TOTP.
            appel(HttpMethod.valueOf(methode), chemin, null, true)
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("/internal/** passe avec la seule signature, sans jeton")
        void internePasDeJeton() {
            // Ces chemins sont authentifies par la signature HMAC, pas par un
            // jeton. Si le filtre d'acces cessait de les couvrir, ils
            // deviendraient ENTIEREMENT publics — dont
            // DELETE /internal/auth/users/{id}.
            appelSigne("/internal/auth/users/" + UTILISATEUR, null)
                    .expectStatus().isOk();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/actuator/health", "/actuator/info"})
        @DisplayName("les sondes passent sans signature ni jeton")
        void sondesPubliques(String chemin) {
            // Consul et Prometheus ne portent ni l'une ni l'autre.
            appel(HttpMethod.GET, chemin, null, false)
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un endpoint du SDK exige un jeton, même signé")
        void sdkExigeUnJeton() {
            // Le modele Auth-as-a-Service : /auth/** est appele par
            // l'APPLICATION du developpeur pour le compte de ses utilisateurs,
            // donc avec un jeton existant.
            appelSigne("/auth/profile", null)
                    .expectStatus().isUnauthorized();
        }
    }
}
