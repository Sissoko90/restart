package com.abdatytch.auth_service.config;

import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.auth_service.filter.GatewayAccessWebFilter;
import com.abdatytch.auth_service.security.JwtServerAuthenticationEntryPoint;
import com.abdatytch.auth_service.security.JwtAuthenticationWebFilter;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.commons.security.HMACSignatureUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

/**
 * Matrice d'autorisation du service, et câblage des filtres de sécurité.
 *
 * <h2>Pourquoi les filtres sont construits ici et non annotés {@code @Component}</h2>
 *
 * WebFlux collecte automatiquement <b>tous</b> les beans de type
 * {@code WebFilter} et les place dans la chaîne HTTP. Spring Security en est
 * lui-même un. Un filtre à la fois annoté {@code @Component} <b>et</b> ajouté
 * par {@code addFilterAt} s'exécutait donc <b>deux fois par requête</b> : une
 * fois dans la chaîne externe, une fois dans celle de Spring Security.
 *
 * <p>C'était le cas des deux filtres de ce service, avec pour conséquences
 * concrètes : deux validations HMAC et deux analyses de jeton sur le chemin de
 * chaque requête, et chaque refus d'accès direct journalisé en double — ce qui
 * fausse toute alerte fondée sur un décompte de tentatives.
 *
 * <p>C'est le même défaut que celui corrigé sur les sept filtres du Gateway, où
 * il divisait par deux le plafond réel de la limitation de débit.
 *
 * <p>{@code ZipBombProtectionFilter} reste délibérément un bean : il doit
 * encadrer <b>tout</b> le trafic, y compris ce que Spring Security laisse passer
 * sans authentification. {@code FilterWiringTest} vérifie la règle et recense
 * cette exception.
 */
