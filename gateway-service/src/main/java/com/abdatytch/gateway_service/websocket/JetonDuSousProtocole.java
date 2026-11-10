package com.abdatytch.gateway_service.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Permet a un NAVIGATEUR de s'abonner au flux temps reel.
 *
 * <h2>Le probleme, et pourquoi il n'etait pas resoluble autrement</h2>
 *
 * {@code GestionnaireWebSocket} de notification-service lit l'identite dans le
 * contexte de securite, donc la poignee de main doit porter un
 * {@code Authorization: Bearer}. Or l'API {@code WebSocket} des navigateurs
 * <b>ne permet pas de poser un en-tete</b> : son constructeur n'accepte qu'une
 * URL et une liste de SOUS-PROTOCOLES.
 *
 * <p>Les trois autres voies ont ete ecartees, et chacune pour une raison :
 *
 * <ul>
 *   <li><b>le jeton dans l'URL</b> — interdit dans ce depot. Une URL part dans
 *       les journaux d'acces, dans l'en-tete {@code Referer} et dans
 *       l'historique du navigateur. C'est la raison pour laquelle le code de
 *       remise a usage unique existe ailleurs ici ;</li>
 *   <li><b>un cookie</b> — cette plateforme est sans session, et introduire un
 *       cookie d'authentification rouvrirait la question du CSRF, qu'un jeton
 *       porteur evite par construction ;</li>
 *   <li><b>un premier message apres connexion</b> — cela ferait accepter une
 *       COMMANDE par WebSocket. {@code GestionnaireWebSocket} refuse
 *       deliberement les messages entrants : « accepter des commandes par
 *       WebSocket ouvrirait une seconde surface d'API, non couverte par les
 *       controles de la passerelle qui s'appliquent requete par requete ».</li>
 * </ul>
 *
 * <h2>Le sous-protocole, et pourquoi il convient</h2>
 *
 * Le client ouvre {@code new WebSocket(url, ["bearer", jeton])}. Le navigateur
 * emet alors {@code Sec-WebSocket-Protocol: bearer, <jeton>} — un EN-TETE, donc
 * ni dans l'URL, ni dans les journaux d'acces, ni dans le {@code Referer}, ni
 * dans l'historique.
 *
 * <p>Un jeton JWT est de surcroit un {@code token} valide au sens de la
 * RFC 6455 : il ne contient que {@code A-Za-z0-9-_.}, donc ni espace ni
 * virgule, et traverse la negociation sans encodage.
 *
 * <h2>CE FILTRE N'ACCORDE AUCUNE CONFIANCE</h2>
 *
 * Il ne valide rien. Il DEPLACE une valeur d'un en-tete vers un autre, et
 * {@code JwtWebFilter} la verifie ensuite comme pour n'importe quelle requete :
 * signature RSA, emetteur, audience, revocation. Un jeton invalide pose ici est
 * refuse exactement comme un jeton invalide pose dans {@code Authorization}.
 *
 * <h2>Quatre conditions, et chacune ferme une porte</h2>
 *
 * <ul>
 *   <li>le chemin est EXACTEMENT celui du flux — pas un prefixe ;</li>
 *   <li>la requete est une montee en protocole ;</li>
 *   <li>{@code Authorization} est ABSENT — ce filtre ne peut donc pas remplacer
 *       un en-tete fourni, ni servir a en imposer un ailleurs ;</li>
 *   <li>le sous-protocole annonce commence par le marqueur {@code bearer}, suivi
 *       d'exactement une valeur.</li>
 * </ul>
 *
 * <h2>En PREMIER dans la chaine</h2>
 *
 * {@code HIGHEST_PRECEDENCE + 4}, avant la protection anti-bombe (+5), la
 * documentation (+8), la signature (+10) et la chaine de securite (+100). Toutes
 * les couches suivantes voient alors une requete authentifiee ordinaire, et
 * aucune n'a besoin de connaitre ce mecanisme.
 */
public class JetonDuSousProtocole implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JetonDuSousProtocole.class);

    /** Le marqueur que le client annonce avant son jeton. */
    static final String MARQUEUR = "bearer";

    private static final String EN_TETE = "Sec-WebSocket-Protocol";

    @Override
    public Mono<Void> filter(ServerWebExchange echange, WebFilterChain chaine) {
        ServerHttpRequest requete = echange.getRequest();

        if (!RelaisWebSocket.ROUTES.containsKey(requete.getPath().value())
                || !RelaisWebSocket.estUneMonteeEnProtocole(echange)
                || requete.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chaine.filter(echange);
        }

        String jeton = jetonAnnonce(requete.getHeaders().get(EN_TETE));
        if (jeton == null) {
            return chaine.filter(echange);
        }

        log.debug("Jeton lu dans le sous-protocole WebSocket pour {}",
                requete.getPath().value());

        // La valeur n'est PAS journalisee : c'est un porteur, et un porteur dans
        // un journal est un acces offert a qui lit le journal.
        return chaine.filter(echange.mutate()
                .request(requete.mutate()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jeton)
                        .build())
                .build());
    }

    /**
     * Le jeton annonce, ou {@code null}.
     *
     * <p>Deux valeurs exactement : le marqueur puis le jeton. Le navigateur
     * envoie {@code bearer, <jeton>} sur une seule ligne, mais un client peut
     * aussi emettre deux lignes {@code Sec-WebSocket-Protocol} ; les deux
     * formes sont acceptees parce que la norme HTTP les declare equivalentes.
     *
     * <p>Plus de deux valeurs est REFUSE plutot que devine : accepter
     * « la deuxieme valeur, quelle que soit la liste » laisserait un client
     * annoncer un sous-protocole reel suivi d'un jeton, et ce filtre
     * choisirait a sa place.
     */
    private static String jetonAnnonce(List<String> valeurs) {
        if (valeurs == null || valeurs.isEmpty()) {
            return null;
        }
        List<String> parties = valeurs.stream()
                .flatMap(ligne -> java.util.Arrays.stream(ligne.split(",")))
                .map(String::trim)
                .filter(partie -> !partie.isEmpty())
                .toList();

        if (parties.size() != 2
                || !MARQUEUR.equalsIgnoreCase(parties.get(0).toLowerCase(Locale.ROOT))) {
            return null;
        }
        return parties.get(1);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }
}
