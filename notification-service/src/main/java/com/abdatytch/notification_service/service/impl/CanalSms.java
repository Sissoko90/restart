package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Remise par SMS.
 *
 * <h2>DESACTIVE par defaut, et il REFUSE — il ne simule pas</h2>
 *
 * Aucun operateur n'est encore choisi. La configuration precedente de ce module
 * posait {@code provider: mock}, ce qui ressemble a un canal fonctionnel en mode
 * simule : un emetteur demandant un SMS aurait obtenu un succes, et le code de
 * verification n'aurait atteint personne. Un faux succes sur un second facteur
 * est la pire forme d'echec — l'utilisateur est bloque et les journaux disent
 * que tout va bien.
 *
 * <p>Ce canal rend donc {@code REFUSE} tant qu'il n'est pas configure. L'etat
 * remonte dans le compte rendu, donc l'emetteur le sait, et
 * {@code notification.remises.total{canal="SMS",etat="REFUSE"}} le compte.
 *
 * <h2>Ce qu'il reste a decider, et qui ne m'appartient pas</h2>
 *
 * Le choix de l'operateur. Ce depot porte des numeros en {@code +223} (Mali) :
 * un agregateur local — Orange, Moov — et un agregateur international n'ont ni
 * la meme API, ni le meme modele d'accuse de reception, ni la meme facturation.
 * auth-service parle deja a AfrikSMS et recoit ses accuses sur
 * {@code /api/v1/sms/accuses/**} : c'est la piste la plus courte, et c'est aussi
 * une duplication a absorber plutot qu'a recreer.
 *
 * <p>La structure est prete : implementer ce canal, c'est remplir
 * {@code remettre} et rendre {@code estActif} vrai. Rien d'autre a toucher.
 */
@Component
public class CanalSms implements CanalDeRemise {

    private static final Logger log = LoggerFactory.getLogger(CanalSms.class);

    /**
     * Faux par defaut.
     *
     * <p>Un canal qu'on active par erreur sans implementation derriere
     * echouerait a chaque message. Le defaut ferme est le seul sens acceptable
     * ici.
     */
    @Value("${notification.sms.enabled:false}")
    private boolean actif;

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        log.info("Canal SMS : {}", actif
                ? "ACTIF"
                : "desactive — toute demande de SMS sera refusee, et comptee");
    }

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.SMS;
    }

    @Override
    public boolean estActif() {
        return actif;
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        if (!actif) {
            return Mono.just(Resultat.refuse("canal SMS non configure"));
        }
        if (message.coordonnee() == null || message.coordonnee().isBlank()) {
            return Mono.just(Resultat.refuse("aucun numero connu"));
        }

        // Active mais sans operateur : on REFUSE en le disant clairement, au
        // lieu de rendre un succes. Activer le canal ne doit pas suffire a faire
        // croire que les SMS partent.
        log.error("Canal SMS active sans operateur implemente : remise refusee");
        return Mono.just(Resultat.refuse("aucun operateur implemente"));
    }
}
