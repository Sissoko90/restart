package com.abdatytch.auth_service.filter;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.commons.security.CheminDeDecision;
import com.abdatytch.commons.security.CheminInterne;
import com.abdatytch.commons.security.CheminsPublics;
import com.abdatytch.commons.security.HMACSignatureUtil;
import com.abdatytch.commons.security.PrivateNetworks;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Garantit qu'aucune requête n'atteint auth-service sans être passée par le
 * Gateway.
 *
 * <p>Toute l'architecture repose sur cet invariant : liste noire d'adresses,
 * limitation de débit, blocage géographique et détection d'injection vivent au
 * Gateway, et ne valent donc que si personne ne peut appeler le service en
 * direct. Ce filtre est ce qui rend l'invariant vrai, et il est le seul.
 *
 * <p>Deux contrôles, dans cet ordre : l'adresse du pair TCP doit être sur un
 * réseau privé, puis la signature HMAC du Gateway doit être valide sur
 * {@code horodatage|chemin|méthode|identifiant}, dans une fenêtre anti-rejeu.
 *
 * <h2>Ni {@code @Component}, ni {@code @Order}</h2>
 *
 * WebFlux place dans la chaîne HTTP <b>tout</b> bean de type {@code WebFilter},
 * et Spring Security en est un lui-même. Ce filtre, annoté {@code @Component}
 * <b>et</b> ajouté par {@code addFilterAt}, s'exécutait donc <b>deux fois par
 * requête</b> : deux validations HMAC, et chaque refus journalisé en double — ce
 * qui fausse toute alerte fondée sur un décompte de tentatives d'accès direct.
 *
 * <p>C'est le même défaut que celui corrigé sur les sept filtres du Gateway, où
 * il divisait par deux le plafond réel de la limitation de débit.
 * {@code FilterWiringTest} vérifie désormais la règle dans ce service aussi.
 */
@Slf4j
public class GatewayAccessWebFilter implements WebFilter {

    private static final Set<String> ALLOWED_IPS = Set.of(
        "127.0.0.1",
        "localhost",
        "0:0:0:0:0:0:0:1",
        "::1",
        "gateway-service");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HMACSignatureUtil hmacSignatureUtil;

    /**
     * Le secret arrive par le constructeur, et non plus par {@code @Value}.
     *
     * <p>Ce filtre n'etant plus un bean, aucun post-traitement Spring ne
     * renseignerait un champ annote : il serait reste {@code null}, et
     * {@code validateSignature} aurait alors leve ou — pire — compare contre une
     * cle vide. {@code SecurityConfig} lit la propriete et la transmet ici.
     */
    private final String secretKey;

    public GatewayAccessWebFilter(HMACSignatureUtil hmacSignatureUtil, String secretKey) {
        this.hmacSignatureUtil = hmacSignatureUtil;
        this.secretKey = secretKey;
    }

    /**
     * Les chemins exemptes du controle d'acces.
     *
     * <p>La regle vit desormais dans {@code CheminsPublics} de
     * platform-commons. Les cinq services en tenaient chacun une copie, et
     * <b>trois defauts de la meme famille</b> en sont sortis — un controle
     * qui designe plus large que son intention. Les trois sont maintenant
     * des cas de test nommes dans {@code CheminsPublicsTest}, et le
     * constructeur <b>refuse</b> ce qui ressemble a un motif : ce qui
     * n'etait qu'un avertissement en commentaire est devenu une erreur de
     * demarrage.
     *
     * <p>La documentation OpenAPI suit la <b>troisieme categorie</b> :
     * signature de passerelle <b>exigee</b>, jeton non. Elle n'est donc pas
     * lisible en attaquant le port du service en direct, et elle est servie a
     * une requete que la passerelle a signee. C'est la passerelle qui agrege
     * les documents et decide qui peut les lire — une surface a proteger au
     * lieu de sept.
     */
    private static final CheminsPublics CHEMINS_PUBLICS = CheminsPublics.sondes()
            .pageDErreur()
            // `/.well-known` a ete RETIRE.
            //
            // Le commentaire d'origine disait « JWKS endpoint pour les autres
            // services ». Verifie : auth-service ne sert AUCUN chemin
            // `/.well-known`. Le seul detenteur du JWKS est
            // interface-service.DevKeyManagementController, l'autorite JWT de
            // la plateforme. auth-service ne fait que le CONSOMMER, par
            // InterfaceServiceClient.
            //
            // L'exemption n'ouvrait donc rien — et c'est precisement pourquoi
            // elle devait partir : le jour ou quelqu'un ajoute une route
            // `/.well-known` ici, elle naitrait sans signature ni jeton, en
            // toute bonne foi puisque la regle semblait prevue pour elle. Meme
            // raisonnement que le retrait de `/api/v1/internal/**` dans
            // media-service.
            //
            // Elle etait de surcroit incoherente : la chaine Security de ce
            // service ne la mettait pas en permitAll, donc un appelant sans
            // jeton franchissait le filtre pour se faire refuser juste apres.
            .documentationViaPasserelle()
            .construire();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // Le chemin BRUT, et non `getURI().getPath()` qui le DECODE.
        //
        // La signature HMAC porte sur le chemin, et le Gateway signe la forme
        // brute — telle qu'elle circule sur le reseau. Lire ici la forme
        // decodee fait comparer deux chaines differentes des que le chemin
        // contient un echappement pourcent : la signature ne peut pas
        // correspondre, et le refus est un 403 qui parle de signature invalide,
        // jamais d'encodage.
        //
        // Mesure faite sur l'environnement de developpement :
        //   /admin/accounts/+22399999999/lockout-status     -> 200
        //   /admin/accounts/%2B22399999999/lockout-status   -> 403
        // alors que %2B est l'encodage CORRECT d'un + dans un chemin, et celui
        // que produit tout client HTTP correct — Postman compris. Tous les
        // endpoints prenant un numero de telephone en variable de chemin
        // etaient donc inatteignables depuis un client normal.
        //
        // interface-service, media-service et user-service lisent deja la forme
        // brute. auth-service, quotas-service et setting-service lisaient la
        // forme decodee.
        String requestURI = request.getPath().value();
        // Le chemin de DECISION, decode, distinct du chemin SIGNE.
        //
        // La signature HMAC porte sur le chemin BRUT : la passerelle signe
        // exactement ce que le service recoit, et y substituer le chemin decode
        // ferait echouer TOUTES les verifications, avec un 403 parlant de
        // signature invalide et jamais de chemin.
        //
        // Mais la question « ce chemin est-il public » doit, elle, se decider
        // sur le chemin decode : les `pathMatchers` de SecurityConfig comparent
        // des segments decodes, et une exemption qui ne voit pas la meme
        // requete qu'eux est une exemption qui ne protege pas ce qu'elle croit.
        String cheminDeDecision = CheminDeDecision.de(request);

