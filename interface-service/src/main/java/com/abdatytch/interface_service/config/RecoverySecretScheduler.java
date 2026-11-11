package com.abdatytch.interface_service.config;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.EmailService;
import com.abdatytch.interface_service.service.RecoverySecretService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Regenere periodiquement le secret de recuperation de l'administrateur, et le
 * lui envoie chiffre.
 *
 * <h2>Les {@code .block()} de cette classe sont deliberes</h2>
 *
 * Ce code ne s'execute que sur le pool de taches planifiees, jamais sur un
 * EventLoop Netty. Bloquer y est le comportement attendu. Ne pas reprendre ce
 * motif dans du code servant des requetes.
 *
 * <h2>LE CORPS DU COURRIEL N'EST PLUS ICI</h2>
 *
 * Cette classe composait le message en concatenant une centaine de fragments
 * de HTML, puis le passait a une methode d'envoi generique qui prenait un corps
 * deja fait. Comme le modele qui le transportait n'avait aucune variante
 * {@code corps-html}, le courriel partait en texte brut AVEC son balisage : le
 * destinataire lisait la source. Mesure faite sur un courriel recu le
 * 2026-11-10 a 20:02 UTC.
 *
 * <p>Le texte vit desormais dans notification-service, en deux variantes —
 * texte et HTML — comme tous les autres messages de la plateforme. Cette classe
 * ne fournit plus que des valeurs.
 *
 * <h2>Le delai est declare UNE fois</h2>
 *
 * {@link #PERIODE} sert a la fois a la planification et au texte du message.
 * Les deux etaient ecrits separement — {@code fixedRate = 24 * 60 * 60 * 1000}
 * d'un cote, « dans 24 heures » de l'autre — donc libres de diverger sans que
 * rien ne le signale. Un courriel qui annonce une echeance fausse est pire
 * qu'un courriel qui n'en annonce aucune.
 */
@Component
public class RecoverySecretScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoverySecretScheduler.class);

    /**
     * La periode de regeneration, source unique.
     *
     * <h3>Pourquoi une chaine ISO-8601 et non un nombre</h3>
     *
     * {@code @Scheduled} exige une CONSTANTE DE COMPILATION ; une
     * {@code Duration} n'en est pas une. Declarer la periode en
     * {@code Duration} obligerait donc a reecrire la meme valeur dans
     * l'annotation — c'est-a-dire a recreer la divergence qu'on ferme ici.
     *
     * <p>Une chaine {@code static final} est une constante de compilation, et
     * {@code fixedRateString} accepte la duree ISO-8601. La {@link #PERIODE}
     * en est DERIVEE, et sert au texte du courriel. Changer la valeur se fait
     * donc a un seul endroit, et la planification comme le message suivent.
     */
    static final String PERIODE_ISO = "PT24H";

    static final Duration PERIODE = Duration.parse(PERIODE_ISO);

    /** Le premier passage, assez tard pour que le contexte soit en place. */
    static final String DELAI_INITIAL_ISO = "PT1M";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RecoverySecretService recoverySecretService;
    private final EmailService emailService;

    @Value("${admin.default.email}")
    private String adminEmail;

    public RecoverySecretScheduler(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   RecoverySecretService recoverySecretService,
                                   EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.recoverySecretService = recoverySecretService;
        this.emailService = emailService;
    }

    /**
     * Regenere le secret : {@value #DELAI_INITIAL_ISO} apres le demarrage,
     * puis chaque {@value #PERIODE_ISO}.
     */
    @Scheduled(initialDelayString = DELAI_INITIAL_ISO, fixedRateString = PERIODE_ISO)
    public void regenerateRecoverySecret() {
        try {
            log.info("Regeneration du secret de recuperation en cours");

            User admin = userRepository.findByEmail(adminEmail).block();

            if (admin == null) {
                log.warn("Administrateur introuvable : le secret de recuperation "
                        + "n'a pas ete regenere");
                return;
            }

            String nouveauSecret = recoverySecretService.generateRecoverySecret();
            String secretChiffre = recoverySecretService.encryptRecoverySecret(nouveauSecret);

            admin.setRecoverySecretHash(passwordEncoder.encode(nouveauSecret));
            userRepository.save(admin).block();

            log.info("Secret de recuperation regenere pour l'administrateur");

            // La non-remise est journalisee par EmailServiceImpl ; ce bloc ne
            // peut donc plus perdre l'information en silence, ce que faisait
            // l'ancien .block() sans compte rendu.
            emailService.envoyerLeSecretDeRecuperation(admin.getEmail(),
                    admin.getFirstName(), secretChiffre, PERIODE.toHours()).block();

        } catch (Exception e) {
            // Ni le secret, ni le chiffre, ni l'adresse ne sont journalises.
            log.error("Regeneration du secret de recuperation impossible : {}",
                    e.getMessage(), e);
        }
    }
}
