package com.abdatytch.interface_service.service.impl;

import com.abdatytch.interface_service.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.mail.internet.MimeMessage;
import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.interface_service.constants.ApplicationConstants;
import java.util.Map;

/**
 * Implémentation du service d'envoi d'emails
 * Configuration depuis les variables d'environnement
 */
@Service
public class EmailServiceImpl implements EmailService {

    /**
     * Les deux modeles employes, nommes une fois.
     *
     * <p>Les clefs vivent dans {@code modeles-notification.properties} de
     * notification-service, et leur variante HTML dans {@code modeles-html/}.
     * Une faute de frappe ici produirait un refus « modele introuvable » que le
     * journal signalerait — et non un courriel vide.
     */
    private static final String MODELE_CODE_DE_VERIFICATION = "securite.code-de-verification";

    private static final String MODELE_REINITIALISATION =
            "securite.reinitialisation-mot-de-passe";

    /**
     * La messagerie sortante, PARTAGEE avec auth-service.
     *
     * <p>{@code ClientMessagerieNotification} vit dans platform-commons et est
     * enregistre par {@code MessagerieSortanteConfig}. Un client propre a ce
     * service aurait fait deux implementations d'un meme protocole interne.
     */
    private final IMessagerieSortante messagerie;

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    /**
     * Les TROIS expediteurs sont desormais nommes explicitement.
     *
     * <h2>Le defaut que les qualificateurs ferment</h2>
     *
     * Aucune des trois injections n'en portait, et {@code adminMailSender}
     * etait {@code @Primary}. Mesure faite dans
     * {@code QuelExpediteurDeCourrielTest} : {@code @Primary} l'emporte sur la
     * correspondance par nom de parametre, donc le champ {@code mailSender}
     * recevait l'expediteur d'ADMINISTRATION.
     *
     * <p>Tous les courriels ordinaires — codes de verification, reinitialisation
     * de mot de passe — partaient par ce compte, avec un {@code From} qui ne lui
     * correspond pas. Un serveur SMTP refuse ou reecrit un expediteur qui n'est
     * pas le compte authentifie.
     *
     * <p>Deux des trois cablages etaient pourtant corrects — l'injection par
     * CHAMP se resout par le nom du champ — ce qui rendait le defaut d'autant
     * plus difficile a voir.
     *
     * <p>Les qualificateurs sont explicites partout, y compris la ou le nom
     * suffisait : une resolution qui depend du nom d'un champ est une
     * resolution qu'un renommage casse en silence.
     */
    private final JavaMailSender mailSender;

    @Autowired(required = false)
    @Qualifier("adminMailSender")
    private JavaMailSender adminMailSender;

    @Autowired(required = false)
    @Qualifier("criticalMailSender")
    private JavaMailSender criticalMailSender;

    @Value("${admin.mail.from}")
    private String adminFrom;

    @Value("${admin.critical.mail.from}")
    private String criticalFrom;

    public EmailServiceImpl(@Qualifier("mailSender") JavaMailSender mailSender,
                            IMessagerieSortante messagerie) {
        this.mailSender = mailSender;
        this.messagerie = messagerie;
    }

    /**
     * Adresse d'expedition, sans valeur de repli plausible.
     *
     * <p>Le defaut etait {@code noreply@abdatech.com}, sur un domaine que ce
     * projet ne porte plus. Garder une adresse de repli d'apparence valide est
     * le pire des cas : l'envoi part, le serveur distant refuse ou classe en
     * indesirable, et rien ne designe la cause.
     *
     * <p>Vide, l'echec survient a l'ENVOI avec un message explicite, et non au
     * demarrage — la politique du depot est de ne jamais refuser de demarrer
     * pour une variable absente.
     *
     * <p>auth-service emploie deja {@code ${EMAIL_FROM}} sans defaut : ce
     * changement rapproche les deux services au lieu de les laisser divergents.
     */
    @Value("${app.email.from:}")
    private String fromEmail;





