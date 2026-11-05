package com.abdatytch.notification_service.web;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.dto.response.NotificationResponse;
import com.abdatytch.notification_service.entities.Notification;
import com.abdatytch.notification_service.repositories.NotificationRepository;
import com.abdatytch.notification_service.service.impl.MoteurDeModeles;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * La boite de reception du porteur.
 *
 * <h2>Le destinataire vient du JETON, jamais de la requete</h2>
 *
 * Aucun endpoint ne prend d'identifiant de destinataire. Il n'y a donc aucun
 * moyen de demander la boite de reception de quelqu'un d'autre : ni par un
 * parametre, ni par un chemin, ni par un corps de requete.
 *
 * <p>C'est la seule facon sure de concevoir cette surface. Ces notifications
 * portent des codes de verification et des alertes de securite — un
 * {@code ?destinataireId=} les rendrait lisibles en changeant un identifiant.
 *
 * <h2>Un identifiant inconnu rend 404, pas 403</h2>
 *
 * {@code parIdEtDestinataire} ne rend rien dans deux cas qu'il ne distingue
 * pas : la notification n'existe pas, ou elle est a quelqu'un d'autre.
 * Repondre 403 sur le second permettrait d'enumerer les notifications des
 * autres en lisant le seul code de statut. Regle constante du depot.
 */
@RestController
@RequestMapping("/notifications")
@Tag(name = "Notifications", description = "Boite de reception du porteur")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    /** Borne dure de pagination. */
    private static final int TAILLE_MAX = 100;
    private static final int TAILLE_DEFAUT = 20;

    private final NotificationRepository depot;
    private final MoteurDeModeles moteur;
    private final ObjectMapper json;

    public NotificationController(NotificationRepository depot, MoteurDeModeles moteur,
                                  ObjectMapper json) {
        this.depot = depot;
        this.moteur = moteur;
        this.json = json;
    }

    @GetMapping
    @Operation(summary = "Lister mes notifications",
            description = "Les plus recentes d'abord. Filtre optionnel sur les non lues.")
    public Flux<NotificationResponse> lister(
            @RequestParam(defaultValue = "false") boolean nonLues,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + TAILLE_DEFAUT) int taille,
            @AuthenticationPrincipal Jwt jwt) {

        String porteur = porteurDe(jwt);
        int bornee = borner(taille);
        int decalage = Math.max(0, page) * bornee;

        Flux<Notification> lues = nonLues
                ? depot.nonLuesParDestinataire(porteur, bornee, decalage)
                : depot.parDestinataire(porteur, bornee, decalage);

        return lues.map(this::rendre);
    }

    @GetMapping("/compteur")
    @Operation(summary = "Nombre de notifications non lues")
    public Mono<Map<String, Long>> compter(@AuthenticationPrincipal Jwt jwt) {
        return depot.compterNonLues(porteurDe(jwt))
                // defaultIfEmpty sur le COMPTE, pas sur la reponse : un COUNT
                // rend toujours une ligne, mais une reponse vide de la base
                // laisserait le Mono vide et le client attendrait un corps qui
                // ne vient pas.
                .defaultIfEmpty(0L)
                .map(nombre -> Map.of("nonLues", nombre));
    }

    @PostMapping("/{id}/lue")
    @Operation(summary = "Marquer une notification comme lue")
    public Mono<NotificationResponse> marquerLue(@PathVariable UUID id,
                                                 @AuthenticationPrincipal Jwt jwt) {
        String porteur = porteurDe(jwt);
        // Les DEUX identifiants dans la requete : c'est ce qui empeche de
        // marquer lue la notification d'autrui.
        return depot.parIdEtDestinataire(id, porteur)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Notification introuvable")))
                .map(notification -> notification.dejaEnregistree().marquerLue())
                .flatMap(depot::save)
                .map(this::rendre);
    }

    /**
     * Le {@code sub} du porteur.
     *
     * <p>Un sujet absent signifie un jeton que cette plateforme n'a pas emis :
     * 401, et non 500. Sans ce controle, {@code null} descendrait jusqu'a la
     * requete SQL et rendrait une liste vide — indistinguable d'une boite de
     * reception vide.
     */
    private String porteurDe(Jwt jwt) {
        String sujet = jwt == null ? null : jwt.getSubject();
        if (sujet == null || sujet.isBlank()) {
            log.warn("Jeton sans sujet sur la boite de reception, acces refuse");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Jeton invalide");
        }
        return sujet;
    }

    /**
     * Borne la taille de page.
     *
     * <p>Sans borne, un {@code ?taille=1000000} ferait lire la table entiere en
     * memoire. La borne est appliquee en SILENCE plutot qu'en erreur : un client
     * qui demande trop recoit le maximum, ce qui est utilisable, la ou un 400
     * casserait son ecran.
     */
    private int borner(int demandee) {
        if (demandee < 1) {
            return TAILLE_DEFAUT;
        }
        return Math.min(demandee, TAILLE_MAX);
    }

    /**
     * Rend le contenu au moment de la lecture, et non celui de l'envoi.
     *
     * <p>La base conserve le gabarit et ses variables : le texte est donc rendu
     * ici. Un changement de formulation s'applique ainsi aux notifications deja
     * en boite, et la traduction reste possible.
     *
     * <p>Le canal {@code IN_APP} est utilise pour le rendu : c'est bien par ce
     * canal que le client la lit, meme si la notification a aussi ete remise
     * ailleurs.
     */
    private NotificationResponse rendre(Notification notification) {
        Map<String, String> variables = variablesDe(notification);
        var contenu = moteur.rendre(notification.getModele(),
                CanalDeNotification.IN_APP, variables);

        // Modele disparu depuis l'envoi : on rend la notification quand meme,
        // avec un corps vide. La faire disparaitre de la boite de reception
        // serait pire — l'utilisateur perdrait une information dont il a
        // peut-etre besoin, et sans aucune trace visible.
        return NotificationResponse.de(notification,
                contenu.map(MoteurDeModeles.Contenu::sujet).orElse(""),
                contenu.map(MoteurDeModeles.Contenu::corps).orElse(""));
    }

    private Map<String, String> variablesDe(Notification notification) {
        try {
            String brut = notification.getVariables();
            if (brut == null || brut.isBlank()) {
                return Map.of();
            }
            return json.readValue(brut, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception illisible) {
            // Une colonne JSON corrompue ne doit pas faire echouer toute la
            // liste : on rend la notification sans ses variables.
            log.warn("Variables illisibles pour une notification : {}",
                    illisible.getMessage());
            return Map.of();
        }
    }
}