@Configuration
@EnableWebFluxSecurity
// Sans cette activation, les 33 @PreAuthorize de ce service étaient
// DÉCORATIFS : la sécurité par méthode n'était pas en place, alors que tous
// les autres services l'activaient. Seuls les chemins déclarés ci-dessous
// étaient réellement contrôlés.
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final HMACSignatureUtil hmacSignatureUtil;
    private final IJwtService jwtService;
    private final JwtServerAuthenticationEntryPoint jwtServerAuthenticationEntryPoint;

    /**
     * Secret partagé avec le Gateway, transmis au filtre par constructeur.
     *
     * <p>Le filtre n'étant plus un bean, un {@code @Value} posé sur l'un de ses
     * champs ne serait plus renseigné : il resterait {@code null}, et la
     * comparaison de signature se ferait contre une clé vide.
     */
    @Value("${gateway.secret.key}")
    private String gatewaySecretKey;

    /** Les memes chemins publics que le filtre d'acces. Voir CheminsPublics. */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            .documentationViaPasserelle()
            .construire();

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(request -> {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(List.of(
                    "http://localhost:3000",
                    "http://localhost:8080",
                    "http://localhost:8081",
                    "http://localhost:4200"
                ));
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
                config.setAllowedHeaders(List.of("*"));
                config.setExposedHeaders(List.of("Authorization", "X-Total-Count"));
                config.setAllowCredentials(true);
                config.setMaxAge(3600L);
                return config;
            }))
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint(jwtServerAuthenticationEntryPoint)
            )
            .headers(headers -> headers
                .frameOptions(ServerHttpSecurity.HeaderSpec.FrameOptionsSpec::disable)
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; frame-ancestors 'none'; form-action 'self'")
                )
            )
            .authorizeExchange(auth -> auth
                // Endpoints publics (santé, documentation)
                
                // Les chemins publics viennent de la MEME source que le filtre
                // d'acces (CheminsPublics, platform-commons) : un chemin n'est
                // joignable que s'il franchit les DEUX couches, et deux listes
                // tenues separement se contredisent en silence.
                //
                // La documentation OpenAPI y figure, et c'est voulu : signature de
                // passerelle EXIGEE, jeton non. Permettre ici n'ouvre rien tant que
                // le filtre d'acces exige la signature — l'attaquer sur le port du
                // service en direct echoue. C'est cette asymetrie qui fait de la
                // passerelle la seule porte, en entree comme en sortie.
                .pathMatchers(CHEMINS_PUBLICS.motifsPermitAll()).permitAll()
                
                // Endpoints internes (protégés par headers Gateway)
                .pathMatchers("/internal/**").permitAll()

                // Accusés de réception du fournisseur SMS.
                //
                // Appelé par AfrikSMS, qui ne peut présenter aucun jeton : il
                // ne connaît que l'URL qu'on lui a déclarée. Le point d'entrée
                // porte donc sa propre authentification — un secret partagé
                // dans la chaîne de requête, comparé à temps constant, et un
                // 404 sur refus. Voir AccuseDeReceptionSmsController.
                //
                // « permitAll » ne veut pas dire « exposé » : la requête a déjà
                // traversé GatewayAccessWebFilter, donc elle vient du Gateway
                // et porte une signature HMAC valide. Un appel direct à
                // auth-service n'atteint jamais ce contrôleur.
                //
                // Déclaré AVANT /auth/** et /admin/**, qui ne le couvrent pas,
                // mais aussi avant le anyExchange().authenticated() final, qui
                // le couvrirait.
                .pathMatchers("/sms/accuses/**").permitAll()
                
                // ==========================================================
                // SURFACE SDK : ce qui precede l'obtention d'un jeton est
                // PUBLIC, et ne peut pas etre autrement.
                //
                // La regle etait `.pathMatchers("/auth/**").authenticated()`,
                // avec pour seules exceptions les deux chemins d'administration.
                // Or l'utilisateur final d'une application appelle /auth/login
                // pour OBTENIR un jeton : lui en exiger un est impossible par
                // construction. Toute la surface destinee au SDK — inscription,
                // OTP, mot de passe oublie, OAuth2 — etait donc inatteignable
                // par ceux a qui elle s'adresse.
                //
                // Le defaut avait survecu aux tests parce que ceux-ci
                // presentaient toujours un jeton d'administration : la surface
                // etait validee en contournant le defaut.
                //
                // CE QUI PROTEGE CES CHEMINS, puisque ce n'est pas le jeton :
                //   - la signature HMAC du Gateway, exigee par
                //     GatewayAccessWebFilter : un appel direct est refuse ;
                //   - la limitation de debit du Gateway ;
                //   - le verrouillage de compte, 5 echecs / 30 minutes ;
                //   - l'OTP a usage unique, expirant en 10 minutes ;
                //   - le refus uniforme, qui n'enumere pas les comptes.
                //
                // La frontiere est celle de la SESSION : est public ce qui
                // s'appelle sans identite etablie, authentifie ce qui agit sur
                // une identite existante.
                // ==========================================================

                // --- Connexion et inscription : aucune identite prealable ---
                .pathMatchers(HttpMethod.POST, "/auth/login").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/refresh").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/forgot-password").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/reset-password").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/resend-code").permitAll()

                // --- Parcours par telephone ---
                .pathMatchers(HttpMethod.POST, "/auth/phone/auth-request").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/phone/auth-verify").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/phone/register-request").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/phone/register-verify").permitAll()

                // --- Parcours par courriel ---
                .pathMatchers(HttpMethod.POST, "/auth/email/auth-request").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/email/auth-verify").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/email/register").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/email/register-verify").permitAll()

                // --- OAuth2 : le fournisseur redirige un navigateur, qui ne
                //     porte aucun en-tete de notre choix ---
                .pathMatchers(HttpMethod.GET, "/auth/oauth2/authorize/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/auth/oauth2/callback/**").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/oauth2/callback").permitAll()
                // Apple poste son rappel sur /callback/{provider}, et la regle
                // ci-dessus ne couvre QUE le chemin exact : sans cette ligne, le
                // rappel Apple est refuse par la chaine de securite avant
                // d'atteindre le controleur. L'echec se produirait a la premiere
                // connexion Apple reelle, et le journal du controleur serait
                // muet — il n'aurait jamais ete appele.
                //
                // Ouvrir ce chemin ne retire aucune protection : la seule qui
                // compte pour un rappel est le `state`, verifie par
                // EtatOAuth2Redis au debut du traitement. La protection CSRF de
                // Spring est de toute façon desactivee sur ce service sans
                // session, donc elle ne jouait aucun role ici.
                .pathMatchers(HttpMethod.POST, "/auth/oauth2/callback/**").permitAll()
                // L'echange du code de remise contre les jetons. Necessairement
                // ouvert : l'appelant n'a pas encore de jeton, c'est
                // precisement ce qu'il vient chercher. Exiger une
                // authentification ici rendrait l'endpoint inatteignable et la
                // connexion OAuth2 n'aboutirait jamais — la correction aurait
                // casse le parcours qu'elle protege.
                //
                // Ce qui tient la porte n'est pas l'authentification mais le
                // code lui-meme : 32 octets aleatoires, a usage unique,
                // valable deux minutes, consomme a la lecture. Voir
                // CodeDeRemiseOAuth2Redis.
                .pathMatchers(HttpMethod.POST, "/auth/oauth2/exchange").permitAll()
                // Le jeton temporaire voyage desormais dans Authorization:
                // Bearer, comme tout autre justificatif de ce service. Un seul
                // canal, et c'est Spring Security qui tranche — le controleur
                // n'est plus seul a verifier.
                //
                // hasRole et NON authenticated : le jeton temporaire porte
                // ROLE_OAUTH2_TEMP et rien d'autre. JwtAuthenticationWebFilter
                // ne l'authentifie que sur ce chemin precis, de sorte qu'il ne
                // puisse satisfaire aucun `.authenticated()` ailleurs.
                .pathMatchers(HttpMethod.POST, "/auth/oauth2/complete-profile")
                        .hasRole("OAUTH2_TEMP")

                // Liste des fournisseurs sociaux utilisables : PUBLIC.
                //
                // L'ecran de connexion en a besoin avant toute session, pour
                // n'afficher que les boutons qui marchent. Ce n'est pas un
                // secret : la liste se deduit de l'ecran lui-meme. Sans cet
                // endpoint, le client code les boutons en dur et propose
                // « Se connecter avec Apple » sur une installation ou Apple
                // n'est pas configure — la personne obtient un refus apres
                // avoir clique, au lieu de ne pas voir le bouton.
                .pathMatchers(HttpMethod.GET, "/auth/oauth2/providers").permitAll()

                // --- Connexion biometrique : c'est une METHODE DE CONNEXION ---
                //
                // initiate lisait l'identite par extractUserIdFromToken : il
                // fallait donc deja etre authentifie pour s'authentifier. Le
                // meme cercle que /auth/login, a un second endroit.
                //
                // L'identifiant arrive desormais dans le corps. Seul le parcours
                // d'AUTHENTIFICATION est public ; register/** reste exige,
                // puisqu'on y ajoute un authentificateur a un compte existant.
                .pathMatchers(HttpMethod.POST, "/auth/biometric/authenticate/initiate").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/biometric/authenticate/verify").permitAll()

                // Connexion des administrateurs de la plateforme. Publics par
                // necessite, comme ci-dessus : un administrateur n'a aucune
                // application devant lui pour presenter un jeton. Chacun porte
                // sa propre verification — role d'administration, mot de passe,
                // et code TOTP des qu'il est enrole.
                .pathMatchers(HttpMethod.POST, "/auth/admin/login").permitAll()
                .pathMatchers(HttpMethod.POST, "/auth/admin/totp/enroll").permitAll()
                // Remplacement du second facteur par l'administrateur lui-meme.
                //
                // Public pour la meme raison que l'enrolement, et plus exigeant
                // que lui : il reclame le mot de passe ET le code de l'appareil
                // actuellement enrole. Il est donc ferme a qui a perdu son
                // appareil — c'est voulu. La sortie pour ce cas est
                // POST /admin/accounts/{identifier}/totp/reset, authentifie et
                // reserve au SUPER_ADMIN, qui releve de la regle
                // /admin/accounts/** ci-dessous.
                .pathMatchers(HttpMethod.POST, "/auth/admin/totp/reset").permitAll()

                // --- Ce qui agit sur une identite DEJA etablie reste exige ---
                //
                // L'effacement pour le compte d'autrui est irreversible : il
                // doit etre declare AVANT la regle generale /auth/**, sinon
                // celle-ci l'emporte et n'exige qu'une authentification.
                // C'etait le cas : n'importe quel utilisateur authentifie
                // pouvait effacer les donnees de n'importe qui.
                .pathMatchers(HttpMethod.POST, "/auth/erasure/me").authenticated()
                .pathMatchers("/auth/erasure/**").hasRole("SUPER_ADMIN")

                // Profil, deconnexion et biometrie designent un utilisateur
                // precis : sans jeton, le controleur n'aurait aucune identite a
                // laquelle se rapporter. Ils restent donc exiges, et la regle
                // generale ci-dessous les couvre.
                .pathMatchers("/auth/**").authenticated()
                
                // Endpoints admin
                .pathMatchers("/actuator/**").hasRole("ADMIN")
                .pathMatchers("/admin/accounts/**").hasRole("ADMIN")
                .pathMatchers("/admin/audit/**").hasRole("ADMIN")
                .pathMatchers("/admin/**").hasRole("ADMIN")
                .pathMatchers("/auth-users/**").hasRole("ADMIN")
                
                .anyExchange().authenticated()
            )
            // Instancies ici, et non injectes : voir la javadoc de la classe.
            // Leur seul point d'entree est cette methode, et leur ordre y est
            // explicite.
            //
            // GatewayAccessWebFilter d'abord : il garantit que la requete vient
            // bien du Gateway. Verifier une identite avant de verifier la
            // provenance reviendrait a authentifier des appels directs.
            .addFilterAt(filtreAccesGateway(), SecurityWebFiltersOrder.FIRST)
            .addFilterAt(filtreAuthentificationJwt(), SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }

    /**
     * Fabrique du filtre de provenance.
     *
     * <h3>Pourquoi une méthode surchargeable plutôt qu'un bean</h3>
     *
     * Retirer {@code @Component} supprime la double exécution, mais rendait du
     * même coup le filtre non substituable : {@code SecurityConfigTest} doit
     * pouvoir le remplacer par un passe-plat pour exercer la matrice
     * d'autorisation — en particulier la règle {@code /internal/**} permitAll,
     * dont toute la protection réside précisément dans ce filtre.
     *
     * <p>Une méthode protégée donne ce point de substitution <b>sans</b> publier
     * de bean {@code WebFilter}, donc sans rouvrir le défaut.
     */
    protected GatewayAccessWebFilter filtreAccesGateway() {
        return new GatewayAccessWebFilter(hmacSignatureUtil, gatewaySecretKey);
    }

    /** Fabrique du filtre d'authentification. Même raison que ci-dessus. */
    protected JwtAuthenticationWebFilter filtreAuthentificationJwt() {
        return new JwtAuthenticationWebFilter(jwtService);
    }
}
