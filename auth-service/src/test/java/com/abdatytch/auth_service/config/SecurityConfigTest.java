package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.filter.GatewayAccessWebFilter;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.auth_service.security.JwtAuthenticationWebFilter;
import com.abdatytch.auth_service.security.JwtServerAuthenticationEntryPoint;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * Matrice d'autorisation d'auth-service.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Les deux failles les plus graves trouvees dans ce service ne venaient pas d'un
 * defaut de logique mais de <b>l'ordre et de la presence des regles</b> de
 * {@link SecurityConfig} :
 *
 * <ul>
 *   <li>l'effacement irreversible des donnees d'un tiers etait couvert par la
 *       regle generale sur {@code /auth/**}, declaree avant la sienne. N'importe
 *       quel porteur de jeton pouvait effacer les donnees de n'importe qui ;</li>
 *   <li>{@code @EnableReactiveMethodSecurity} etait absent, ce qui rendait les
 *       33 {@code @PreAuthorize} du service purement decoratifs : seuls les
 *       chemins declares ici etaient reellement controles.</li>
 * </ul>
 *
 * Aucun test ne pouvait les detecter, parce qu'aucun test n'exercait les regles
 * elles-memes. Un ordre de {@code pathMatchers} se casse silencieusement : le
 * code compile, les tests des services passent, et l'endpoint repond 200 a qui
 * ne devrait pas l'atteindre.
 *
 * <h2>Ce qui est exerce, et ce qui ne l'est pas</h2>
 *
 * La chaine est construite depuis la vraie {@link SecurityConfig}, devant un
 * controleur muet. Les deux filtres du service sont remplaces :
 *
 * <ul>
 *   <li>{@link GatewayAccessWebFilter} devient un passe-plat. C'est ce qui
 *       permet d'exercer la regle {@code /internal/**").permitAll()}, dont
 *       toute la protection reside precisement dans ce filtre ;</li>
 *   <li>{@link JwtAuthenticationWebFilter} pose l'identite depuis l'en-tete
 *       {@value #EN_TETE_ROLES} au lieu de valider un jeton signe.</li>
 * </ul>
 *
 * <h2>Pourquoi l'identite n'est pas posee avec {@code mockUser()}</h2>
 *
 * {@link SecurityConfig} installe un {@code NoOpServerSecurityContextRepository}
 * — choix correct pour un service sans session, ou l'identite vient du jeton a
 * chaque requete. Mais les utilitaires de {@code spring-security-test} passent
 * par ce depot : avec un depot qui ne retient rien, l'identite simulee est
 * perdue et toutes les requetes arrivent anonymes.
 *
 * <p>L'identite est donc ecrite dans le contexte Reactor via
 * {@link ReactiveSecurityContextHolder#withAuthentication}, exactement comme le
 * fait le vrai {@link JwtAuthenticationWebFilter}. Le test suit le mecanisme
 * reel du service plutot qu'un mecanisme parallele.
 */
@DisplayName("SecurityConfig - matrice d'autorisation")
class SecurityConfigTest {

    /**
     * En-tete portant les roles a poser, en remplacement d'un jeton signe.
     *
     * <p>Plusieurs roles se separent par des virgules : c'est ainsi qu'un vrai
     * jeton les porte, {@code UserRole.expand()} placant {@code ADMIN} a cote de
     * {@code SUPER_ADMIN} a l'emission.
     */
    private static final String EN_TETE_ROLES = "X-Test-Roles";

    private WebTestClient client;

    /** Controleur muet : repond 200 a tout ce que la chaine laisse passer. */
    @RestController
    static class ControleurMuet {
        @RequestMapping("/**")
        Mono<String> tout() {
            return Mono.just("atteint");
        }
    }

    /**
     * {@link SecurityConfig} avec ses deux filtres remplaces par des passe-plats.
     *
     * <h3>Pourquoi une sous-classe de la configuration</h3>
     *
     * Les filtres ne sont plus des beans — ils s'executaient sinon deux fois par
     * requete, ce que verrouille {@link FilterWiringTest}. Ils ne peuvent donc
     * plus etre substitues en declarant un bean concurrent. {@link SecurityConfig}
     * expose a cet effet deux fabriques protegees, surchargees ici.
     *
     * <p>Des sous-classes anonymes plutot que des simulacres Mockito : un
     * simulacre de ces deux filtres echoue selon l'ordre de chargement des
     * classes, et un echec d'outillage au demarrage du contexte fait echouer les
     * 40 tests d'un coup, y compris ceux qui n'ont rien a voir — on ne lit plus
     * rien du resultat.
     */
    // @TestConfiguration, et NON @Configuration : une @Configuration imbriquee
    // dans ce paquet est captee par le balayage de composants de
    // l'application, et entre alors en conflit avec la vraie SecurityConfig —
    // deux definitions du bean securityWebFilterChain. @TestConfiguration est
    // exclue du balayage par le TypeExcludeFilter de Spring Boot.
    @TestConfiguration
    static class ConfigurationDeSecuriteDeTest extends SecurityConfig {

        ConfigurationDeSecuriteDeTest() {
            // Les arguments ne sont jamais utilises : les deux fabriques qui les
            // consommeraient sont entierement redefinies ci-dessous.
            super(null, null, new JwtServerAuthenticationEntryPoint(new ObjectMapper()));
        }

        /** Passe-plat a la place de la verification de provenance et de signature. */
        @Override
        protected GatewayAccessWebFilter filtreAccesGateway() {
            return new GatewayAccessWebFilter(null, "secret-de-test") {
                @Override
                public Mono<Void> filter(ServerWebExchange echange, WebFilterChain suite) {
                    return suite.filter(echange);
                }
            };
        }

        /**
         * Pose l'identite depuis {@value #EN_TETE_ROLES} au lieu de valider un
         * jeton signe, en ecrivant dans le contexte Reactor comme le fait le
         * vrai filtre.
         */
        @Override
        protected JwtAuthenticationWebFilter filtreAuthentificationJwt() {
            return new JwtAuthenticationWebFilter(null) {
                @Override
                public Mono<Void> filter(ServerWebExchange echange, WebFilterChain suite) {
                    String roles = echange.getRequest().getHeaders().getFirst(EN_TETE_ROLES);
                    if (roles == null || roles.isBlank()) {
                        // Aucun role demande : la requete reste anonyme, comme
                        // une requete sans jeton.
                        return suite.filter(echange);
                    }

                    List<GrantedAuthority> autorites = Arrays.stream(roles.split(","))
                            .map(String::trim)
                            .filter(role -> !role.isEmpty())
                            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();

                    var identite = new UsernamePasswordAuthenticationToken(
                            "utilisateur-de-test", null, autorites);

                    return suite.filter(echange)
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(identite));
                }
            };
        }
    }

    @EnableWebFlux
    @Import({ConfigurationDeSecuriteDeTest.class, ControleurMuet.class})
    static class ConfigurationDeTest {
    }

    @BeforeEach
    void setUp() {
        AnnotationConfigReactiveWebApplicationContext contexte =
                new AnnotationConfigReactiveWebApplicationContext();
        contexte.register(ConfigurationDeTest.class);
        contexte.refresh();

        client = WebTestClient.bindToApplicationContext(contexte).build();
    }

    /** Appelle {@code chemin} en portant {@code roles}, ou anonymement si vide. */
    private WebTestClient.ResponseSpec appel(HttpMethod methode, String chemin, String... roles) {
        WebTestClient.RequestHeadersSpec<?> requete = client.method(methode).uri(chemin);
        if (roles.length > 0) {
            requete = requete.header(EN_TETE_ROLES, String.join(",", roles));
        }
        return requete.exchange();
    }

    // ==================================================================
    @Nested
    @DisplayName("Effacement de compte")
    class Effacement {

        @Test
        @DisplayName("effacer SES donnees demande seulement d'etre authentifie")
        void effacerSesDonnees() {
            // Le droit a l'effacement (RGPD art. 17) appartient a la personne :
            // l'exercer sur soi-meme ne doit exiger aucun role particulier.
            appel(HttpMethod.POST, "/auth/erasure/me", "DEVELOPER")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("effacer les donnees D'UN TIERS est refuse a un utilisateur authentifie")
        void effacerAutruiRefuse() {
            // LA faille. Cet appel repondait 200 : la regle generale sur
            // /auth/** etait declaree avant celle-ci et l'emportait.
            appel(HttpMethod.POST, "/auth/erasure/00000000-0000-0000-0000-000000000001", "DEVELOPER")
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("effacer les donnees d'un tiers est refuse meme a un ADMIN")
        void effacerAutruiRefuseAAdmin() {
            // L'ADMIN gere les roles et le bon fonctionnement de l'application ;
            // un effacement irreversible releve du responsable technique.
            appel(HttpMethod.POST, "/auth/erasure/00000000-0000-0000-0000-000000000001", "ADMIN")
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("le SUPER_ADMIN peut effacer les donnees d'un tiers")
        void effacerAutruiParSuperAdmin() {
            appel(HttpMethod.POST, "/auth/erasure/00000000-0000-0000-0000-000000000001",
                    "SUPER_ADMIN", "ADMIN")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("sans authentification, meme l'effacement de soi est refuse")
        void effacementAnonymeRefuse() {
            appel(HttpMethod.POST, "/auth/erasure/me")
                    .expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Connexion des administrateurs")
    class ConnexionAdministrateur {

        @Test
        @DisplayName("POST /auth/admin/login est public")
        void loginPublic() {
            // Un administrateur n'a aucune application devant lui pour presenter
            // un jeton, contrairement au modele SDK ou /auth/** est appele par
            // l'application du developpeur. Sans cette regle, le compte
            // SUPER_ADMIN cree au deploiement etait inutilisable.
            appel(HttpMethod.POST, "/auth/admin/login")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("POST /auth/admin/totp/enroll est public")
        void enrolementPublic() {
            // L'enrolement est garde par le mot de passe dans le service, et ne
            // sert le secret qu'une seule fois.
            appel(HttpMethod.POST, "/auth/admin/totp/enroll")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("POST /auth/admin/totp/reset est public")
        void reinitialisationPublique() {
            // Plus exigeant que l'enrolement : mot de passe ET code de
            // l'appareil actuellement enrole. Exiger un jeton en plus
            // reviendrait a demander d'etre connecte pour reparer son moyen de
            // se connecter.
            appel(HttpMethod.POST, "/auth/admin/totp/reset")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("la reinitialisation forcee, elle, exige une session")
        void reinitialisationForceeNonPublique() {
            // La sortie pour un appareil perdu ne peut pas etre publique : ce
            // serait le contournement du second facteur lui-meme. Elle vit sous
            // /admin/accounts/**, qui exige ROLE_ADMIN, et porte en plus
            // @PreAuthorize("hasRole('SUPER_ADMIN')").
            appel(HttpMethod.POST, "/admin/accounts/x/totp/reset")
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("les autres methodes sur /auth/admin/login ne sont PAS publiques")
        void seulPostEstPublic() {
            // La regle porte sur POST uniquement. Si elle devenait un
            // pathMatchers sans methode, elle ouvrirait tout le sous-arbre.
            appel(HttpMethod.GET, "/auth/admin/login")
                    .expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La surface SDK : la frontiere est celle de la SESSION")
    class SurfaceSdk {

        /**
         * La decision qui a change, et pourquoi.
         *
         * <p>Ce test affirmait l'inverse, avec cette justification :
         *
         * <blockquote>Choix delibere : c'est l'application du developpeur qui
         * appelle ces endpoints pour son utilisateur final, en presentant son
         * propre jeton. Un {@code /auth/login} public ferait de ce service un
         * second point d'entree pour les utilisateurs finaux.</blockquote>
         *
         * <p>L'argument qui l'emporte est de construction : l'utilisateur final
         * appelle {@code /auth/login} pour <b>obtenir</b> un jeton. Lui en
         * exiger un est un cercle — et la consequence mesuree etait que
         * <b>toute</b> la surface destinee au SDK repondait 401 a ceux a qui
         * elle s'adresse. Inscription, OTP, mot de passe oublie, OAuth2 :
         * inatteignables.
         *
         * <p>Le defaut avait survecu parce que les tests presentaient toujours
         * un jeton : la surface etait validee en contournant le defaut.
         *
         * <p>Ce qui protege ces chemins n'est donc pas le jeton, mais la
         * signature HMAC du Gateway, la limitation de debit, le verrouillage de
         * compte, l'OTP a usage unique et le refus uniforme.
         */
        @ParameterizedTest(name = "{0} est joignable sans jeton")
        @ValueSource(strings = {
                "/auth/login",
                "/auth/refresh",
                "/auth/forgot-password",
                "/auth/reset-password",
                "/auth/resend-code",
                "/auth/phone/register-request",
                "/auth/phone/register-verify",
                "/auth/phone/auth-request",
                "/auth/phone/auth-verify",
                "/auth/email/register",
                "/auth/email/register-verify",
                "/auth/email/auth-request",
                "/auth/email/auth-verify",
                "/auth/oauth2/callback",
                // Rappel en form_post, impose par Apple des que le perimetre
                // demande name ou email. La regle precedente ne couvrait QUE le
                // chemin exact : sans ouverture de /callback/**, le rappel
                // Apple etait refuse par la chaine de securite et le controleur
                // n'etait jamais appele — un echec muet, visible seulement a la
                // premiere connexion Apple reelle.
                "/auth/oauth2/callback/apple",
                "/auth/biometric/authenticate/initiate",
                "/auth/biometric/authenticate/verify",
        })
        @DisplayName("ce qui precede l'obtention d'un jeton est PUBLIC")
        void sdkPublicSansJeton(String chemin) {
            // Le controleur muet repond a /** : un chemin AUTORISE l'atteint
            // donc et rend 200. Ce test ne verifie pas le metier, il verifie
            // que la requete n'est pas arretee par l'autorisation.
            appel(HttpMethod.POST, chemin)
                    .expectStatus().isOk();
        }

        @ParameterizedTest(name = "{0} exige un jeton")
        @ValueSource(strings = {
                "/auth/logout",
                // L'ENROLEMENT biometrique reste ferme : on y ajoute un
                // authentificateur a un compte existant. Seule
                // l'AUTHENTIFICATION est publique.
                "/auth/biometric/register/complete",
                "/auth/erasure/me",
                // complete-profile exige ROLE_OAUTH2_TEMP, que seul le jeton
                // temporaire porte : un appel anonyme est donc refuse.
                "/auth/oauth2/complete-profile",

                // --- Libre-service du compte ---
                //
                // Toute cette surface agit sur le compte de l'appelant, dont
                // l'identite vient du jeton. Un acces anonyme n'aurait aucun
                // compte auquel se rapporter, et un chemin ouvert par erreur
                // deviendrait un moyen d'agir sur le compte d'autrui.
                //
                // Enumeres un par un et non par un motif : un motif large
                // couvrirait aussi les chemins futurs, et ce test cesserait de
                // dire quoi que ce soit du jour ou l'un d'eux doit etre public.
                "/auth/password/change",
                "/auth/totp/enroll",
                "/auth/totp/confirm",
                "/auth/email/confirm-request",
                "/auth/email/confirm-verify",
                "/auth/phone/confirm-request",
                "/auth/phone/confirm-verify",
                // Accorder la confiance a un appareil DISPENSE du second
                // facteur : un acces anonyme ici donnerait une dispense a qui
                // n'a rien prouve.
                "/auth/trusted-devices",
        })
        @DisplayName("ce qui agit sur une identite etablie reste exige")
        void sdkFermeSansJeton(String chemin) {
            // Ces endpoints designent un utilisateur precis : sans jeton, le
            // controleur n'aurait aucune identite a laquelle se rapporter.
            appel(HttpMethod.POST, chemin)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("le profil exige un jeton, en lecture comme en ecriture")
        void profilFerme() {
            appel(HttpMethod.GET, "/auth/profile").expectStatus().isUnauthorized();
            appel(HttpMethod.PUT, "/auth/profile").expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un jeton valide reste accepte sur la surface publique")
        void jetonTolereSurLaSurfacePublique() {
            // Un developpeur qui presente son jeton ne doit pas etre refuse
            // pour autant : permitAll laisse passer, authentifie ou non.
            appel(HttpMethod.POST, "/auth/login", "DEVELOPER")
                    .expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Administration")
    class Administration {

        @ParameterizedTest(name = "{0} exige ADMIN")
        @ValueSource(strings = {
                "/admin/accounts/list",
                "/admin/audit/events",
                "/admin/templates",
                "/auth-users/00000000-0000-0000-0000-000000000001/roles",
                "/actuator/env"
        })
        @DisplayName("un utilisateur authentifie sans role d'administration est refuse")
        void administrationRefuseeAuDeveloppeur(String chemin) {
            appel(HttpMethod.GET, chemin, "DEVELOPER")
                    .expectStatus().isForbidden();
        }

        @ParameterizedTest(name = "{0} accessible a ADMIN")
        @ValueSource(strings = {
                "/admin/accounts/list",
                "/admin/audit/events",
                "/auth-users/00000000-0000-0000-0000-000000000001/roles"
        })
        @DisplayName("un ADMIN atteint les chemins d'administration")
        void administrationAccessibleAAdmin(String chemin) {
            appel(HttpMethod.GET, chemin, "ADMIN")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un SUPER_ADMIN atteint aussi les chemins d'ADMIN")
        void superAdminHeriteDAdmin() {
            // La hierarchie est resolue a l'emission du jeton : UserRole.expand()
            // place ROLE_ADMIN a cote de ROLE_SUPER_ADMIN dans les claims, plutot
            // qu'un RoleHierarchy dans chaque service.
            appel(HttpMethod.GET, "/admin/accounts/list", "SUPER_ADMIN", "ADMIN")
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("un SUPER_ADMIN seul, sans ADMIN dans ses claims, est refuse")
        void superAdminSansAdminRefuse() {
            // Consequence assumee de ce choix : les regles n'appliquent aucune
            // hierarchie, c'est le jeton qui porte les roles effectifs. Si
            // UserRole.expand() cessait d'ajouter ADMIN, le responsable
            // technique perdrait l'acces aux chemins d'ADMIN. Ce test rend la
            // dependance visible plutot que de la laisser implicite.
            appel(HttpMethod.GET, "/admin/accounts/list", "SUPER_ADMIN")
                    .expectStatus().isForbidden();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chemins publics et interservice")
    class PublicsEtInterservice {

        @ParameterizedTest(name = "{0} est public")
        @ValueSource(strings = {
                "/actuator/health",
                "/actuator/info",
                "/actuator/prometheus"
        })
        @DisplayName("les sondes sont accessibles sans jeton")
        void sondesPubliques(String chemin) {
            appel(HttpMethod.GET, chemin)
                    .expectStatus().isOk();
        }

        /**
         * La documentation n'exige <b>pas</b> de jeton — mais exige la
         * signature de la passerelle.
         *
         * <h3>L'asymétrie, et pourquoi elle est voulue</h3>
         *
         * Ce test est la moitié « chaîne Security » d'une propriété en deux
         * parties, dont l'autre est
         * {@code GatewayAccessWebFilterTest.documentationFermee}. Les deux
         * doivent rester <b>en désaccord apparent</b>, et c'est tout le
         * dispositif :
         *
         * <ul>
         *   <li><b>ici</b> : la chaîne Security sert la documentation sans
         *       jeton. Exiger un jeton la rendrait illisible, puisqu'un
         *       navigateur qui charge Swagger n'en porte pas ;</li>
         *   <li><b>là-bas</b> : le filtre d'accès exige la signature HMAC.
         *       L'attaquer directement sur le port du service échoue donc.</li>
         * </ul>
         *
         * <p>Résultat : la documentation n'est lisible qu'à travers la
         * passerelle, qui l'agrège et décide qui peut la lire. Une surface à
         * protéger au lieu de sept. <b>La passerelle est la seule porte, en
         * entrée comme en sortie.</b>
         *
         * <p>Si ce test devenait 401, Swagger cesserait de fonctionner même via
         * la passerelle. Si {@code documentationFermee} devenait vrai, la
         * documentation redeviendrait lisible en contournant la passerelle. Les
         * deux erreurs sont silencieuses, d'où ces deux tests.
         */
        @ParameterizedTest(name = "{0} ne reclame pas de jeton")
        @ValueSource(strings = {
                "/v3/api-docs",
                "/v3/api-docs/swagger-config",
                "/swagger-ui/index.html"
        })
        @DisplayName("la documentation est servie sans jeton, la signature suffit")
        void documentationSansJeton(String chemin) {
            appel(HttpMethod.GET, chemin)
                    .expectStatus().value(statut -> assertThat(statut)
                            .withFailMessage("""
                                    %s est refuse par la chaine Security (%d).

                                    La documentation doit etre servie SANS jeton : un
                                    navigateur qui charge Swagger n'en porte pas, donc
                                    elle serait illisible meme via la passerelle.

                                    Ce qui la protege est le filtre d'acces, qui exige
                                    la signature HMAC — voir
                                    GatewayAccessWebFilterTest.documentationFermee.
                                    """.formatted(chemin, statut))
                            .isNotEqualTo(401));
        }

        @Test
        @DisplayName("/actuator/env n'est PAS public malgre la regle sur /actuator/health")
        void actuatorSensibleNonPublic() {
            // Les trois sondes sont ouvertes nominativement, jamais /actuator/**.
            // Un jour quelqu'un voudra simplifier en un seul pathMatchers : ce
            // test s'y opposera. /actuator/env expose la configuration resolue,
            // donc les substitutions de secrets.
            appel(HttpMethod.GET, "/actuator/env")
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("/internal/** est public au niveau de Spring Security")
        void internalPublicCoteSecurity() {
            // Ce n'est pas un oubli : ces chemins sont reserves aux appels
            // interservice, authentifies par la signature HMAC du
            // GatewayAccessWebFilter — ici neutralise.
            //
            // La consequence merite d'etre ecrite noir sur blanc : si ce filtre
            // cessait de couvrir /internal/**, ces endpoints deviendraient
            // entierement publics. Parmi eux, DELETE /internal/auth/users/{id}.
            // C'est le filtre, et lui seul, qui les protege.
            appel(HttpMethod.GET, "/internal/auth/users/00000000-0000-0000-0000-000000000001")
                    .expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Regle de fermeture")
    class Fermeture {

        @ParameterizedTest(name = "{0} refuse a l''anonyme")
        @ValueSource(strings = {
                "/",
                "/un-chemin-inconnu",
                "/auth",
                "/admin",
                "/metrics"
        })
        @DisplayName("tout chemin non declare exige au moins une authentification")
        void anyExchangeAuthentifie(String chemin) {
            // anyExchange().authenticated() : un endpoint ajoute demain sans
            // regle explicite est ferme par defaut, pas ouvert.
            appel(HttpMethod.GET, chemin)
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("un refus d'authentification rend 401, pas une redirection")
        void refusEst401() {
            // Un service d'API ne doit pas renvoyer 302 vers une page de
            // connexion : le client est une application, pas un navigateur.
            appel(HttpMethod.GET, "/un-chemin-inconnu")
                    .expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("En-tetes de securite")
    class EnTetes {

        @Test
        @DisplayName("la politique de contenu interdit l'inclusion dans un cadre")
        void cspInterditLesCadres() {
            // frame-ancestors 'none' : defense contre le detournement de clic.
            appel(HttpMethod.GET, "/actuator/health")
                    .expectHeader().valueMatches("Content-Security-Policy",
                            ".*frame-ancestors 'none'.*");
        }

        @Test
        @DisplayName("la politique de contenu restreint les actions de formulaire")
        void cspRestreintLesFormulaires() {
            appel(HttpMethod.GET, "/actuator/health")
                    .expectHeader().valueMatches("Content-Security-Policy",
                            ".*form-action 'self'.*");
        }
    }
}
