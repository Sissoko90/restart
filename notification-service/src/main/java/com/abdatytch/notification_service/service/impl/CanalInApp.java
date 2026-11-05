package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Boite de reception applicative.
 *
 * <h2>Ce canal n'ecrit RIEN, et c'est voulu</h2>
 *
 * La notification est deja enregistree par l'orchestrateur <b>avant</b> que les
 * canaux ne soient essayes : la ligne existe, donc elle est deja dans la boite
 * de reception. Ce canal se contente de le constater.
 *
 * <p>Pourquoi l'enregistrement est en amont et non ici : l'identifiant de la
 * notification doit etre connu des AUTRES canaux — le message WebSocket et la
 * notification push le portent, pour que le client puisse marquer l'element lu.
 * Si ce canal creait la ligne, un ordre different des canaux changerait le
 * resultat, et demander PUSH sans IN_APP enverrait un identifiant inexistant.
 *
 * <p>Ce canal existe donc pour dire explicitement « la boite de reception fait
 * partie des canaux demandes » plutot que de l'appliquer en silence. Sans lui,
 * un emetteur ne pourrait pas distinguer une notification destinee a la boite
 * d'une notification purement volatile — et le compte rendu ne mentionnerait
 * jamais IN_APP.
 *
 * <p>Il est toujours actif : il ne depend d'aucune configuration ni d'aucun
 * tiers.
 */
@Component
public class CanalInApp implements CanalDeRemise {

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.IN_APP;
    }

    @Override
    public boolean estActif() {
        return true;
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        // La ligne existe deja. Un identifiant absent signifierait que
        // l'orchestrateur n'a pas enregistre : ce serait un defaut de code, pas
        // une panne de canal, et le declarer REFUSE le rendrait visible dans le
        // compte rendu au lieu de passer pour un succes.
        if (message.notificationId() == null) {
            return Mono.just(Resultat.refuse("notification non enregistree"));
        }
        return Mono.just(Resultat.remis());
    }
}
