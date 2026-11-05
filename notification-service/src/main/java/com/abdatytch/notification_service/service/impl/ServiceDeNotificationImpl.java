package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.CompteRenduDeRemise;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.notification_service.entities.Notification;
import com.abdatytch.notification_service.entities.RemiseParCanal;
import com.abdatytch.notification_service.metrics.NotificationMetrics;
import com.abdatytch.notification_service.repositories.NotificationRepository;
import com.abdatytch.notification_service.repositories.RemiseParCanalRepository;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.IServiceDeNotification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestration : enregistrer, rendre, remettre, rendre compte.
 *
 * <h2>L'ordre des etapes porte la robustesse</h2>
 *
 * La notification est enregistree <b>avant</b> toute tentative de remise. Deux
 * consequences voulues :
 *
 * <ul>
 *   <li>la boite de reception contient la notification meme si tous les canaux
 *       echouent — rien n'est jamais perdu ;</li>
 *   <li>son identifiant existe, donc les canaux WebSocket et push peuvent le
 *       porter, et le client sait quoi marquer lu.</li>
 * </ul>
 *
 * <h2>La politique de repli depend de l'urgence</h2>
 *
 * <ul>
 *   <li>{@code INFORMATION} : un seul canal essaye. Une information de confort
 *       ne justifie pas de reveiller un telephone parce qu'un onglet etait
 *       ferme.</li>
 *   <li>{@code NORMALE} : on s'arrete au premier canal qui aboutit.</li>
 *   <li>{@code CRITIQUE} : TOUS les canaux sont essayes, meme apres un succes.
 *       Une alerte de securite doit laisser une trace que l'attaquant ne peut
 *       pas effacer en fermant un onglet, donc le courriel part meme si le
 *       WebSocket a abouti.</li>
 * </ul>
 *
 * <h2>Les canaux sont essayes en SEQUENCE</h2>
 *
 * {@code concatMap} et non {@code flatMap} : l'ordre des canaux exprime une
 * priorite, et le repli « s'arreter au premier succes » n'a de sens que si les
 * tentatives sont ordonnees. En parallele, les quatre partiraient avant qu'on
 * sache si le premier a suffi.
 */
@Service
public class ServiceDeNotificationImpl implements IServiceDeNotification {

    private static final Logger log = LoggerFactory.getLogger(ServiceDeNotificationImpl.class);

    private final NotificationRepository notifications;
    private final RemiseParCanalRepository remises;
    private final MoteurDeModeles moteur;
    private final ResolveurDeCoordonnees resolveur;
    private final NotificationMetrics metriques;
    private final ObjectMapper json;

    /**
     * Les canaux, indexes par leur enum.
     *
     * <p>Spring injecte TOUTES les implementations : ajouter un canal est
     * ajouter une classe, sans {@code switch} a mettre a jour. Un
     * {@code switch} oublie n'echoue pas, il ignore le canal — exactement le
     * genre de defaut silencieux que ce depot traque.
     */
    private final Map<CanalDeNotification, CanalDeRemise> canaux =
            new EnumMap<>(CanalDeNotification.class);

    public ServiceDeNotificationImpl(NotificationRepository notifications,
                                     RemiseParCanalRepository remises,
                                     MoteurDeModeles moteur,
                                     ResolveurDeCoordonnees resolveur,
                                     NotificationMetrics metriques,
                                     ObjectMapper json,
                                     List<CanalDeRemise> canauxDisponibles) {
        this.notifications = notifications;
        this.remises = remises;
        this.moteur = moteur;
        this.resolveur = resolveur;
        this.metriques = metriques;
        this.json = json;
        canauxDisponibles.forEach(c -> this.canaux.put(c.canal(), c));
        log.info("Canaux de remise enregistres : {}", this.canaux.keySet());
    }

