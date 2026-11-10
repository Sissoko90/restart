package com.abdatytch.gateway_service.websocket;

import com.abdatytch.commons.security.JwtClaimsReader;
import com.abdatytch.gateway_service.routing.CheminCible;
import com.abdatytch.gateway_service.routing.ChoixDInstance;
import com.abdatytch.gateway_service.security.ClientIpResolver;
import com.abdatytch.gateway_service.security.JwtWebFilter;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Le relais du flux temps reel : la seule montee en protocole de la passerelle.
 *
 * <h2>Pourquoi ce composant existe</h2>
 *
 * {@code GatewayRoutesConfig} proxifie avec {@code WebClient}, qui ne sait pas
 * effectuer une montee en protocole : il n'y avait donc <b>aucune maniere</b>
 * d'ecrire la route du flux avec le mecanisme en place.
 *
 * <p>Consequence mesuree : {@code notification-service} porte
 * {@code GestionnaireWebSocket} sur {@code /notifications-flux}, enregistre par
 * {@code ConfigurationWebSocket}, et ce flux n'etait joignable par aucun chemin
 * de la passerelle. Un client mobile ne pouvait pas s'y abonner.
 *
 * <h2>Pourquoi un relais dedie, et non {@code spring-cloud-starter-gateway}</h2>
 *
 * Declarer la dependance aurait apporte son {@code WebsocketRoutingFilter}, donc
 * un travail deja ecrit. Mais elle aurait aussi apporte un SECOND mecanisme de
 * routage complet — {@code RoutePredicateHandlerMapping}, ses predicats, son
 * ordre face au {@code RouterFunctionMapping} — et ce depot vient de passer une
 * journee a defaire les consequences d'un routage ecrit a deux endroits : onze
 * routes YAML inertes, six prefixes joignables nulle part, et trois garde-fous
 * qui les comptaient comme reelles.
 *
 * <p>Ce relais fait une seule chose, pour un seul chemin, et le declare.
 *
 * <h2>CE QUI DOIT ETRE TRANSMIS, ET POURQUOI CHAQUE PIECE COMPTE</h2>
 *
 * Une poignee de main WebSocket est une requete HTTP ordinaire. Elle traverse
 * donc en aval {@code GatewayAccessWebFilter} comme tout le reste, et
 * {@code GestionnaireWebSocket} lit l'identite dans le contexte de securite de
 * notification-service. Le relais doit donc reproduire EXACTEMENT ce que fait
 * la proxification HTTP :
 *
 * <ul>
 *   <li>la <b>signature HMAC</b> posee par {@code GatewaySignatureFilter}. Elle
 *       est calculee sur le chemin REECRIT par {@link CheminCible} et sur la
 *       methode : ce relais n'a donc rien a recalculer, il recopie les en-tetes
 *       {@code X-Gateway-*} de l'echange, deja mutes par le filtre ;</li>
 *   <li>l'en-tete {@code Authorization}, sans lequel notification-service
 *       fermerait la session faute de porteur identifiable ;</li>
 *   <li>{@code X-Auth-User-Id} et {@code X-User-Role}, poses depuis les
 *       claims <b>VALIDES</b> et jamais recopies du client ;</li>
 *   <li>{@code X-Forwarded-For}, ecrase avec l'adresse resolue par
 *       {@link ClientIpResolver} — le client ne choisit pas l'adresse sur
 *       laquelle les decisions aval reposent.</li>
 * </ul>
 *
 * <p>En oublier une seule ouvre une porte ou casse le flux en silence. C'est la
 * raison pour laquelle ce composant n'a pas ete improvise : les quatre pieces
 * sont ici, et la liste des en-tetes RESERVES a la passerelle est la meme
 * constante que celle du chemin HTTP.
 *
 * <h2>Les en-tetes de la poignee de main sont RETIRES</h2>
 *
 * {@code Connection}, {@code Upgrade}, {@code Sec-WebSocket-Key},
 * {@code Sec-WebSocket-Version}, {@code Sec-WebSocket-Extensions}. Le client
 * sortant pose les SIENS : transmettre la clef du client amont ferait echouer
 * la negociation, les deux poignees de main etant independantes.
 *
 * <h2>Limite connue : un navigateur ne peut pas s'abonner</h2>
 *
 * L'API WebSocket des navigateurs <b>ne permet pas de poser un en-tete</b>, donc
 * pas d'{@code Authorization}. Ce relais sert les clients qui peuvent le faire
 * — les bibliotheques natives mobiles, OkHttp, Starscream, {@code dart:io}.
 *
 * <p>Ouvrir le flux a un navigateur demanderait un autre porteur de jeton :
 * parametre de requete (interdit ici, les jetons ne vivent pas dans une URL),
 * cookie (cette plateforme est sans session), ou premier message apres
 * connexion (ce qui ferait accepter une commande par WebSocket, surface que
 * {@code GestionnaireWebSocket} refuse deliberement). C'est une decision
 * distincte, et elle n'est pas prise ici.
 *
 * <h2>Pourquoi un {@code WebFilter} de la chaine EXTERNE, et en dernier</h2>
 *
 * Le relais a besoin de l'echange — pour les claims valides et les en-tetes
 * mutes — ce qu'un {@code WebSocketHandler} ne recoit pas. Il a aussi besoin de
 * s'executer APRES la chaine de securite, qui pose ces claims.
 *
 * <p>{@code WebFilterChainProxy} de Spring Security est lui-meme un
 * {@code WebFilter} de la chaine externe, place a
 * {@code HIGHEST_PRECEDENCE + 100}. Un ordre {@code LOWEST_PRECEDENCE} passe
 * donc apres l'authentification et l'autorisation.
 *
 * <p>Il n'est PAS ajoute par {@code addFilterAt} dans la chaine de securite :
 * WebFlux collecte tous les beans {@code WebFilter}, et un filtre present dans
 * les deux chaines s'execute <b>deux fois par requete</b>. Ce depot l'a paye sur
 * sept filtres — un plafond de debit valait la moitie de l'annonce.
 */
