package com.abdatytch.notification_service.scheduler;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.repositories.RemiseParCanalRepository;
import com.abdatytch.notification_service.service.IServiceDeNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Reprend les remises qui ont echoue.
 *
 * <h2>Le defaut que cette classe ferme</h2>
 *
 * {@code RemiseParCanalRepository.aReprendre} existait <b>sans aucun
 * appelant</b>. L'etat {@code ECHOUE} etait donc ecrit avec soin — le schema
 * compte meme les tentatives — et personne ne le lisait : un courriel perdu
 * pendant une panne SMTP restait perdu, et la colonne {@code tentatives} ne
 * depassait jamais 1.
 *
 * <p>Ecrire un etat sur lequel rien n'agit est pire que ne rien ecrire : cela
 * donne l'apparence d'une reprise.
 *
 * <h2>Le SMS est EXCLU de la reprise</h2>
 *
 * Et c'est une decision, pas un oubli. Un SMS remis au fournisseur se paie. Un
 * rejeu dont on ne sait pas si le premier envoi est parti fait payer deux fois
 * et peut livrer le message en double.
 *
 * <p>La distinction serait techniquement possible — le motif dit si le
 * fournisseur a repondu — mais elle reposerait sur une chaine de caracteres, et
 * se tromper coute de l'argent reel. Un echec de SMS n'est pas perdu pour
 * autant : l'utilisateur redemande un code, ce qui est un reessai decide par
 * une personne plutot que par une boucle.
 *
 * <h2>Ce qui est repris, et ce qui ne l'est pas</h2>
 *
 * La requete exclut deja {@code REFUSE} et {@code SANS_DESTINATAIRE} :
 *
 * <ul>
 *   <li>{@code REFUSE} est definitif — aucune adresse connue, modele
 *       introuvable, canal inactif. Reessayer ne trouverait rien de plus, et
 *       une reprise sans fin ferait grossir la file sans jamais la vider ;</li>
 *   <li>{@code SANS_DESTINATAIRE} decrit un WebSocket sans session ouverte :
 *       c'est le fonctionnement normal, pas une panne.</li>
 * </ul>
 *
 * <p>Reste {@code ECHOUE} : serveur injoignable, coordonnees indisponibles,
 * delai depasse. Ce sont les cas ou reessayer a une chance d'aboutir.
 *
 * <h2>Le delai avant reprise, et pourquoi il n'est pas nul</h2>
 *
 * {@code aReprendre} ne retient que les lignes essayees <b>avant</b> un
 * horodatage. Reprendre immediatement reessaierait pendant la panne meme qui a
 * cause l'echec, consommerait le plafond de tentatives en quelques secondes, et
 * aurait tout epuise au moment ou le service revient.
 */
@Component
public class RepriseDesRemises {

    private static final Logger log = LoggerFactory.getLogger(RepriseDesRemises.class);

    private final RemiseParCanalRepository remises;
    private final IServiceDeNotification service;
    private final short tentativesMax;
    private final int taille;
    private final long delaiAvantRepriseMinutes;

    public RepriseDesRemises(
            RemiseParCanalRepository remises,
            IServiceDeNotification service,
            @Value("${notification.reprise.tentatives-max:3}") short tentativesMax,
            @Value("${notification.reprise.taille-du-lot:50}") int taille,
            @Value("${notification.reprise.delai-minutes:5}") long delaiAvantRepriseMinutes) {

        this.remises = remises;
        this.service = service;
        this.tentativesMax = tentativesMax;
        this.taille = taille;
        this.delaiAvantRepriseMinutes = delaiAvantRepriseMinutes;
    }

    /**
     * Une passe de reprise.
     *
     * <p>{@code fixedDelay} et non {@code fixedRate} : le delai court apres la
     * FIN de la passe precedente. Avec {@code fixedRate}, une passe plus longue
     * que l'intervalle verrait la suivante demarrer par-dessus, et deux passes
     * selectionneraient les memes lignes — donc deux remises pour un message.
     *
     * <p>Le lot est borne : une file de dix mille echecs ne doit pas produire
     * dix mille remises d'un coup, ce qui ferait tomber le serveur SMTP qu'on
     * essaie justement de menager.
     */
    @Scheduled(
            initialDelayString = "${notification.reprise.delai-initial-secondes:60}",
            fixedDelayString = "${notification.reprise.intervalle-secondes:120}",
            timeUnit = TimeUnit.SECONDS)
    public void reprendre() {
        LocalDateTime avant = LocalDateTime.now().minusMinutes(delaiAvantRepriseMinutes);

        remises.aReprendre(avant, tentativesMax, taille)
                // Le SMS ne se rejoue pas : voir la javadoc de classe. Filtre
                // ici et non dans la requete, pour que la raison se lise au
                // meme endroit que la decision.
                .filter(remise -> remise.getCanal() != CanalDeNotification.SMS)
                // concatMap et non flatMap : les remises partent une par une.
                // En parallele, une file d'echecs accumulee pendant une panne
                // repartirait d'un coup vers le service qui vient de revenir.
                .concatMap(remise -> service
                        .rejouer(remise.getNotificationId(), remise.getCanal())
                        // Un rejeu qui echoue ne doit pas arreter les suivants :
                        // sans cette garde, la premiere notification en erreur
                        // viderait tout le lot.
                        .onErrorResume(erreur -> {
                            log.error("Rejeu impossible pour la remise {} ({})",
                                    remise.getId(), erreur.getClass().getSimpleName());
                            return Mono.empty();
                        }))
                .count()
                .doOnNext(nombre -> {
                    // Journalise SEULEMENT quand il y a eu quelque chose a
                    // faire : une ligne « 0 reprise » toutes les deux minutes
                    // noierait les journaux et ferait perdre l'habitude de les
                    // lire.
                    if (nombre > 0) {
                        log.info("Reprise : {} remise(s) rejouee(s)", nombre);
                    }
                })
                // Sans abonnement, rien ne s'execute — un Flux construit et
                // jamais souscrit est le defaut silencieux de ce style de code.
                .subscribe(
                        nombre -> { },
                        erreur -> log.error("Passe de reprise interrompue : {}",
                                erreur.getClass().getSimpleName()));
    }
}