    @Override
    public Mono<CompteRenduDeRemise> traiter(DemandeDeNotification demande) {
        metriques.demandeRecue(demande.serviceEmetteur());
        var chrono = metriques.demarrerChrono();

        return validee(demande)
                .flatMap(valide -> notifications.parDemandeId(demande.demandeId())
                        // DEJA TRAITEE : Kafka garantit « au moins une fois », donc
                        // un meme message revient apres un redemarrage ou un
                        // reequilibrage de groupe. Sans ce controle, l'utilisateur
                        // verrait la notification en double et un code de
                        // verification partirait deux fois.
                        .map(dejaVue -> {
                            metriques.rejet("doublon");
                            log.debug("Demande deja traitee, ignoree");
                            return new CompteRenduDeRemise(demande.demandeId(),
                                    List.of(), true, Instant.now());
                        })
                        .switchIfEmpty(Mono.defer(() -> enregistrerPuisRemettre(demande))))
                .doFinally(fin -> metriques.arreterChrono(chrono));
    }

    /**
     * Les demandes inexploitables sont rejetees AVANT d'ecrire en base.
     *
     * <p>Une demande sans destinataire ou sans canal ne peut rien produire :
     * l'enregistrer creerait une ligne inutile et un compte rendu trompeur.
     */
    private Mono<DemandeDeNotification> validee(DemandeDeNotification demande) {
        if (demande.destinataireId() == null || demande.destinataireId().isBlank()) {
            metriques.rejet("sans-destinataire");
            log.warn("Demande sans destinataire, rejetee");
            return Mono.empty();
        }
        if (demande.canaux() == null || demande.canaux().isEmpty()) {
            metriques.rejet("sans-canal");
            log.warn("Demande sans canal, rejetee");
            return Mono.empty();
        }
        return Mono.just(demande);
    }

    private Mono<CompteRenduDeRemise> enregistrerPuisRemettre(DemandeDeNotification demande) {
        return Mono.fromCallable(() -> json.writeValueAsString(
                        demande.variables() == null ? Map.of() : demande.variables()))
                .onErrorReturn("{}")
                .map(variablesJson -> Notification.depuis(demande, variablesJson))
                .flatMap(notifications::save)
                .flatMap(enregistree -> remettreParCanaux(demande, enregistree))
                // Une panne de base NE DOIT PAS faire disparaitre la demande.
                //
                // On rend un compte rendu d'echec plutot que de signaler une
                // erreur : le consommateur Kafka decide alors de NE PAS
                // acquitter, donc le message est repris. Signaler l'erreur
                // aurait le meme effet, mais l'emetteur n'aurait aucun compte
                // rendu — et c'est lui qui doit savoir.
                .onErrorResume(panne -> {
                    log.error("Traitement impossible : {}", panne.getMessage());
                    return Mono.just(new CompteRenduDeRemise(demande.demandeId(),
                            List.of(new CompteRenduDeRemise.RemiseParCanal(
                                    demande.canaux().get(0),
                                    CompteRenduDeRemise.Etat.ECHOUE,
                                    "persistance : " + panne.getClass().getSimpleName())),
                            false, Instant.now()));
                });
    }

