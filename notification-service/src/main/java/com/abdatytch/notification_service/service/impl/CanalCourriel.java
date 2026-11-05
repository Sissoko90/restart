package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Remise par courriel.
 *
 * <h2>SMTP est BLOQUANT, et tout est a l'interieur du scope</h2>
 *
 * {@link JavaMailSender} n'a pas d'API reactive : l'envoi occupe son thread du
 * debut a la fin. La politique de ce depot est zero blocage sur le chemin
 * requete, mesuree par BlockHound.
 *
 * <p>La construction du message est <b>dans</b> le {@code fromCallable}, et non
 * avant : un objet construit a l'exterieur s'evalue a l'assemblage, donc sur le
 * thread appelant, avant que {@code subscribeOn} ne s'applique. C'est le piege
 * exact deja corrige dans {@code ProjectServiceImpl} pour la publication Kafka.
 *
 * <h2>Un expediteur absent REFUSE, et ne tente pas</h2>
 *
 * Sans adresse d'expediteur, le serveur destinataire rejette le message — ou
 * pire, l'accepte et le classe en indesirable. Essayer quand meme produirait un
 * echec qu'on reessaierait indefiniment pour une cause qui ne changera pas.
 * D'ou {@code REFUSE} et non {@code ECHOUE}.
 */
@Component
public class CanalCourriel implements CanalDeRemise {

    private static final Logger log = LoggerFactory.getLogger(CanalCourriel.class);

    private final JavaMailSender expediteur;

    /**
     * Adresse d'expediteur. VIDE par defaut, delibere.
     *
     * <p>Une adresse plausible mais fausse est pire qu'une absente : elle fait
     * croire la configuration faite. Le demarrage n'est pas bloque pour autant
     * — politique du projet — mais le canal se declare inactif.
     */
    @Value("${notification.email.from:}")
    private String adresseExpediteur;

    @Value("${notification.email.reply-to:}")
    private String adresseReponse;

    public CanalCourriel(JavaMailSender expediteur) {
        this.expediteur = expediteur;
    }

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        if (!estActif()) {
            log.error("notification.email.from n'est pas configure : le canal "
                    + "COURRIEL refusera toute remise. Renseigner "
                    + "NOTIFICATION_EMAIL_FROM.");
        }
    }

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.COURRIEL;
    }

    @Override
    public boolean estActif() {
        return adresseExpediteur != null && !adresseExpediteur.isBlank();
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        if (!estActif()) {
            return Mono.just(Resultat.refuse("expediteur non configure"));
        }
        if (message.coordonnee() == null || message.coordonnee().isBlank()) {
            return Mono.just(Resultat.refuse("aucune adresse connue"));
        }

        return Mono.fromCallable(() -> {
                    // TOUT est construit ici, dans le scope boundedElastic.
                    SimpleMailMessage courriel = new SimpleMailMessage();
                    courriel.setFrom(adresseExpediteur);
                    if (adresseReponse != null && !adresseReponse.isBlank()) {
                        courriel.setReplyTo(adresseReponse);
                    }
                    courriel.setTo(message.coordonnee());
                    courriel.setSubject(message.sujet());
                    courriel.setText(message.corps());
                    expediteur.send(courriel);
                    return Resultat.remis();
                })
                .subscribeOn(Schedulers.boundedElastic())
                // Deux rattrapages, et la distinction decide d'un reessai.
                //
                // MailException couvre les refus du serveur et les pannes
                // reseau ; les deux peuvent etre passagers, donc ECHOUE.
                //
                // Le motif ne reprend PAS l'adresse : il part en base et dans
                // les journaux, et une adresse y est une donnee personnelle.
                .onErrorResume(MailException.class, panne -> {
                    log.warn("Remise par courriel echouee : {}", panne.getMessage());
                    return Mono.just(Resultat.echoue(
                            "SMTP : " + panne.getClass().getSimpleName()));
                })
                .onErrorResume(autre -> {
                    log.error("Remise par courriel impossible : {}", autre.getMessage());
                    return Mono.just(Resultat.echoue(
                            autre.getClass().getSimpleName()));
                });
    }
}
