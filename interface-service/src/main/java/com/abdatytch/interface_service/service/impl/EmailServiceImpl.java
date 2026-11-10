package com.abdatytch.interface_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.interface_service.constants.ApplicationConstants;
import com.abdatytch.interface_service.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Les courriels de ce service, qui n'en envoie plus aucun lui-meme.
 *
 * <h2>Ce que cette classe etait</h2>
 *
 * Un expediteur complet : trois comptes SMTP declares a la main, deux gabarits
 * HTML lus du chemin de classe, un logo attache en ressource integree, et la
 * substitution faite par {@code String.replace}. C'etait un SECOND moteur de
 * modeles sur la plateforme, et il portait quatre defauts mesures :
 *
 * <ul>
 *   <li><b>aucun echappement</b> du prenom, fourni a l'inscription ;</li>
 *   <li>les gabarits etaient <b>en anglais</b> quand tous les modeles texte de
 *       la plateforme sont en francais ;</li>
 *   <li><b>deux boutons morts</b> en {@code href="#"} dans un courriel de
 *       securite ;</li>
 *   <li>l'echeance du code <b>n'apparaissait pas</b>, alors que la variante
 *       texte l'annonce.</li>
 * </ul>
 *
 * <h2>Ce qu'elle est devenue</h2>
 *
 * Un adaptateur. Les quatre methodes de {@link EmailService} delegent a
 * notification-service par le client partage de platform-commons — le MEME que
 * celui d'auth-service. Les signatures ne changent pas : les appelants de
 * {@code UserServiceImpl}, {@code AdminController} et
 * {@code RecoverySecretScheduler} ne sont pas touches.
 *
 * <p>Ce qui change pour eux, en revanche, est que la NON-REMISE est desormais
 * journalisee. Quatre des six appels aux alertes d'exploitation etaient des
 * {@code .subscribe()} sans gestionnaire : une alerte perdue ne laissait
 * aucune trace.
 *
 * <h2>Pourquoi aucune methode ne leve</h2>
 *
 * Le {@code Mono<Void>} se termine toujours normalement, remise ou pas. C'etait
 * deja le contrat — l'ancienne implementation avalait ses erreurs par
 * {@code onErrorResume(error -> Mono.empty())} — et il doit l'etre : une
 * inscription ne doit pas echouer parce qu'un courriel n'est pas parti. La
 * difference est qu'elle le DIT maintenant.
 */