    private Mono<CompteRenduDeRemise> remettreParCanaux(DemandeDeNotification demande,
                                                        Notification enregistree) {
        List<CompteRenduDeRemise.RemiseParCanal> journal = new ArrayList<>();
        boolean toutEssayer = demande.urgence() == DemandeDeNotification.Urgence.CRITIQUE;
        boolean unSeulCanal = demande.urgence() == DemandeDeNotification.Urgence.INFORMATION;

        return Flux.fromIterable(demande.canaux())
                .concatMap(canal -> essayer(demande, enregistree, canal)
                        .doOnNext(remise -> {
                            journal.add(remise.versCompteRendu());
                            metriques.remise(remise.getCanal(), remise.getEtat());
                        }))
                // L'arret anticipe : takeUntil et non filter.
                //
                // takeUntil laisse passer l'element qui declenche l'arret, donc
                // le canal qui a abouti figure bien au compte rendu. Un filter
                // l'aurait exclu.
                .takeUntil(remise -> {
                    if (toutEssayer) {
                        return false;
                    }
                    if (unSeulCanal) {
                        return true;
                    }
                    return remise.getEtat() == CompteRenduDeRemise.Etat.REMIS;
                })
                .then(Mono.fromSupplier(() -> {
                    boolean rendu = journal.stream()
                            .anyMatch(r -> r.etat() == CompteRenduDeRemise.Etat.REMIS);

                    // Une CRITIQUE entierement perdue a son propre compteur.
                    //
                    // Une alerte sur « des remises echouent » se declencherait
                    // sur du trafic normal : un WebSocket sans session ouverte,
                    // un canal SMS desactive. Une critique dont AUCUN canal n'a
                    // abouti est d'une autre nature — un code de verification
                    // que personne n'a recu.
                    if (!rendu && toutEssayer) {
                        metriques.critiquePerdue(demande.serviceEmetteur());
                        log.error("Notification CRITIQUE perdue : aucun canal n'a "
                                        + "abouti (modele {}, emetteur {})",
                                demande.modele(), demande.serviceEmetteur());
                    }

                    return new CompteRenduDeRemise(demande.demandeId(),
                            List.copyOf(journal), rendu, Instant.now());
                }));
    }

    private Mono<RemiseParCanal> essayer(DemandeDeNotification demande,
                                         Notification enregistree,
                                         CanalDeNotification canal) {
        CanalDeRemise implementation = canaux.get(canal);
        if (implementation == null) {
            // Un canal demande qu'aucune implementation ne couvre. Le declarer
            // REFUSE le rend visible ; l'ignorer en silence ferait croire a
            // l'emetteur que son canal a ete pris en compte.
            return enregistrer(enregistree.getId(), canal,
                    CompteRenduDeRemise.Etat.REFUSE, "canal non implemente");
        }
        if (!implementation.estActif()) {
            return enregistrer(enregistree.getId(), canal,
                    CompteRenduDeRemise.Etat.REFUSE, "canal inactif");
        }

        var contenu = moteur.rendre(demande.modele(), canal, demande.variables());
        if (contenu.isEmpty()) {
            return enregistrer(enregistree.getId(), canal,
                    CompteRenduDeRemise.Etat.REFUSE, "modele introuvable");
        }

        var message = new CanalDeRemise.MessageARemettre(
                demande.destinataireId(),
                resolveur.pour(demande, canal),
                contenu.get().sujet(),
                contenu.get().corps(),
                demande.urgence(),
                enregistree.getId());

        return implementation.remettre(message)
                // Un canal ne devrait jamais signaler d'erreur ; s'il le fait,
                // on le traite comme une panne au lieu de laisser l'exception
                // interrompre les AUTRES canaux de la meme notification.
                .onErrorResume(imprevu -> {
                    log.error("Canal {} a leve une exception : {}", canal,
                            imprevu.getMessage());
                    return Mono.just(CanalDeRemise.Resultat.echoue(
                            imprevu.getClass().getSimpleName()));
                })
                .flatMap(resultat -> enregistrer(enregistree.getId(), canal,
                        resultat.etat(), resultat.motif()));
    }

    /**
     * Ecrit le sort du canal, en incrementant la ligne existante s'il y en a une.
     *
     * <p>La contrainte d'unicite (notification, canal) l'impose : creer une
     * seconde ligne echouerait. Et c'est aussi ce qui empeche un canal en panne
     * reessaye en boucle de faire grossir la table sans borne.
     */
    private Mono<RemiseParCanal> enregistrer(java.util.UUID notificationId,
                                             CanalDeNotification canal,
                                             CompteRenduDeRemise.Etat etat,
                                             String motif) {
        return remises.parNotificationEtCanal(notificationId, canal.name())
                .map(existante -> existante.dejaEnregistree().nouvelleTentative(etat, motif))
                .defaultIfEmpty(RemiseParCanal.de(notificationId, canal, etat, motif))
                .flatMap(remises::save);
    }
}
