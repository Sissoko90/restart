package com.abdatytch.notification_service.web;

import com.abdatytch.notification_service.dto.request.EnregistrementDAppareilRequest;
import com.abdatytch.notification_service.dto.response.AppareilResponse;
import com.abdatytch.notification_service.entities.JetonDAppareil;
import com.abdatytch.notification_service.repositories.JetonDAppareilRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Les appareils du porteur, pour les notifications push.
 *
 * <h2>Ce que le client mobile doit faire, et quand</h2>
 *
 * <ol>
 *   <li>a chaque DEMARRAGE de l'application, apres connexion :
 *       {@code POST /api/v1/notifications/appareils} avec le jeton rendu par
 *       le SDK FCM. A chaque demarrage et non une seule fois — le systeme
 *       renouvelle le jeton sans avertir, et cet appel rafraichit aussi la date
 *       de derniere vue, qui protege l'appareil de la purge ;</li>
 *   <li>quand le SDK signale un jeton renouvele : le meme appel avec le
 *       nouveau jeton ;</li>
 *   <li>a la DECONNEXION : {@code DELETE /api/v1/notifications/appareils} avec
 *       le jeton. Sans cela, l'appareil continuerait de recevoir les
 *       notifications d'un compte dont il est sorti.</li>
 * </ol>
 *
 * <h2>Le destinataire vient du JETON, jamais de la requete</h2>
 *
 * Aucun endpoint ne prend d'identifiant de destinataire. Il n'y a donc aucun
 * moyen d'enregistrer son propre appareil au nom de quelqu'un d'autre — ce qui
 * reviendrait a faire suivre ses codes de verification et ses alertes de
 * securite. Meme regle que la boite de reception, et pour la meme raison.
 *
 * <h2>Pourquoi ces chemins sous {@code /notifications}</h2>
 *
 * {@code /api/v1/notifications/**} est deja route vers ce service et deja
 * declare dans {@code CheminCible}, donc deja couvert par le mode maintenance.
 * Un prefixe neuf aurait demande les deux, et ce depot a plusieurs fois livre
 * des surfaces qu'aucune route n'atteignait.
 *
 * <p>Aucune collision avec {@code NotificationController} : ses chemins sont
 * {@code /notifications}, {@code /notifications/compteur} et
 * {@code /notifications/{id}/lue}.
 */
@RestController
@RequestMapping("/notifications/appareils")
@Tag(name = "Appareils", description = "Enregistrement des appareils pour le push")
public class AppareilController {

    private static final Logger log = LoggerFactory.getLogger(AppareilController.class);

    private final JetonDAppareilRepository appareils;

    public AppareilController(JetonDAppareilRepository appareils) {
        this.appareils = appareils;
    }

    /**
     * Declare ou rafraichit un appareil.
     *
     * <h3>Idempotent, et il DEPLACE le jeton si besoin</h3>
     *
     * Rappeler cet endpoint avec le meme jeton ne cree pas de doublon : la
     * contrainte d'unicite porte sur le jeton, et l'ecriture est un
     * {@code ON CONFLICT DO UPDATE}.
     *
     * <p>Et si le jeton appartenait a quelqu'un d'autre — cas reel d'un
     * telephone sur lequel une seconde personne se connecte, FCM attribuant le
     * meme jeton — il change de proprietaire. Sans cela, l'appareil
     * continuerait de recevoir les notifications du PREMIER compte.
     *
     * <p>{@code POST /api/v1/notifications/appareils}
     */
    @PostMapping
    @Operation(summary = "Déclarer un appareil",
            description = "À appeler à chaque démarrage de l'application. Idempotent.")
    public Mono<AppareilResponse> declarer(
            @Valid @RequestBody EnregistrementDAppareilRequest demande,
            @AuthenticationPrincipal Jwt jwt) {

        String porteur = jwt.getSubject();

        // Ni le jeton ni son abrege ne sont journalises : c'est une capacite
        // d'affichage sur un appareil precis, et ce journal part vers Loki. La
        // plateforme suffit au diagnostic.
        log.info("Appareil {} declare pour un porteur", demande.plateforme());

        return appareils.enregistrer(UUID.randomUUID(), porteur, demande.jeton(),
                        demande.plateforme().name())
                // La reponse est relue depuis la BASE, et non construite depuis
                // la demande : c'est ce qui prouve a l'appelant que son
                // enregistrement a bien pris, plutot que de lui renvoyer ce
                // qu'il vient d'envoyer.
                .thenMany(appareils.parDestinataire(porteur))
                .filter(appareil -> demande.jeton().equals(appareil.getJeton()))
                .next()
                .map(AppareilResponse::de)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "L'appareil n'a pas pu etre relu apres son enregistrement.")));
    }

    /**
     * Mes appareils.
     *
     * <p>Les jetons sont TRONQUES — voir {@code AppareilResponse}. Le
     * proprietaire a besoin de reconnaitre ses appareils, pas de recopier une
     * capacite d'envoi.
     *
     * <p>{@code GET /api/v1/notifications/appareils}
     */
    @GetMapping
    @Operation(summary = "Lister mes appareils",
            description = "Jetons tronqués : ils ne servent pas au client.")
    public Flux<AppareilResponse> lister(@AuthenticationPrincipal Jwt jwt) {
        return appareils.parDestinataire(jwt.getSubject())
                .map(AppareilResponse::de);
    }

    /**
     * Retire UN appareil. A appeler a la deconnexion.
     *
     * <h3>Le parametre est OBLIGATOIRE, et c'est delibere</h3>
     *
     * La forme REST habituelle ferait de {@code DELETE} sur la collection un
     * « retire tout ». Ici, un client qui oublierait le parametre
     * desinscrirait TOUS les appareils de la personne — y compris ceux depuis
     * lesquels elle n'a rien demande, et sans qu'elle le sache.
     *
     * <p>Sans parametre, cet endpoint rend donc 400, et « tout retirer » a son
     * propre chemin, explicite.
     *
     * <h3>Un jeton inconnu rend 404, pas 403</h3>
     *
     * La requete ne distingue pas « ce jeton n'existe pas » de « ce jeton
     * appartient a quelqu'un d'autre » : le filtre sur le destinataire est dans
     * la requete SQL. Repondre differemment permettrait de tester l'existence
     * du jeton d'autrui. Regle constante du depot.
     *
     * <p>{@code DELETE /api/v1/notifications/appareils?jeton=...}
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer un appareil",
            description = "À appeler à la déconnexion. 404 si le jeton n'est pas le vôtre.")
    public Mono<Void> retirer(@RequestParam String jeton,
                              @AuthenticationPrincipal Jwt jwt) {
        return appareils.retirerPour(jwt.getSubject(), jeton)
                .flatMap(retires -> retires != null && retires > 0
                        ? Mono.empty()
                        : Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                                "Aucun appareil de ce porteur ne porte ce jeton.")))
                .then();
    }

    /**
     * Retire TOUS mes appareils. Deconnexion de partout.
     *
     * <h3>Deux usages, et le second compte autant que le premier</h3>
     *
     * Pour la personne : couper les notifications sur un telephone perdu, sans
     * acces a ce telephone.
     *
     * <p>Pour la plateforme : c'est la brique qu'un effacement RGPD devra
     * appeler. Un jeton d'appareil est une donnee personnelle — il identifie un
     * telephone — et ce service <b>n'ecoute pas encore</b> l'evenement
     * d'effacement, alors que sa migration V1 annonce que les variables de
     * notification sont separees pour pouvoir etre videes.
     *
     * <p>La capacite existe donc, et elle est exercee par un endpoint reel
     * plutot que d'attendre en code mort — ce depot a assez de travail ecrit et
     * jamais atteint.
     *
     * <p>{@code DELETE /api/v1/notifications/appareils/tous}
     */
    @DeleteMapping("/tous")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Retirer tous mes appareils",
            description = "Déconnexion de partout. Toujours 204, même sans appareil.")
    public Mono<Void> retirerTous(@AuthenticationPrincipal Jwt jwt) {
        return appareils.retirerTousPour(jwt.getSubject())
                .doOnNext(retires -> log.info("{} appareil(s) retire(s) a la demande "
                        + "du porteur", retires))
                // 204 meme sans appareil : la personne a demande qu'il n'en
                // reste aucun, et c'est le cas. Un 404 lui ferait croire a un
                // echec.
                .then();
    }
}