        // Autoriser les endpoints actuator (health, metrics), pages d'erreur, Swagger/OpenAPI et JWKS
        // Les trois sondes sont nommees, et NON /actuator/ en bloc.
        //
        // `contains("/actuator/")` exemptait tout le sous-arbre : env,
        // httptrace et loggers passaient sans signature ni jeton. Et
        // `contains` et non `startsWith` : n'importe quel chemin contenant
        // la chaine suffisait, donc `/auth/x/actuator/y` aussi.
        if (CHEMINS_PUBLICS.contient(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // 1. Vérifier l'IP source (protection contre accès direct)
        String clientIp = getClientIp(request);
        if (!isAllowedIp(clientIp)) {
            log.warn(" Direct access blocked from IP: {} to {}", clientIp, requestURI);
            return sendForbiddenResponse(exchange, requestURI,
                "Direct access not allowed. Requests must go through Gateway.");
        }

        // 2. Appel inter-services : la signature de passerelle est levee.
        //
        // APRES le controle d'adresse ci-dessus, et l'ordre compte : un chemin
        // /internal reste reserve au reseau prive.
        //
        // Cette exemption MANQUAIT, et le defaut etait VIVANT :
        // user-service/ClientIdentiteAuth appelle /internal/auth/users/{id} et
        // recevait 403 « Missing required security headers ». Son test passe
        // pourtant, parce qu'il parle a un serveur simule — la seule facon de
        // voir le probleme etait de comparer les filtres entre eux.
        //
        // Ce qui protege ce chemin a la place : l'adresse privee verifiee
        // ci-dessus, et X-Internal-Secret compare a temps constant DANS
        // InternalAuthController.
        if (CheminInterne.correspond(cheminDeDecision)) {
            return chain.filter(exchange);
        }

        // 3. Vérifier la signature HMAC (OBLIGATOIRE)
        String timestamp = request.getHeaders().getFirst("X-Gateway-Timestamp");
        String signature = request.getHeaders().getFirst("X-Gateway-Signature");
        String requestId = request.getHeaders().getFirst("X-Gateway-Request-Id");

        // Vérifier que tous les headers HMAC sont présents
        if (timestamp == null || signature == null || requestId == null) {
            log.warn(" Missing HMAC headers from IP: {} to {}", clientIp, requestURI);
            return sendForbiddenResponse(exchange, requestURI,
                "Missing required security headers. Requests must go through Gateway.");
        }

        // Valider timestamp (anti-replay)
        if (!hmacSignatureUtil.isTimestampValid(timestamp)) {
            log.warn(" Expired or invalid timestamp from IP: {}", clientIp);
            return sendForbiddenResponse(exchange, requestURI, "Request timestamp expired");
        }

        // Valider signature HMAC
        String signatureData = hmacSignatureUtil.buildSignatureData(
            timestamp, requestURI, request.getMethod().name(), requestId
        );

        if (!hmacSignatureUtil.validateSignature(secretKey, signatureData, signature)) {
            log.warn(" Invalid HMAC signature from IP: {}", clientIp);
            return sendForbiddenResponse(exchange, requestURI, "Invalid request signature");
        }

        log.debug(" HMAC signature validated for {} {}", request.getMethod(), requestURI);
        log.debug(AppConstants.RQ_AUTHORIZED, requestURI);

        // Requête autorisée - continuer la chaîne
        return chain.filter(exchange);
    }

    /**
     * Refus 403 en JSON, identique quelle qu'en soit la cause.
     *
     * <h3>Pourquoi le motif n'est plus dans le corps</h3>
     *
     * Le corps portait {@code "reason"} avec le motif exact : « Missing required
     * security headers », « Request timestamp expired », « Invalid request
     * signature ». Un appelant apprenait ainsi lequel des contrôles il venait de
     * franchir — en particulier, un horodatage expiré lui confirmait que sa
     * signature et son adresse, elles, étaient acceptées.
     *
     * <p>Le motif est passé en paramètre pour la journalisation seule. C'est la
     * politique déjà appliquée au filtre JWT du Gateway et au point d'entrée 401
     * de ce service : le détail reste dans les journaux.
     */
    private Mono<Void> sendForbiddenResponse(ServerWebExchange exchange, String requestURI, String reason) {
        log.debug("Refus d'accès sur {} : {}", requestURI, reason);

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now().toString());
        errorResponse.put("status", HttpStatus.FORBIDDEN.value());
        errorResponse.put("error", HttpStatus.FORBIDDEN.getReasonPhrase());
        errorResponse.put("message", AppConstants.KEY_REQUIRED);
        errorResponse.put("path", requestURI);
        errorResponse.put("details", AppConstants.X_SECRET_KEY_REQUIRED);

        try {
            byte[] bytes = objectMapper.writeValueAsBytes(errorResponse);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            log.error("Error writing forbidden response", e);
            return response.setComplete();
        }
    }