@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    /**
     * Les modeles employes, nommes une fois.
     *
     * <p>Les clefs vivent dans {@code modeles-notification.properties} de
     * notification-service, et les variantes HTML des deux premiers dans
     * {@code modeles-html/}. Une faute de frappe ici produit un refus
     * « modele introuvable » que le journal signale — et non un courriel vide.
     */
    private static final String MODELE_CODE_DE_VERIFICATION = "securite.code-de-verification";

    private static final String MODELE_REINITIALISATION =
            "securite.reinitialisation-mot-de-passe";

    /** Alertes destinees a un exploitant. Modele de transit : voir le fichier. */
    private static final String MODELE_ALERTE = "exploitation.alerte";

    /**
     * La messagerie sortante, PARTAGEE avec auth-service.
     *
     * <p>{@code ClientMessagerieNotification} vit dans platform-commons et est
     * enregistre par {@code MessagerieSortanteConfig}. Un client propre a ce
     * service aurait fait deux implementations d'un meme protocole interne.
     */
    private final IMessagerieSortante messagerie;

    public EmailServiceImpl(IMessagerieSortante messagerie) {
        this.messagerie = messagerie;
    }

    @Override
    public Mono<Void> sendEmailOtp(String email, String otpCode, String firstName) {
        return envoyerUnCode(email, otpCode, firstName, MODELE_CODE_DE_VERIFICATION);
    }

    @Override
    public Mono<Void> sendPasswordResetOtp(String email, String otpCode, String firstName) {
        return envoyerUnCode(email, otpCode, firstName, MODELE_REINITIALISATION);
    }

    /**
     * Une alerte d'exploitation ordinaire.
     *
     * <p>{@code NORMALE} : elle part par le compte d'expedition courant, avec
     * repli entre canaux. Une alerte d'exploitation importe, mais elle ne
     * conditionne pas l'acces de quelqu'un a son compte.
     */
    @Override
    public Mono<Void> sendAdminAlertEmail(String adminEmail, String subject, String message) {
        return envoyerUneAlerte(adminEmail, subject, message,
                DemandeDeNotification.Urgence.NORMALE);
    }

    /**
     * Une alerte d'exploitation CRITIQUE.
     *
     * <p>{@code CRITIQUE} : elle emprunte le compte d'expedition dedie, et
     * {@code CanalCourriel} la fait repartir par le compte ordinaire si celui-la
     * echoue. C'est ce qui separe une alerte qui arrive d'une alerte perdue.
     */
    @Override
    public Mono<Void> sendCriticalAlertEmail(String criticalEmail, String subject,
                                            String message) {
        return envoyerUneAlerte(criticalEmail, subject, message,
                DemandeDeNotification.Urgence.CRITIQUE);
    }

    /**
     * L'envoi d'un code, ecrit une seule fois pour les deux.
     *
     * <p>Les deux methodes ne differaient que par leur gabarit et leur sujet, et
     * portaient pourtant quarante lignes identiques chacune : lecture du
     * fichier, substitution, piece jointe, gestion d'erreur.
     *
     * <p>{@code CRITIQUE} : un code de verification qui n'arrive pas est une
     * impossibilite de se connecter. L'urgence decide du repli entre canaux et
     * du compte d'expedition, jamais du consentement — c'est le CANAL qui en
     * decide, donc cet endpoint ne peut pas servir a le contourner.
     */
    private Mono<Void> envoyerUnCode(String email, String otpCode, String firstName,
                                     String modele) {
        return envoyer(IMessagerieSortante.Demande.critique(
                        CanalDeNotification.COURRIEL, email, modele,
                        Map.of("code", otpCode,
                                "prenom", firstName == null ? "" : firstName,
                                "minutes", String.valueOf(
                                        ApplicationConstants.OTP_VALIDITE_MINUTES))),
                "code (" + modele + ")");
    }

    /**
     * L'envoi d'une alerte, ecrit une seule fois pour les deux urgences.
     *
     * <p>Le titre et le corps passent en VARIABLES : ces alertes portent de la
     * donnee d'incident composee par l'appelant — geolocalisation, appareil,
     * liens d'action — et non un texte reutilisable. Le modele est de transit,
     * et le fichier de modeles dit pourquoi.
     *
     * <p>Elles partent en TEXTE BRUT, la ou l'ancienne implementation posait
     * {@code setText(message, true)}. Pour une alerte de securite c'est un
     * gain : l'exploitant voit l'URL complete du lien d'action au lieu d'un
     * bouton qui la cache.
     */
    private Mono<Void> envoyerUneAlerte(String destinataire, String titre, String corps,
                                        DemandeDeNotification.Urgence urgence) {
        return envoyer(new IMessagerieSortante.Demande(
                        CanalDeNotification.COURRIEL, destinataire, MODELE_ALERTE,
                        Map.of("titre", titre == null ? "Alerte" : titre,
                                "corps", corps == null ? "" : corps),
                        urgence),
                "alerte " + urgence);
    }

    /**
     * L'appel, et le compte rendu de ce qui est reellement arrive.
     *
     * <p>Le libelle ne reprend NI l'adresse, NI le contenu : il part dans les
     * journaux, ou une adresse est une donnee personnelle et un corps d'alerte
     * peut citer une adresse IP ou un jeton de maintenance.
     */
    private Mono<Void> envoyer(IMessagerieSortante.Demande demande, String libelle) {
        return messagerie.envoyer(demande)
                .doOnNext(remis -> {
                    if (remis) {
                        log.info("Courriel remis : {}", libelle);
                    } else {
                        log.warn("Courriel NON remis : {}", libelle);
                    }
                })
                .then();
    }
}
