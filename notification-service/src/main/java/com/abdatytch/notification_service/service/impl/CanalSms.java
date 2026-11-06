package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.ISmsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Remise par SMS.
 *
 * <h2>Ce canal ne simulait rien, et il n'envoyait rien non plus</h2>
 *
 * Il refusait toute remise avec « aucun operateur implemente ». C'etait exact
 * quand ce module a ete ecrit — et la javadoc d'alors designait deja la suite :
 * « auth-service parle deja a AfrikSMS (...) c'est une duplication a absorber
 * plutot qu'a recreer ».
 *
 * <p>C'est fait : le sous-systeme SMS vit desormais ici. L'operateur est donc
 * implemente, dans le meme module, et ce canal se contentait de l'ignorer —
 * <b>le chemin notification ne pouvait envoyer aucun SMS</b> alors que
 * l'expediteur etait a cote.
 *
 * <h2>UN seul interrupteur, et c'etait deux</h2>
 *
 * Ce canal lisait {@code notification.sms.enabled} ; l'expediteur reel lit
 * {@code sms.enabled}. Deux reglages pour une seule question, qui pouvaient se
 * contredire — et le jour ou ce canal aurait envoye pour de vrai, la
 * contradiction aurait produit un canal « actif » qui n'envoie rien, ou
 * l'inverse.
 *
 * <p>{@code notification.sms.enabled} a donc ete retire. L'etat du canal est
 * celui de l'expediteur, par {@code envoiActif()} : une seule valeur, un seul
 * detenteur.
 *
 * <h2>Les garde-fous de depense sont ceux de l'expediteur</h2>
 *
 * Plafond journalier, liste de numeros autorises, aucun reessai : rien n'est
 * recopie ici. Ce canal n'est qu'un chemin d'appel de plus vers le meme
 * {@code ISmsService} — c'est pourquoi un SMS de notification et un SMS de code
 * de verification se comptent dans le meme plafond. C'est voulu : ils coutent au
 * meme endroit.
 *
 * <h2>Pourquoi {@code @RefreshScope}</h2>
 *
 * Pour que couper le canal depuis le config-repo prenne effet sans redemarrage.
 * Voir {@code RechargementAChaud}.
 */
@Component
@RefreshScope
public class CanalSms implements CanalDeRemise {

    private static final Logger log = LoggerFactory.getLogger(CanalSms.class);

    private final ISmsService smsService;

    public CanalSms(ISmsService smsService) {
        this.smsService = smsService;
    }

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        log.info("Canal SMS : {}", smsService.envoiActif()
                ? "ACTIF"
                : "desactive — toute demande de SMS sera refusee, et comptee");
    }

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.SMS;
    }

    @Override
    public boolean estActif() {
        return smsService.envoiActif();
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        if (message.coordonnee() == null || message.coordonnee().isBlank()) {
            return Mono.just(Resultat.refuse("aucun numero connu"));
        }

        return smsService.sendSms(message.coordonnee(), message.corps())
                .map(parti -> parti
                        ? Resultat.remis()
                        // REFUSE et non ECHOUE : rien n'est parti pour une
                        // raison DELIBEREE — envoi coupe, numero hors liste,
                        // plafond de depense atteint. Un ECHOUE ferait rejouer
                        // le message par la reprise, donc contournerait la
                        // decision meme qui vient d'etre prise.
                        : Resultat.refuse("envoi refuse par les garde-fous de depense"))
                // Une erreur du fournisseur, elle, est un ECHOUE : le message
                // n'est pas parti et reessayer aurait du sens. La reprise exclut
                // malgre tout le canal SMS, pour ne pas payer deux fois un envoi
                // dont on ignore s'il est parti. Voir RepriseDesRemises.
                .onErrorResume(erreur -> {
                    log.warn("Remise SMS en echec : {}", erreur.getClass().getSimpleName());
                    return Mono.just(Resultat.echoue(erreur.getClass().getSimpleName()));
                });
    }
}