    /**
     * Le code de verification par courriel : DELEGUE a notification-service.
     *
     * <h3>Ce que cette methode faisait</h3>
     *
     * Elle lisait {@code templates/otp-email.html}, y inserait le prenom et le
     * code par un {@code String.replace}, attachait le logo et envoyait le
     * message elle-meme. C'etait un SECOND moteur de modeles sur la plateforme,
     * avec trois consequences mesurees :
     *
     * <ul>
     *   <li><b>aucun echappement</b>. Un prenom est fourni a l'inscription :
     *       du balisage y entrait tel quel dans le corps du courriel. Le moteur
     *       de notification-service echappe desormais toute variable d'un corps
     *       HTML ;</li>
     *   <li><b>le gabarit etait en anglais</b> alors que le reste de la
     *       plateforme ecrit en francais ;</li>
     *   <li><b>deux boutons morts</b>, « Verify my account » et « Click here »,
     *       tous deux en {@code href="#"}. Un bouton qui ne mene nulle part dans
     *       un courriel de securite apprend a cliquer dans ce genre de
     *       message.</li>
     * </ul>
     *
     * <h3>Ce que la delegation apporte</h3>
     *
     * Le texte vit dans {@code modeles-notification.properties} et
     * {@code modeles-html/securite.code-de-verification.html}, corrigeable a
     * chaud par {@code PUT /api/v1/admin/modeles}. La variante texte part avec
     * l'HTML, le consentement s'applique, et la remise est tracee et comptee
     * comme toutes les autres.
     *
     * <h3>La signature ne change PAS</h3>
     *
     * Les trois appelants de {@code UserServiceImpl} ne sont pas touches : ce
     * qui change est l'interieur. Le {@code Mono<Void>} reste, et une
     * non-remise ne fait pas echouer l'inscription — c'etait deja le cas, et
     * {@code OTP_GENERE_SANS_ENVOI} existe cote auth-service pour la meme
     * raison.
     */
    @Override
    public Mono<Void> sendEmailOtp(String email, String otpCode, String firstName) {
        return envoyerUnCode(email, otpCode, firstName,
                MODELE_CODE_DE_VERIFICATION);
    }

    @Override
    public Mono<Void> sendPasswordResetOtp(String email, String otpCode, String firstName) {
        return envoyerUnCode(email, otpCode, firstName,
                MODELE_REINITIALISATION);
    }

    /**
     * L'envoi, ecrit une seule fois pour les deux codes.
     *
     * <p>Les deux ne differaient que par leur gabarit et leur sujet, et ils
     * portaient pourtant quarante lignes identiques chacun — lecture du
     * fichier, substitution, piece jointe, gestion d'erreur. Le modele est
     * desormais le seul parametre qui change.
     *
     * <p>{@code CRITIQUE} : un code de verification qui n'arrive pas est une
     * impossibilite de se connecter. L'urgence decide du repli entre canaux,
     * jamais du consentement.
     */
    private Mono<Void> envoyerUnCode(String email, String otpCode, String firstName,
                                     String modele) {
        return messagerie.envoyer(IMessagerieSortante.Demande.critique(
                        CanalDeNotification.COURRIEL, email, modele,
                        Map.of("code", otpCode,
                                "prenom", firstName == null ? "" : firstName,
                                "minutes", String.valueOf(
                                        ApplicationConstants.OTP_VALIDITE_MINUTES))))
                .doOnNext(remis -> {
                    if (remis) {
                        log.info("Code envoye par courriel (modele {})", modele);
                    } else {
                        // Journalise et NON leve : une non-remise ne doit pas
                        // faire echouer l'inscription. L'appelant poursuit, et
                        // la personne peut demander un nouveau code.
                        log.warn("Code NON remis par courriel (modele {})", modele);
                    }
                })
                .then();
    }



    @Override
    public Mono<Void> sendAdminAlertEmail(String adminEmail, String subject, String message) {
        if (adminMailSender == null) {
            log.warn("AdminMailSender non configuré, impossible d'envoyer l'alerte admin");
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            MimeMessage mimeMessage = adminMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(adminFrom);
            helper.setTo(adminEmail);
            helper.setSubject(subject);
            helper.setText(message, true); // true = HTML

            adminMailSender.send(mimeMessage);
            log.info("Alerte admin envoyée à: {}", adminEmail);
            return (Void) null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(error -> log.error("Erreur envoi alerte admin: {}", error.getMessage()))
        .onErrorResume(error -> Mono.empty());
    }

    @Override
    public Mono<Void> sendCriticalAlertEmail(String criticalEmail, String subject, String message) {
        if (criticalMailSender == null) {
            log.warn("CriticalMailSender non configuré, impossible d'envoyer l'alerte critique");
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            SimpleMailMessage mailMessage = new SimpleMailMessage();
            mailMessage.setFrom(criticalFrom);
            mailMessage.setTo(criticalEmail);
            mailMessage.setSubject(subject);
            mailMessage.setText(message);

            criticalMailSender.send(mailMessage);
            log.info("Alerte critique envoyée à: {}", criticalEmail);
            return (Void) null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(error -> log.error("Erreur envoi alerte critique: {}", error.getMessage()))
        .onErrorResume(error -> Mono.empty());
    }
}
