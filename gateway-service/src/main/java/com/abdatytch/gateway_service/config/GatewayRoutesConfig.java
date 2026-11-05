package com.abdatytch.gateway_service.config;

import com.abdatytch.commons.security.JwtClaimsReader;
import com.abdatytch.gateway_service.util.SecurityLogUtil;
import com.abdatytch.gateway_service.routing.CheminCible;
import com.abdatytch.gateway_service.security.ClientIpResolver;
import com.abdatytch.gateway_service.security.JwtWebFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.jsonwebtoken.Claims;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static org.springframework.web.reactive.function.server.RequestPredicates.*;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;


/**


 * Configuration du routing réactif pour le Gateway
 * Architecture non-bloquante: Netty EventLoop (Selector) + Channels + Buffers + Worker Threads
 *
 * Flux:
 * 1. Client  Netty Selector (EventLoop) détecte la requête
 * 2. Channel alloue un Buffer pour lire les données
 * 3. Worker Thread traite la requête de manière asynchrone (Mono/Flux)
 * 4. WebClient proxifie vers auth-service via Consul (load balancing)
 * 5. Réponse retournée sans bloquer le thread
 */
@Configuration
public class GatewayRoutesConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayRoutesConfig.class);

    /**

     * En-têtes dont le Gateway est le seul émetteur légitime : ils sont retirés
     * de la requête entrante avant proxification, puis réécrits à partir de
     * sources vérifiées (claims validés, IP résolue). Sans ce retrait, un client
     * pourrait annoncer lui-même son identité ou son adresse aux services aval.
     */
    private static final java.util.Set<String> GATEWAY_OWNED_HEADERS = java.util.Set.of(
            "x-auth-user-id",
            "x-user-role",
            "x-forwarded-for",
            "x-real-ip",
            // Pose par GatewaySignatureFilter a partir de l'IP resolue. Le
            // signature filter purge deja la version du client ; l'inscrire ici
            // aussi garantit qu'une valeur fournie par l'appelant ne passe pas,
            // meme si l'ordre des filtres changeait un jour.
            "x-client-ip");

    /**
     * Delai maximal d'une proxification vers un service aval.
     *
     * <p>Plus long que les delais internes des services (5 s) : le Gateway
     * attend une reponse complete, qui inclut le travail du service aval. Une
     * connexion coute a elle seule 241 ms de BCrypt, et un televersement de
     * document bien davantage.
     *
     * <p>Mais borne tout de meme : sans delai, le disjoncteur pose juste
     * au-dessus ne voyait jamais echouer un service fige, donc ne s'ouvrait
     * jamais.
     */
    @Value("${gateway.proxy.timeout-seconds:30}")
    private long delaiDeProxificationEnSecondes;

    private java.time.Duration delaiDeProxification;

    @jakarta.annotation.PostConstruct
    void initialiserDelai() {
        this.delaiDeProxification =
                java.time.Duration.ofSeconds(delaiDeProxificationEnSecondes);
    }

    @Value("${gateway.secret.key}")
    private String gatewaySecretKey;

    private final ClientIpResolver clientIpResolver;
    private final ReactiveDiscoveryClient discoveryClient;

    /**
     * Choix de l'instance aval : <b>equilibre</b>, et il ne l'etait pas.
     *
     * <h3>Le defaut</h3>
     *
     * La proxification resolvait l'instance par
     * {@code discoveryClient.getInstances(nom).next()} — c'est-a-dire la
     * <b>premiere</b> que Consul renvoie. Avec plusieurs instances d'un service,
     * <b>une seule recevait tout le trafic</b> ; les autres restaient inactives
     * tout en etant saines et enregistrees.
     *
     * <p>Et le defaut etait masque : les routes declarees en YAML utilisent bien
     * {@code uri: lb://...}, donc un equilibrage correct. Mais ce
     * {@code RouterFunction} a l'ordre -1 et Spring Cloud Gateway l'ordre 1 :
     * pour les douze groupes de chemins declares ici — auth, admin, media,
     * quotas, settings — c'est ce code qui tranche, et les {@code lb://}
     * correspondants ne sont jamais atteints. En lisant la configuration, on
     * concluait a un equilibrage qui n'avait pas lieu.
     *
     * <h3>Pourquoi le Factory et non un @LoadBalanced WebClient</h3>
     *
     * Le {@code WebClient} de ce Gateway est celui de {@code NettyConfig},
     * partage et deliberement non decore. L'annoter changerait le comportement
     * de tous ses usages ; passer par le Factory ne change que le choix de
     * l'instance, ici, visiblement.
     */
    private final org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory
            fabriqueDEquilibrage;
    private final CircuitBreaker authServiceCircuitBreaker;
    private final CircuitBreaker userServiceCircuitBreaker;
    private final CircuitBreaker defaultCircuitBreaker;

    public GatewayRoutesConfig(
            ReactiveDiscoveryClient discoveryClient,
            org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory
                    fabriqueDEquilibrage,
            ClientIpResolver clientIpResolver,
            @Qualifier("authServiceCircuitBreaker") CircuitBreaker authServiceCircuitBreaker,
            @Qualifier("userServiceCircuitBreaker") CircuitBreaker userServiceCircuitBreaker,
            @Qualifier("defaultCircuitBreaker") CircuitBreaker defaultCircuitBreaker) {
        this.discoveryClient = discoveryClient;
        this.fabriqueDEquilibrage = fabriqueDEquilibrage;
        this.clientIpResolver = clientIpResolver;
        this.authServiceCircuitBreaker = authServiceCircuitBreaker;
        this.userServiceCircuitBreaker = userServiceCircuitBreaker;
        this.defaultCircuitBreaker = defaultCircuitBreaker;
    }

    /**

     * RouterFunction pour routing manuel réactif
     * Remplace Spring Cloud Gateway qui n'existe pas dans Spring Boot 4.0.1
     * Utilise le WebClient de NettyConfig
     */
    @Bean
    public RouterFunction<ServerResponse> gatewayRoutes(WebClient webClient) {

        return route()
            // Route: /.well-known/jwks.json  interface-service (decouverte des cles)
            //
            // LE DEFAUT CORRIGE : cette route proxifiait vers AUTH-SERVICE, qui
            // ne sert aucun chemin `/.well-known`. L'autorite JWT de la
            // plateforme est interface-service, seul detenteur des cles privees
            // (DevKeyManagementController) ; auth-service ne fait que CONSOMMER
            // le JWKS, par InterfaceServiceClient.
            //
            // Le chemin standard rendait donc une erreur. Rien ne le signalait :
            // une route qui existe et ne mene nulle part est indistinguable d'un
            // chemin inexistant. Et le chemin explicite
            // `/dev/.well-known/jwks.json` fonctionnait par une route YAML, donc
            // les services ne s'en plaignaient pas.
            //
            // CheminCible ramene ce chemin sur `/dev/.well-known/jwks.json`, le
            // chemin reellement servi — le controleur porte
            // `@RequestMapping("/dev")`. Le routeur et le filtre de signature
            // lisent la MEME transformation, donc ils ne peuvent pas diverger.
            .GET("/.well-known/jwks.json", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))

            // ==============================================================
            // La documentation OpenAPI de chaque service
            // ==============================================================
            // `/api/v1/docs/<service>` est ramene par CheminCible sur
            // `/v3/api-docs`, le chemin servi par springdoc dans chaque service.
            //
            // GET SEULEMENT : un document OpenAPI se lit. Ouvrir POST, PUT ou
            // DELETE ici n'ajouterait aucune fonction et offrirait une surface
            // d'ecriture vers un chemin que personne ne surveille.
            //
            // Ces chemins sont SIGNES comme les autres : les services
            // n'exemptent la documentation que du jeton, jamais de la signature.
            // Elle n'est donc lisible qu'a travers cette passerelle, et
            // `DocumentationBasicAuthWebFilter` y controle qui peut la lire.
            .GET("/api/v1/docs/auth", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
            .GET("/api/v1/docs/interface", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
            .GET("/api/v1/docs/users", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", userServiceCircuitBreaker))
            .GET("/api/v1/docs/media", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
            .GET("/api/v1/docs/quotas", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
            .GET("/api/v1/docs/settings", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
            .GET("/api/v1/docs/security", request -> proxyRequestWithCircuitBreaker(request, webClient, "security-service", defaultCircuitBreaker))

            // Route: /api/v1/sms/accuses/** auth-service (accuses de reception SMS)
            //
            // Declaree dans le RouterFunction et non en YAML : le
            // RouterFunctionMapping de Spring a une priorite plus forte que le
            // RoutePredicateHandlerMapping de Spring Cloud Gateway, donc une
            // route YAML seule serait masquee des qu'un chemin du
            // RouterFunction la recouvrirait. Ici il n'y a pas de recouvrement,
            // mais la coherence vaut mieux que la chance.
            //
            // GET et POST : typeNotification vaut 1 (POST) ou 2 (GET) chez
            // AfrikSMS, et ce reglage vit de leur cote.
            .nest(path("/api/v1/sms/accuses/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
            )

            // ---------------------------------------------------------------
            // auth-service : trois surfaces, trois prefixes publics
            //
            // auth-service et interface-service exposent TOUS LES DEUX /admin/**
            // et /auth/**. Le retrait du prefixe /api/v1 ne suffit donc pas a
            // les distinguer : les chemins publics ci-dessous sont distincts, et
            // CheminCible les ramene sur le chemin reel d'auth-service.
            //
            // interface-service garde /api/v1/auth/** et /api/v1/admin/** : son
            // contrat d'URL ne change pas.
            // ---------------------------------------------------------------

            // /api/v1/auth-admin/**  ->  auth-service  /admin/**
            .nest(path("/api/v1/auth-admin/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
            )

            // /api/v1/auth-sdk/**  ->  auth-service  /auth/**
            .nest(path("/api/v1/auth-sdk/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
            )

            // /api/v1/auth-users/**  ->  auth-service  /auth-users/**  (sans reecriture)
            .nest(path("/api/v1/auth-users/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "auth-service", authServiceCircuitBreaker))
            )

            // Route: /api/v1/auth/** interface-service via Consul (avec Circuit Breaker)
            .nest(path("/api/v1/auth/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/users/** user-service via Consul (avec Circuit Breaker)
            .nest(path("/api/v1/users/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", userServiceCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", userServiceCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", userServiceCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", userServiceCircuitBreaker))
            )
            // Route: /api/v1/media/** media-service via Consul (avec Circuit Breaker)
            .nest(path("/api/v1/media/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/quotas/** quotas-service via Consul (avec Circuit Breaker)
            .nest(path("/api/v1/quotas/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/settings/** setting-service via Consul (avec Circuit Breaker)
            .nest(path("/api/v1/settings/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/security-admin/** security-service
            //
            // Cette route n'existait pas, et c'etait le defaut : security-service
            // etait complet, teste, configure — et route par AUCUN des deux
            // mecanismes. Ses sept endpoints restaient injoignables, et rien ne
            // le signalait puisque la passerelle rend 404, indistinguable d'un
            // chemin inexistant.
            //
            // Le prefixe public est identique au chemin interne : CheminCible
            // retire /api/v1 et n'a aucune reecriture a appliquer, car
            // /security-admin ne collisionne avec rien.
            .nest(path("/api/v1/security-admin/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "security-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "security-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "security-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "security-service", defaultCircuitBreaker))
            )
            // notification-service N'EST PAS route ici, et c'est delibere.
            //
            // J'avais ajoute une route /api/v1/notifications/** a cet endroit,
            // sans voir qu'une route YAML pour le MEME chemin existait deja
            // (config-repo/dev/gateway-service-dev.yml, id: notification-service).
            // Comme le RouterFunctionMapping de Spring a une priorite plus
            // forte que le RoutePredicateHandlerMapping de Spring Cloud
            // Gateway, la mienne masquait la sienne : deux declarations pour un
            // chemin, dont une seule agissait.
            //
            // Les deux routes de ce service vivent donc en YAML :
            //   /api/v1/notifications/**    la boite de reception (REST)
            //   /api/v1/notifications-flux  le flux temps reel (WebSocket)
            //
            // Et le flux DOIT y rester : ce RouterFunction proxifie avec
            // WebClient, qui ne sait pas effectuer une montee en protocole.
            // Son chemin porte un tiret et non un segment sous
            // /notifications/, pour qu'aucune route de ce fichier ne puisse le
            // capter un jour.
            // Surfaces d'administration de quotas-service et setting-service.
            //
            // Elles n'etaient routees nulle part, et /api/v1/admin/** les
            // envoyait a interface-service, qui ne porte ni /admin/quotas ni
            // /admin/settings : quatre endpoints rendaient 404.
            //
            // AVANT la route generale /api/v1/admin/**, comme les chemins de
            // media-service juste en dessous.
            .nest(path("/api/v1/admin/quotas/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "quotas-service", defaultCircuitBreaker))
            )
            .nest(path("/api/v1/admin/settings/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "setting-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/files/** user-service (photos de profil)
            //
            // Cette route n'existait NULLE PART, et le chemin etait pourtant
            // celui que l'API rend aux clients : POST /users/me/profile-picture
            // repond un `profilePictureUrl` construit sur `file.base.url`.
            //
            // Trois raisons independantes rendaient cette URL injoignable :
            // elle n'etait routee par aucun des deux mecanismes ; elle designait
            // le port du service (8083), que l'exterieur ne peut pas atteindre ;
            // et elle portait le prefixe /api/v1, que le controleur n'attend pas
            // — un appel direct aurait donc rendu 404 lui aussi.
            //
            // Une photo de profil enregistree etait donc inaffichable, et rien
            // ne le signalait : l'envoi repondait 200 avec une URL d'apparence
            // valide.
            //
            // GET seulement : l'ecriture passe par /users/me/profile-picture,
            // qui valide le type et le plafond de taille. Ouvrir POST ici
            // donnerait un second chemin d'ecriture sans ces controles.
            .nest(path("/api/v1/files/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/admin/users/** user-service
            //
            // Cette route n'existait pas ici, et c'etait le defaut : elle etait
            // declaree en YAML (`id: user-admin`), ou le RouterFunction la
            // masquait. Le RouterFunctionMapping de Spring a une priorite plus
            // forte que le RoutePredicateHandlerMapping de Spring Cloud
            // Gateway, donc la route generale /api/v1/admin/** ci-dessous
            // capturait /api/v1/admin/users/** et l'envoyait a
            // interface-service, qui ne porte pas cette surface.
            //
            // Toute l'administration des comptes — blocage, bannissement,
            // suspension, revocation, retablissement, listes — etait donc
            // injoignable depuis la passerelle. Rien ne le signalait : la
            // reponse etait un 404, indistinguable d'un chemin inexistant.
            //
            // AVANT /api/v1/admin/**, comme les chemins de quotas-service,
            // setting-service et media-service.
            .nest(path("/api/v1/admin/users/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "user-service", defaultCircuitBreaker))
            )
            // Routes spécifiques AVANT la route générale (ordre important!)
            // Route: /api/v1/admin/document-types/** media-service via Consul (endpoints admin)
            .nest(path("/api/v1/admin/document-types/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/admin/role-requirements/** media-service via Consul (endpoints admin)
            .nest(path("/api/v1/admin/role-requirements/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
            )
            // Route: /api/v1/admin/documents/** media-service via Consul (endpoints admin)
            .nest(path("/api/v1/admin/documents/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "media-service", defaultCircuitBreaker))
            )
            // Route générale: /api/v1/admin/** interface-service via Consul (Interface Admin Console)
            // DOIT être APRÈS les routes spécifiques pour éviter de les capturer
            .nest(path("/api/v1/admin/**"), builder -> builder
                .GET("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
                .POST("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
                .PUT("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
                .DELETE("/**", request -> proxyRequestWithCircuitBreaker(request, webClient, "interface-service", defaultCircuitBreaker))
            )
            .build();
    }

    /**

     * Proxifie la requête avec Circuit Breaker pour protection contre cascading failures
     * Utilise l'opérateur réactif de Resilience4j (100% non-bloquant)
     */
    private Mono<ServerResponse> proxyRequestWithCircuitBreaker(
            org.springframework.web.reactive.function.server.ServerRequest request,
            WebClient webClient,
            String serviceName,
            CircuitBreaker circuitBreaker) {

        // Utilisation de l'opérateur réactif CircuitBreakerOperator
        // au lieu de .block() qui bloquait le thread Netty
        return proxyRequest(request, webClient, serviceName)
            // DELAI MAXIMAL, a l'INTERIEUR du disjoncteur.
            //
            // Il n'y en avait aucun, et le disjoncteur ci-dessous etait donc
            // AVEUGLE au cas de panne le plus courant : un service aval FIGE —
            // pas tombe, fige — ne produit aucun signal terminal, donc rien a
            // compter, donc le disjoncteur ne s'ouvre jamais. Les requetes
            // s'accumulaient au Gateway sans aboutir ni echouer.
            //
            // L'ordre importe : pose a l'exterieur du disjoncteur, le delai
            // n'aurait pas davantage fourni d'echec a compter. A l'interieur,
            // chaque tentative se termine franchement, et le disjoncteur peut
            // enfin decider d'arreter d'essayer.
            //
            // Ce Gateway porte TOUT le trafic de la plateforme : c'est le
            // dernier endroit ou un appel sortant non borne devait subsister.
            .timeout(delaiDeProxification)
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .onErrorResume(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class, error -> {
                log.warn(" Circuit Breaker [{}] is OPEN - rejecting request to {}",
                    serviceName, request.path());
                return ServerResponse.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(String.format(
                        "{\"error\":\"Service %s temporarily unavailable\",\"message\":\"Circuit breaker is open - too many failures\"}",
                        serviceName
                    ));
            })
            .onErrorResume(error -> {
                // Le MESSAGE de l'erreur n'est plus renvoye au client.
                //
                // Il etait interpole dans le corps de la reponse 503. Or le
                // message d'une exception WebClient contient l'URI appelee,
                // donc l'adresse et le port de l'instance aval resolue par
                // Consul : la topologie interne partait au client a chaque
                // panne. Elle reste dans le journal, qui n'est pas public.
                log.error("Proxification vers {} en echec : {}",
                    serviceName, error.getClass().getSimpleName(), error);
                return ServerResponse.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(String.format(
                        "{\"error\":\"Service %s temporarily unavailable\"}", serviceName));
            });
    }

    /**
     * Choisit une instance du service, en repartissant la charge.
     *
     * <p>{@code RoundRobinLoadBalancer} par defaut dans Spring Cloud
     * LoadBalancer : chaque requete va a l'instance suivante. Le
     * {@code ReactiveDiscoveryClient} reste injecte comme <b>repli</b> pour le
     * cas ou aucun equilibreur n'est configure pour ce service — et ce repli
     * journalise, pour qu'un retour silencieux au comportement « premiere
     * instance » ne passe pas inaperçu.
     *
     * <p>Une absence d'instance est une erreur explicite et non un
     * {@code Mono} vide : vide, la chaine se terminerait sans reponse et le
     * client attendrait son propre delai d'expiration, ce qui ne designe rien.
     */
    private Mono<org.springframework.cloud.client.ServiceInstance> choisirInstance(
            String serviceName) {
        var equilibreur = fabriqueDEquilibrage.getInstance(serviceName);
        if (equilibreur == null) {
            log.warn("Aucun equilibreur pour {} : repli sur la premiere instance, "
                    + "le trafic n'est PAS reparti", serviceName);
            return discoveryClient.getInstances(serviceName).next()
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                            "Aucune instance disponible pour " + serviceName)));
        }
        return Mono.from(equilibreur.choose())
                .flatMap(reponse -> reponse.hasServer()
                        ? Mono.just((org.springframework.cloud.client.ServiceInstance)
                                reponse.getServer())
                        : Mono.error(new IllegalStateException(
                                "Aucune instance disponible pour " + serviceName)))
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Aucune instance disponible pour " + serviceName)));
    }

    /**

     * Proxifie la requête vers le service backend de manière réactive
     * Résout l'instance via Consul Discovery
     *
     * @param request Requête entrante (ServerRequest)
     * @param webClient WebClient standard
     * @param serviceName Nom du service Consul (ex: auth-service)
     * @return Mono<ServerResponse> réponse asynchrone
     */
    private Mono<ServerResponse> proxyRequest(
            org.springframework.web.reactive.function.server.ServerRequest request,
            WebClient webClient,
            String serviceName) {

        log.debug(" Gateway forwarding: {} {} to {}", request.method(), request.uri(), serviceName);
        // Le message qui annoncait « Adding X-Secret-Key header » a ete retire.
        //
        // Aucun code ne l'ajoutait : l'en-tete a ete sorti du protocole parce
        // qu'il transportait le secret partage a chaque requete, et la
        // signature HMAC prouve la meme chose sans le transmettre. Le journal
        // decrivait donc un comportement disparu — et c'est ce qui coute le plus
        // cher a diagnostiquer, puisqu'il envoie chercher du cote des secrets un
        // refus qui vient d'ailleurs.

        // Le chemin cible vient de CheminCible, la MEME source que
        // GatewaySignatureFilter. Le service en aval recalcule la signature sur
        // le chemin recu : si ces deux endroits divergent, tout est refuse en
        // 403 sans que rien ne designe le chemin comme cause.
        String originalPath = request.path();
        String targetPath = CheminCible.pour(originalPath);
        String queryString = request.uri().getQuery() != null ? "?" + request.uri().getQuery() : "";

        // Identité issue des claims VALIDÉS par JwtWebFilter (signature RSA vérifiée).
        // Ne jamais re-décoder le payload JWT à la main ici : il ne serait pas vérifié.
        Claims validatedClaims = validatedClaims(request);
        String userId = validatedClaims != null ? validatedClaims.getSubject() : null;
        if (userId != null) {
            log.debug(" userId issu des claims validés: {}", userId);
        }

        String clientIp = clientIpResolver.resolve(request.exchange());

        // Détecter si c'est une requête multipart
        String contentType = request.headers().firstHeader("Content-Type");
        boolean isMultipart = contentType != null && contentType.startsWith("multipart/");
        log.debug(" Content-Type: {}, isMultipart: {}", contentType, isMultipart);

        // Résoudre l'instance du service via Consul Discovery
        return choisirInstance(serviceName)
            .flatMap(instance -> {
                // Construire l'URI complète avec l'instance résolue
                String fullUri = instance.getUri().toString() + targetPath + queryString;

                log.debug(" Proxification vers: {}", fullUri);

                // Proxifier la requête de manière réactive
                return webClient
                    .method(request.method())
                    // uri(URI) et NON uri(String).
                    //
                    // La surcharge String traite son argument comme un GABARIT
                    // d'URI et ré-encode le pourcent. Mesuré par
                    // UriDeProxificationTest :
                    //
                    //   uri(String) -> /admin/accounts/%252B22399999999/...
                    //   uri(URI)    -> /admin/accounts/%2B22399999999/...
                    //
                    // Le Gateway SIGNE le chemin avant de proxifier. En
                    // ré-encodant, il émettait un chemin différent de celui
                    // qu'il venait de signer : il contredisait sa propre
                    // signature. Tout échappement pourcent dans un chemin
                    // cassait donc la requête — y compris le %2B d'un numéro de
                    // téléphone, c'est-à-dire ce que produit n'importe quel
                    // client HTTP correct, Postman compris.
                    //
                    // Constaté sur les trois services routés, avec des symptômes
                    // differents qui masquaient la cause commune : 400 pour
                    // auth-service et interface-service, 503 pour user-service.
                    .uri(java.net.URI.create(fullUri))
                    .headers(headers -> {
                        // Copier les headers de la requête originale (incluant ceux de
                        // GatewaySignatureFilter), en excluant ceux que seul le Gateway
                        // a le droit d'émettre : ils sont réécrits ci-dessous à partir
                        // des claims validés et de l'IP résolue, jamais recopiés depuis
                        // le client.
                        request.headers().asHttpHeaders().forEach((key, value) -> {
                            if (!key.equalsIgnoreCase(HttpHeaders.HOST)
                                    && !GATEWAY_OWNED_HEADERS.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                                headers.addAll(key, value);
                            }
                        });

                        if (userId != null) {
                            headers.set("X-Auth-User-Id", userId);
                        }

                        String userRole = JwtClaimsReader.primaryRole(validatedClaims);
                        if (userRole != null) {
                            headers.set("X-User-Role", userRole);
                            log.debug(" userRole issu des claims validés: {}", userRole);
                        }

                        // Les services en aval décident de journalisation, de
                        // géolocalisation et de blocage à partir de cet en-tête.
                        // Le Gateway est le seul point capable de l'établir
                        // honnêtement : il l'écrase avec l'IP qu'il a résolue.
                        headers.set("X-Forwarded-For", clientIp);
                    })
                    // Streamer le body sans le buffer en mémoire (crucial pour les fichiers volumineux)
                    .body(request.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class),
                          org.springframework.core.io.buffer.DataBuffer.class)
                    .exchangeToMono(response -> {
                        int statusCode = response.statusCode().value();

                        // Log des erreurs pour le monitoring avec détails par code
                        if (response.statusCode().isError()) {
                            // Les libelles ne DIAGNOSTIQUENT pas : ils nomment
                            // le statut, et rien de plus.
                            //
                            // « 401 -> Token invalide ou expire » etait une
                            // conclusion, pas une observation. Le Gateway ne
                            // sait pas pourquoi le service a repondu 401 : ici,
                            // c'etait un refus d'enrolement du second facteur,
                            // et le journal a envoye deux personnes chercher du
                            // cote des jetons. Un journal qui conclut a tort
                            // coute plus cher qu'un journal muet.
                            String logMessage = switch (statusCode) {
                                case 400 -> " Bad Request";
                                case 401 -> " Unauthorized (motif rendu par le service)";
                                case 403 -> " Forbidden (motif rendu par le service)";
                                case 404 -> " Not Found";
                                case 409 -> " Conflict";
                                case 500 -> " Internal Server Error";
                                case 503 -> " Service Unavailable";
                                default -> statusCode >= 500 ? " Server Error" : " Client Error";
                            };
                            log.warn("{} - {} {} - Status: {}",
                                logMessage, request.method(), request.uri(), statusCode);
                        }

                        // Construire la réponse en propageant le status et les headers
                        return response.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(responseBody -> {
                                if (!response.statusCode().isError()) {
                                    log.debug(" Success - {} {} - Status: {}",
                                        request.method(), request.uri(), statusCode);
                                }
                                return ServerResponse
                                    .status(response.statusCode())
                                    .headers(h -> h.addAll(response.headers().asHttpHeaders()))
                                    .contentType(response.headers().contentType().orElse(MediaType.APPLICATION_JSON))
                                    .bodyValue(responseBody.isEmpty() ? "{}" : responseBody);
                            });
                    })
                    .onErrorResume(error -> {
                        // Gestion des erreurs de connexion ou timeout
                        log.error(" Gateway unexpected error: {} - {} {}",
                            error.getMessage(), request.method(), request.uri(), error);
                        String errorJson = String.format(
                            "{\"timestamp\":\"%s\",\"status\":500,\"error\":\"Gateway Error\",\"message\":\"Erreur inattendue du Gateway: %s\",\"path\":\"%s\"}",
                            java.time.LocalDateTime.now(),
                            error.getMessage() != null ? error.getMessage().replace("\"", "\\\"") : "Erreur inconnue",
                            request.uri().getPath()
                        );
                        return ServerResponse.status(500)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(errorJson);
                    });
            })
            .switchIfEmpty(
                // Si aucune instance n'est trouvée
                ServerResponse.status(503)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"error\":\"Service " + serviceName + " unavailable\"}")
            );
    }

    /**

     * Récupère les claims JWT déjà VALIDÉS (signature RSA + issuer + audience) par
     * {@link com.abdatytch.gateway_service.security.JwtWebFilter}, publiés dans les
     * attributs d'échange.
     *
     * Remplace l'ancien décodage Base64 manuel du payload : celui-ci n'était pas
     * vérifié, donc n'importe quel token forgé pouvait renseigner X-Auth-User-Id.
     *
     * @return les claims validés, ou null si la requête n'est pas authentifiée
     */
    private Claims validatedClaims(org.springframework.web.reactive.function.server.ServerRequest request) {
        Object attribute = request.attributes().get(JwtWebFilter.VALIDATED_CLAIMS_ATTRIBUTE);
        return attribute instanceof Claims claims ? claims : null;
    }

}
