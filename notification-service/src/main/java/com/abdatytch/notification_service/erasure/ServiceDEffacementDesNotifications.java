package com.abdatytch.notification_service.erasure;

import com.abdatytch.notification_service.repositories.JetonDAppareilRepository;
import com.abdatytch.notification_service.repositories.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * L'effacement RGPD de ce qu'un service de notification detient.
 *
 * <h2>Les briques existaient, le cablage manquait</h2>
 *
 * {@code NotificationRepository.toutesPourEffacement} et
 * {@code Notification.anonymiser()} etaient ecrits et documentes — la seconde
 * citee par le javadoc de la premiere — et appeles par PERSONNE. La migration
 * V1 annonce elle aussi que les variables sont tenues separees « pour permettre
 * a l'effacement RGPD de vider variables sans perdre la trace ». Trois endroits
 * decrivaient donc un effacement qui n'avait jamais lieu.
 *
 * <h2>Ce qui est efface, et ce qui reste</h2>
 *
 * <ul>
 *   <li><b>notification.variables</b> vide, et {@code destinataire_id} remplace
 *       par un marqueur. La LIGNE reste : « effacer n'est pas supprimer la
 *       ligne » — supprimer casserait la trace qu'une notification a eu lieu,
 *       dont l'audit a besoin. Le nom du gabarit reste aussi, parce qu'il ne
 *       designe personne ;</li>
 *   <li><b>jeton_d_appareil</b> : les lignes sont SUPPRIMEES, et la difference
 *       est voulue. Un jeton d'appareil n'est pas une trace d'evenement, c'est
 *       un moyen d'atteindre la personne — le garder vide n'aurait aucun sens,
 *       et le garder tel quel permettrait de pousser une notification a
 *       quelqu'un qui a demande son effacement.</li>
 * </ul>
 *
 * <h2>Ce qui n'a PAS besoin d'etre efface</h2>
 *
 * {@code remise_par_canal} ne porte ni adresse ni numero : son champ
 * {@code motif} nomme un etat ou une classe d'exception, jamais une coordonnee.
 * C'est une propriete du code — {@code CanalCourriel} le dit explicitement, son
 * motif ne reprend pas l'adresse — et non une chance. La table est de plus liee
 * par {@code notification_id}, donc elle suit la ligne conservee.
 *
 * <h2>Idempotent, parce que Kafka livre « au moins une fois »</h2>
 *
 * Un meme message d'effacement peut arriver deux fois apres un redemarrage ou
 * un reequilibrage de groupe. Anonymiser une ligne deja anonymisee ne change
 * rien, et retirer des jetons deja retires rend zero. Le second passage compte
 * donc zero enregistrement, ce qui est la verite — et non une erreur.
 */
@Service
public class ServiceDEffacementDesNotifications {

    private static final Logger log =
            LoggerFactory.getLogger(ServiceDEffacementDesNotifications.class);

    private final NotificationRepository notifications;
    private final JetonDAppareilRepository appareils;

    public ServiceDEffacementDesNotifications(NotificationRepository notifications,
                                              JetonDAppareilRepository appareils) {
        this.notifications = notifications;
        this.appareils = appareils;
    }

    /**
     * Efface ce que ce service detient sur cette personne.
     *
     * @param destinataireId l'identifiant du compte, tel que l'emet auth-service
     * @return le nombre d'enregistrements touches : notifications anonymisees
     *         plus jetons retires. Ce nombre part dans l'accuse, donc il doit
     *         etre COMPTE et non estime.
     */
    public Mono<Integer> effacer(String destinataireId) {
        return notifications.toutesPourEffacement(destinataireId)
                .map(notification -> notification.anonymiser())
                .flatMap(notifications::save)
                .count()
                .flatMap(anonymisees -> appareils.retirerTousPour(destinataireId)
                        .defaultIfEmpty(0L)
                        .map(jetons -> {
                            log.info("Effacement RGPD : {} notification(s) anonymisee(s), "
                                    + "{} jeton(s) d'appareil retire(s)",
                                    anonymisees, jetons);
                            return (int) (anonymisees + jetons);
                        }));
    }
}
