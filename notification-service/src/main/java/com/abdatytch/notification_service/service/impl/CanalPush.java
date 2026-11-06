package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Remise par notification push, via Firebase Cloud Messaging.
 *
 * <h2>DESACTIVE par defaut, et il REFUSE</h2>
 *
 * Meme raison que le canal SMS : la configuration precedente posait une
 * {@code FCM_SERVER_KEY} et un {@code sender-id: dev-sender-id} sans
 * implementation derriere. Un canal qui repond « remis » sans rien envoyer est
 * un faux succes, et c'est exactement le motif que ce depot passe son temps a
 * retirer.
 *
 * <h2>Ce qui manque, et ce n'est pas qu'une cle</h2>
 *
 * Un push a besoin d'un <b>jeton d'appareil</b>, pas d'une adresse : un meme
 * porteur en a autant que d'appareils, ils expirent, et FCM les invalide. Il
 * faut donc une table de jetons par porteur, un endpoint pour que le client
 * enregistre le sien, et le traitement des reponses d'invalidation de FCM —
 * sans quoi on pousse indefiniment vers des appareils disparus.
 *
 * <p>Rien de cela n'existe encore, et l'inventer a moitie serait pire que de
 * l'annoncer absent. {@code estActif} rend donc faux, et le compte rendu le dit
 * a chaque demande.
 *
 * <p>Note de securite : la cle serveur FCM autorise a pousser vers TOUS les
 * appareils de l'application. Elle ne doit jamais atteindre un client, ni un
 * journal, ni un message du bus.
 */
@Component
@RefreshScope
public class CanalPush implements CanalDeRemise {

    private static final Logger log = LoggerFactory.getLogger(CanalPush.class);

    @Value("${notification.push.enabled:false}")
    private boolean actif;

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        log.info("Canal PUSH : {}", actif
                ? "ACTIF"
                : "desactive — toute demande de push sera refusee, et comptee");
    }

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.PUSH;
    }

    @Override
    public boolean estActif() {
        return actif;
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        if (!actif) {
            return Mono.just(Resultat.refuse("canal PUSH non configure"));
        }
        if (message.coordonnee() == null || message.coordonnee().isBlank()) {
            // Pour ce canal, la coordonnee est un JETON D'APPAREIL. Son absence
            // est le cas NORMAL aujourd'hui : aucune table ne les enregistre.
            return Mono.just(Resultat.refuse("aucun jeton d'appareil enregistre"));
        }

        log.error("Canal PUSH active sans client FCM implemente : remise refusee");
        return Mono.just(Resultat.refuse("aucun client FCM implemente"));
    }
}