    /**

     * Adresse du pair TCP, et rien d'autre.
     *
     * <h3>Pourquoi les en-tetes ne sont pas lus</h3>
     *
     * Cette methode lisait {@code X-Forwarded-For} puis {@code X-Real-IP} avant
     * de retomber sur l'adresse reelle. Or ces en-tetes sont fournis par
     * l'appelant : il suffisait d'envoyer {@code X-Forwarded-For: 127.0.0.1} pour
     * franchir le controle d'IP et faire passer un appel direct pour un appel du
     * Gateway.
     *
     * <p>Le controle reposait alors entierement sur la signature HMAC. C'est
     * solide — elle couvre le chemin et la methode, donc un rejeu sur un autre
     * endpoint echoue — mais le secret partage a ete committe dans le depot
     * (commit {@code 71af837}) et doit etre considere comme connu. Le controle
     * d'IP etait le dernier rempart, et il ne tenait pas.
     *
     * <p>Autre perte, moins visible : le journal « Direct access blocked from IP »
     * ne se declenchait pas pour un appelant qui usurpait l'en-tete. On perdait
     * donc aussi le signal de detection.
     *
     * <p>Ici, aucun proxy ne s'interpose : le Gateway appelle le service
     * directement. L'adresse du pair TCP est donc la seule information fiable, et
     * la seule necessaire. Les trois autres services (media, quotas, setting)
     * faisaient deja ainsi.
     */
    private String getClientIp(ServerHttpRequest request) {
        if (request.getRemoteAddress() != null
                && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    /**
     * L'appelant est-il sur un reseau d'ou le Gateway peut legitimement appeler ?
     *
     * <h3>Le defaut corrige : {@code 172.} n'est pas une plage privee</h3>
     *
     * Le test {@code ip.startsWith("172.")} autorisait <b>172.0.0.0/8</b>, soit
     * 16,7 millions d'adresses. Or la plage privee est 172.<b>16</b>.0.0/12 :
     * elle s'arrete a 172.31. Tout ce qui est en dehors — 172.0 a 172.15 et
     * 172.32 a 172.255 — est de l'<b>espace public routable</b>. 172.217.x.x
     * appartient a Google.
     *
     * <p>La portee de l'erreur se mesure a ce que ce controle protege : la
     * javadoc de {@code getClientIp} explique que le secret HMAC a ete committe
     * (commit {@code 71af837}) et doit etre considere comme connu, et que le
     * controle d'IP est par consequent le <b>dernier rempart</b> contre un appel
     * direct au service. Ce rempart acceptait 93 % d'une plage publique.
     *
     * <p>La verification porte desormais sur le second octet, et non sur un
     * prefixe de chaine.
     */
    private boolean isAllowedIp(String ip) {
        if (ALLOWED_IPS.contains(ip)) {
            return true;
        }
        // La notion de reseau prive vit dans platform-commons.
        //
        // Elle etait implementee ici, et differemment dans interface-service,
        // avec deux erreurs OPPOSEES : ce service acceptait 172.0.0.0/8 (93 %
        // d'espace public), l'autre n'acceptait qu'une adresse de machine ecrite
        // en dur. Deux copies, deux defauts contraires — le motif que
        // JwtClaimsReader a deja corrige pour la lecture des roles.
        return PrivateNetworks.contient(ip);
    }

}