public class RelaisWebSocket implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RelaisWebSocket.class);

    /**
     * Les chemins relayes, et le service qui les sert.
     *
     * <h3>Lu par le garde-fou, et non devine</h3>
     *
     * {@code RoutageCoherentTest} importe cette constante pour verifier que
     * {@link CheminCible} connait ces prefixes. Il lit donc le CODE, pas une
     * expression reguliere sur le texte du fichier.
     *
     * <p>La distinction n'est pas de style : la version precedente de ce
     * garde-fou comptait les routes YAML de la passerelle en cherchant des
     * chaines ligne par ligne, et ne pouvait voir ni leur indentation fautive,
     * ni l'absence du moteur qui les aurait lues. Onze routes inertes passaient
     * pour reelles.
     */
    public static final Map<String, String> ROUTES =
            Map.of("/api/v1/notifications-flux", "notification-service");

    /**
     * Les en-tetes propres a la poignee de main amont.
     *
     * <p>Le client sortant pose les siens. Transmettre
     * {@code Sec-WebSocket-Key} ferait echouer la negociation aval, la reponse
     * {@code Sec-WebSocket-Accept} etant derivee de la clef du CLIENT qui
     * ouvre.
     */
    private static final Set<String> EN_TETES_DE_POIGNEE_DE_MAIN = Set.of(
            "connection", "upgrade", "host",
            "sec-websocket-key", "sec-websocket-version",
            "sec-websocket-extensions", "sec-websocket-accept",
            "sec-websocket-protocol");

    /**
     * Les en-tetes que SEULE la passerelle a le droit d'emettre.
     *
     * <p>Meme liste que celle du chemin HTTP, et pour la meme raison : ils sont
     * reecrits plus bas depuis les claims valides et l'adresse resolue, jamais
     * recopies depuis le client. Un client qui poserait
     * {@code X-Auth-User-Id} se ferait passer pour quelqu'un d'autre.
     */
    private static final Set<String> RESERVES_A_LA_PASSERELLE = Set.of(
            "x-auth-user-id", "x-user-role", "x-forwarded-for",
            "x-real-ip", "x-client-ip");

    private final WebSocketService service;
    private final WebSocketClient client;
    private final ChoixDInstance choixDInstance;
    private final ClientIpResolver resolveurDIp;

    public RelaisWebSocket(ChoixDInstance choixDInstance, ClientIpResolver resolveurDIp) {
        this(new HandshakeWebSocketService(), new ReactorNettyWebSocketClient(),
                choixDInstance, resolveurDIp);
    }

    /** Pour les tests : les deux pieces WebSocket sont fournies. */
    RelaisWebSocket(WebSocketService service, WebSocketClient client,
                    ChoixDInstance choixDInstance, ClientIpResolver resolveurDIp) {
        this.service = service;
        this.client = client;
        this.choixDInstance = choixDInstance;
        this.resolveurDIp = resolveurDIp;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange echange, WebFilterChain chaine) {
        String chemin = echange.getRequest().getPath().value();
        String nomDuService = ROUTES.get(chemin);

        if (nomDuService == null || !estUneMonteeEnProtocole(echange)) {
            return chaine.filter(echange);
        }

        return choixDInstance.pour(nomDuService)
                .flatMap(instance -> relayer(echange, instance, chemin))
                .onErrorResume(panne -> {
                    // 503 et non 500 : l'instance n'a pas ete trouvee, ce qui est
                    // une indisponibilite et non un defaut de la requete. Le
                    // meme statut que la proxification HTTP rend dans ce cas.
                    log.warn("Relais WebSocket impossible vers {} : {}",
                            nomDuService, panne.getClass().getSimpleName());
                    echange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    return echange.getResponse().setComplete();
                });
    }

    private Mono<Void> relayer(ServerWebExchange echange,
                               org.springframework.cloud.client.ServiceInstance instance,
                               String cheminPublic) {
        // Le chemin cible vient de CheminCible, la MEME source que
        // GatewaySignatureFilter : le service aval recalcule la signature sur le
        // chemin recu, donc une divergence d'un caractere refuserait la poignee
        // de main en 403, avec un message parlant de signature et non de chemin.
        String cheminAval = CheminCible.pour(cheminPublic);
        URI cible = URI.create("ws://" + instance.getHost() + ":" + instance.getPort()
                + cheminAval);

        HttpHeaders enTetes = enTetesARelayer(echange);

        log.debug("Relais WebSocket {} -> {}", cheminPublic, cible);

        return service.handleRequest(echange, amont ->
                client.execute(cible, enTetes, aval ->
                        // Les deux sens en parallele, et `retain` sur chacun :
                        // les trames sont a comptage de references, et `send`
                        // les relache. Sans `retain`, la trame serait liberee
                        // avant d'etre ecrite.
                        Mono.zip(
                                        aval.send(amont.receive()
                                                .doOnNext(WebSocketMessage::retain)),
                                        amont.send(aval.receive()
                                                .doOnNext(WebSocketMessage::retain)))
                                .then()));
    }

    /**
     * Les en-tetes transmis a la poignee de main sortante.
     *
     * <p>Tout ce que porte l'echange — donc la signature HMAC deja posee par
     * {@code GatewaySignatureFilter} et l'{@code Authorization} du client —
     * SAUF les en-tetes propres a la poignee de main amont et ceux que seule la
     * passerelle emet. Ces derniers sont ensuite reecrits depuis les claims
     * valides.
     */
    // Visibilite de PAQUET, pour que RelaisWebSocketTest l'exerce directement.
    //
    // C'est ici que vit le contrat de securite du relais : ce qui est transmis,
    // ce qui est refuse au client, et ce qui est reecrit depuis les claims
    // VALIDES. Le verifier a travers une vraie poignee de main demanderait un
    // serveur WebSocket d'essai et ne porterait plus sur la seule chose qui
    // peut ouvrir une porte.
    HttpHeaders enTetesARelayer(ServerWebExchange echange) {
        HttpHeaders relayes = new HttpHeaders();

        echange.getRequest().getHeaders().forEach((nom, valeurs) -> {
            String minuscule = nom.toLowerCase(Locale.ROOT);
            if (!EN_TETES_DE_POIGNEE_DE_MAIN.contains(minuscule)
                    && !RESERVES_A_LA_PASSERELLE.contains(minuscule)) {
                relayes.addAll(nom, valeurs);
            }
        });

        Claims claims = clamsValides(echange);
        if (claims != null) {
            if (claims.getSubject() != null) {
                relayes.set("X-Auth-User-Id", claims.getSubject());
            }
            String role = JwtClaimsReader.primaryRole(claims);
            if (role != null) {
                relayes.set("X-User-Role", role);
            }
        }

        // La passerelle est le seul point capable d'etablir cette adresse
        // honnetement : elle l'ecrase avec celle qu'elle a resolue.
        relayes.set("X-Forwarded-For", resolveurDIp.resolve(echange));
        return relayes;
    }

    /**
     * Les claims VALIDES, poses par {@code JwtWebFilter}.
     *
     * <p>Jamais un decodage du jeton ici : il ne serait pas verifie. C'est la
     * meme source que la proxification HTTP.
     */
    private static Claims clamsValides(ServerWebExchange echange) {
        Object attribut = echange.getAttributes().get(JwtWebFilter.VALIDATED_CLAIMS_ATTRIBUTE);
        return attribut instanceof Claims claims ? claims : null;
    }

    /**
     * Est-ce vraiment une montee en protocole ?
     *
     * <p>Le chemin seul ne suffit pas : un {@code GET} ordinaire sur ce chemin
     * doit continuer la chaine et finir en 404, et non etre remis a un service
     * de poignee de main qui repondrait par une erreur de negociation
     * incomprehensible.
     *
     * <p>Comparaison insensible a la casse : la specification HTTP ne contraint
     * pas la casse des valeurs de ces en-tetes, et {@code Upgrade: WebSocket}
     * est emis par de vrais clients.
     */
    static boolean estUneMonteeEnProtocole(ServerWebExchange echange) {
        HttpHeaders enTetes = echange.getRequest().getHeaders();
        String upgrade = enTetes.getFirst("Upgrade");
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim());
    }

    /**
     * En DERNIER dans la chaine externe.
     *
     * <p>Apres {@code WebFilterChainProxy} de Spring Security
     * ({@code HIGHEST_PRECEDENCE + 100}), qui pose les claims valides, et apres
     * {@code GatewaySignatureFilter} ({@code HIGHEST_PRECEDENCE + 10}), qui
     * pose la signature. Le relais a besoin des deux.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
