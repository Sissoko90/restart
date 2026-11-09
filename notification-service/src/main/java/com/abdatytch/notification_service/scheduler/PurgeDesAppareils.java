package com.abdatytch.notification_service.scheduler;

import com.abdatytch.notification_service.repositories.JetonDAppareilRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Retire les appareils qui ne se manifestent plus.
 *
 * <h2>Pourquoi une purge, alors que FCM signale les jetons morts</h2>
 *
 * Parce qu'il ne les signale qu'a l'ENVOI. Un jeton n'est declare
 * {@code UNREGISTERED} que le jour ou l'on tente de pousser vers lui ; une
 * personne qui ne recoit aucune notification garde donc son jeton perime
 * indefiniment, et la table grossit sans borne.
 *
 * <p>C'est le pendant de {@code RepriseDesRemises} : l'une rattrape ce qui a
 * echoue, l'autre retire ce qui ne sert plus.
 *
 * <h2>Pourquoi {@code vu_le} et non {@code enregistre_le}</h2>
 *
 * Un client reenregistre son jeton a CHAQUE demarrage de l'application — c'est
 * ce que demande la documentation de {@code AppareilController}, et c'est ce
 * qui rafraichit cette date. Un {@code vu_le} ancien signifie donc « cette
 * application n'a pas demarre depuis longtemps », et non « ce jeton est vieux ».
 *
 * <h2>Le delai est GENEREUX, et pour une raison precise</h2>
 *
 * Purger trop tot couperait les notifications de quelqu'un qui reviendrait —
 * mais le cout reel est faible : son jeton est reenregistre des la premiere
 * ouverture de l'application. Le risque inverse est plus desagreable : une
 * personne qui ouvre son application tous les deux mois perdrait ses alertes de
 * securite entre deux fois.
 *
 * <p>D'ou un defaut de six mois. Ce n'est pas une purge de place, c'est une
 * purge d'hygiene.
 *
 * <h2>Pas de {@code @RefreshScope} sur cette classe</h2>
 *
 * Une tache {@code @Scheduled} est enregistree une seule fois au demarrage et
 * ne serait jamais reenregistree sur le mandataire recree par un
 * rafraichissement : elle cesserait de s'executer, en silence. Regle gardee par
 * {@code RefreshScopeSansPlanificationTest} dans platform-commons.
 */
@Component
public class PurgeDesAppareils {

    private static final Logger log = LoggerFactory.getLogger(PurgeDesAppareils.class);

    private final JetonDAppareilRepository appareils;
    private final int jours;

    public PurgeDesAppareils(
            JetonDAppareilRepository appareils,
            @Value("${notification.push.purge-apres-jours:180}") int jours) {
        this.appareils = appareils;
        this.jours = jours;
    }

    /**
     * Une passe par jour.
     *
     * <p>{@code fixedDelay} et non {@code cron} : l'intervalle court apres la
     * FIN de la passe precedente, donc deux passes ne peuvent pas se
     * chevaucher. Avec une expression cron, une passe lente serait relancee
     * pendant qu'elle tourne encore.
     *
     * <p>{@code subscribe} explicite, avec son gestionnaire d'erreur : un
     * {@code Mono} non souscrit ne s'execute pas, et ce depot a deja trouve des
     * chaines reactives construites puis abandonnees.
     */
    @Scheduled(fixedDelayString = "${notification.push.purge-intervalle-ms:86400000}",
            initialDelayString = "${notification.push.purge-delai-initial-ms:120000}")
    public void purger() {
        LocalDateTime avant = LocalDateTime.now().minusDays(jours);

        appareils.purgerLesDormants(avant).subscribe(
                retires -> {
                    if (retires != null && retires > 0) {
                        // Le NOMBRE seulement : ni jeton, ni destinataire.
                        log.info("{} appareil(s) sans signe de vie depuis {} jours "
                                + "retire(s)", retires, jours);
                    }
                },
                panne -> log.warn("Purge des appareils impossible : {}",
                        panne.getClass().getSimpleName()));
    }
}
