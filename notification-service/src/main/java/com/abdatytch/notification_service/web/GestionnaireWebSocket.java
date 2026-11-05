package com.abdatytch.notification_service.web;

import com.abdatytch.notification_service.service.RegistreDesSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * Le flux temps reel : une session par appareil connecte.
 *
 * <h2>L'identite vient du JETON, jamais de l'URL</h2>
 *
 * C'est la decision de securite de cette classe. Un {@code destinataireId} pris
 * dans le chemin ou dans un parametre de requete laisserait n'importe qui
 * s'abonner au flux de n'importe qui : il suffirait de changer un identifiant
 * dans l'URL pour recevoir les notifications d'autrui — codes de verification
 * compris.
 *
 * <p>L'identifiant est donc lu dans le contexte de securite, pose par la chaine
 * WebFlux apres validation du jeton. Une poignee de main sans jeton valide
 * n'arrive jamais jusqu'ici.
 *
 * <p>Note : la poignee de main est une requete HTTP ordinaire, donc elle
 * traverse aussi {@code GatewayAccessWebFilter} — la signature de passerelle
 * est exigee comme pour tout le reste.
 *
 * <h2>Pourquoi le flux entrant est consomme et IGNORE</h2>
 *
 * Ce canal est a sens unique : le serveur pousse, le client ecoute. Mais le flux
 * entrant doit tout de meme etre abonne, sinon la session ne detecte pas la
 * fermeture par le client et resterait enregistree indefiniment — une fuite,
 * flux par flux.
 *
 * <p>Les messages recus sont volontairement ignores : accepter des commandes par
 * WebSocket ouvrirait une seconde surface d'API, non couverte par les controles
 * de la passerelle qui s'appliquent requete par requete.
 */
@Component
public class GestionnaireWebSocket implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GestionnaireWebSocket.class);

    private final RegistreDesSessions registre;

    public GestionnaireWebSocket(RegistreDesSessions registre) {
        this.registre = registre;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        return porteur()
                .switchIfEmpty(Mono.defer(() -> {
                    // Ne devrait pas arriver : la chaine de securite exige un
                    // porteur authentifie. On ferme plutot que d'enregistrer un
                    // flux sans destinataire connu, qui ne recevrait rien et
                    // occuperait une entree.
                    log.warn("Poignee de main WebSocket sans porteur identifiable");
                    return Mono.empty();
                }))
                .flatMap(destinataireId -> servir(session, destinataireId))
                .then(session.close())
                .then();
    }

    private Mono<Void> servir(WebSocketSession session, String destinataireId) {
        var flux = registre.ouvrir(destinataireId, session.getId());

        // send() n'est abonne qu'UNE fois, ici. C'est la raison d'etre du
        // registre : tout envoi ulterieur passe par le flux, pas par la session.
        Mono<Void> sortant = session.send(flux.asFlux().map(session::textMessage));

        // Le flux entrant est abonne pour detecter la fermeture, et son contenu
        // est ignore : ce canal est a sens unique.
        Mono<Void> entrant = session.receive().then();

        // Le premier des deux qui se termine termine la session. doFinally
        // garantit le retrait du flux quelle que soit la cause — fermeture
        // propre, coupure reseau, erreur — sans quoi le registre fuirait.
        return Mono.firstWithSignal(sortant, entrant)
                .doFinally(fin -> {
                    registre.fermer(destinataireId, session.getId());
                    log.debug("Flux WebSocket ferme ({})", fin);
                });
    }

    /** Le {@code sub} du porteur, depuis le contexte de securite. */
    private Mono<String> porteur() {
        return ReactiveSecurityContextHolder.getContext()
                .map(contexte -> contexte.getAuthentication())
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(authentification -> (Jwt) authentification.getPrincipal())
                .mapNotNull(Jwt::getSubject)
                .filter(sujet -> !sujet.isBlank());
    }
}
