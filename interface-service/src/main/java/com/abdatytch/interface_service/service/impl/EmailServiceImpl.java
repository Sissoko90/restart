package com.abdatytch.interface_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import com.abdatytch.commons.notification.IMessagerieSortante;
import com.abdatytch.interface_service.constants.ApplicationConstants;
import com.abdatytch.interface_service.dto.GeoLocationInfo;
import com.abdatytch.interface_service.service.EmailService;
import com.abdatytch.interface_service.service.FaitsDeTentativeAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
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
 * Un adaptateur. Les cinq methodes de {@link EmailService} delegent a
 * notification-service par le client partage de platform-commons — le MEME que
 * celui d'auth-service.
 *
 * <p>Ce qui change pour les appelants est que la NON-REMISE est desormais
 * journalisee. Quatre des six appels aux alertes d'exploitation etaient des
 * {@code .subscribe()} sans gestionnaire : une alerte perdue ne laissait aucune
 * trace.
 *
 * <h2>LE DEFAUT QUE LA PREMIERE VERSION DE CET ADAPTATEUR A LAISSE PASSER</h2>
 *
 * La migration avait conserve deux methodes generiques —
 * {@code sendAdminAlertEmail(destinataire, sujet, message)} et sa jumelle
 * critique — qui prenaient un corps DEJA COMPOSE, et les faisait partir par un
 * modele de transit sans variante {@code corps-html}.
 *
 * <p>Or leurs trois appelants composaient du HTML. Le courriel partait donc en
 * {@code text/plain} avec son balisage dedans, et le destinataire lisait la
 * source. Mesure faite sur un courriel recu le 2026-11-10 a 20:02 UTC, soit
 * dix-sept minutes apres la mise en service de cette classe.
 *
 * <p>La javadoc de l'epoque affirmait meme que ces alertes partaient « en TEXTE
 * BRUT » et que c'etait « un gain : l'exploitant voit l'URL complete du lien
 * d'action au lieu d'un bouton qui la cache ». L'affirmation etait exacte et la
 * conclusion fausse : les emetteurs, eux, n'avaient pas cesse d'envoyer du
 * HTML.
 *
 * <p>Les deux methodes generiques ont ete retirees. Une signature qui accepte
 * un corps tout fait ne peut pas savoir si on lui donne du texte ou du
 * balisage, ni quelles variantes le modele d'en face possede. Les trois
 * methodes qui les remplacent prennent des FAITS, et c'est le modele qui decide
 * de la forme.
 *
 * <h2>C'est ICI que les faits deviennent des variables de gabarit</h2>
 *
 * {@code AdminController} ne connait plus aucun nom de variable de modele : il
 * produit un {@link com.abdatytch.interface_service.service.FaitsDeTentativeAdmin}.
 * Le vocabulaire des gabarits vit dans {@code faitsCommuns} et nulle part
 * ailleurs, et {@code VariablesDesAlertesTest} en tient la moitie emetteur du
 * contrat — l'autre moitie etant {@code AlertesDExploitationTest}, dans
 * notification-service.
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

    /**
     * Les trois modeles d'exploitation, qui ont remplace UN modele de transit.
     *
     * <p>{@code exploitation.alerte} portait son sujet et son corps en
     * variables, donc n'imposait aucune forme — et n'avait aucune variante
     * {@code corps-html}. Les appelants composaient du HTML en Java, qui
     * partait en texte brut avec son balisage visible. Chaque incident a
     * desormais son gabarit, et les deux premiers ont une variante HTML.
     */
    private static final String MODELE_SECRET_DE_RECUPERATION =
            "exploitation.secret-de-recuperation";

    private static final String MODELE_CONNEXION_ADMIN = "exploitation.connexion-admin";

    private static final String MODELE_ATTAQUE_ADMIN = "exploitation.attaque-admin";

    /**
     * Ce qu'on affiche a la place d'un fait manquant.
     *
     * <p>Une valeur EXPLICITE, et non une chaine vide : la geolocalisation est
     * un appel a un tiers qui echoue, et une ligne vide dans un rapport
     * d'incident se lit comme une absence d'anomalie plutot que comme une
     * absence de mesure.
     */
    private static final String INCONNU = "non disponible";

    /** Un horodatage lisible par une personne, pas par une machine. */
    private static final DateTimeFormatter HORODATAGE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

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

    @Override
    public Mono<Void> envoyerLeSecretDeRecuperation(String destinataire, String prenom,
                                                    String secretChiffre, long heures) {
        return envoyer(new IMessagerieSortante.Demande(
                        CanalDeNotification.COURRIEL, destinataire,
                        MODELE_SECRET_DE_RECUPERATION,
                        Map.of("prenom", prenom == null ? "" : prenom,
                                "secret", secretChiffre == null ? "" : secretChiffre,
                                "heures", String.valueOf(heures)),
                        DemandeDeNotification.Urgence.NORMALE),
                "secret de recuperation");
    }

    @Override
    public Mono<Void> alerterSurUneConnexionAdmin(String destinataire,
                                                  FaitsDeTentativeAdmin faits,
                                                  String urlDeConfirmation,
                                                  String urlDeBlocage, long minutes) {
        Map<String, String> variables = faitsCommuns(faits);
        variables.put("identifiant", ou(faits.geo() == null ? null : faits.geo().getId()));
        variables.put("evenement", ou(faits.geo() == null ? null : faits.geo().getEventType()));
        variables.put("statut", ou(faits.geo() == null ? null : faits.geo().getStatus()));
        variables.put("horodatage", ou(faits.geo() == null ? null : faits.geo().getTimestamp()));
        variables.put("confirmer", ou(urlDeConfirmation));
        variables.put("bloquer", ou(urlDeBlocage));
        variables.put("minutes", String.valueOf(minutes));

        return envoyer(new IMessagerieSortante.Demande(
                        CanalDeNotification.COURRIEL, destinataire, MODELE_CONNEXION_ADMIN,
                        variables, DemandeDeNotification.Urgence.NORMALE),
                "alerte de connexion admin");
    }

    @Override
    public Mono<Void> alerterSurUneAttaqueAdmin(String destinataire,
                                                FaitsDeTentativeAdmin faits,
                                                String urlDeMaintenance, long heures) {
        Map<String, String> variables = faitsCommuns(faits);
        variables.put("tentatives", ou(faits.tentatives()));
        variables.put("tentativesApresConfirmation",
                ou(faits.tentativesApresConfirmation()));
        variables.put("maintenance", ou(urlDeMaintenance));
        variables.put("heures", String.valueOf(heures));

        return envoyer(new IMessagerieSortante.Demande(
                        CanalDeNotification.COURRIEL, destinataire, MODELE_ATTAQUE_ADMIN,
                        variables, DemandeDeNotification.Urgence.CRITIQUE),
                "alerte d'attaque admin");
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
     * Les faits communs aux deux alertes, traduits en variables de modele.
     *
     * <h3>C'est ICI que vivent les noms de variables, et nulle part ailleurs</h3>
     *
     * Le controleur produit des faits ; cet adaptateur seul connait le
     * vocabulaire des gabarits de notification-service. Un gabarit renomme ne
     * touche donc que ce fichier.
     *
     * <h3>Une carte MUTABLE, deliberement</h3>
     *
     * {@code Map.of} s'arrete a dix paires et refuse une valeur nulle — or il y
     * a dix-sept faits communs, et la geolocalisation peut echouer en entier.
     * Chaque appelant complete ensuite avec ce qui lui est propre.
     *
     * <h3>Le {@code User-Agent} vient de la REQUETE, pas de la geolocalisation</h3>
     *
     * L'ancien code affichait {@code geoInfo.getDeviceInfo()} sous l'etiquette
     * « User-Agent » : une valeur derivee, absente des que l'appel au tiers
     * echouait — c'est-a-dire au moment ou l'on en a le plus besoin. La requete,
     * elle, porte toujours son en-tete.
     */
    private Map<String, String> faitsCommuns(FaitsDeTentativeAdmin faits) {
        GeoLocationInfo geo = faits.geo();
        Map<String, String> variables = new HashMap<>();
        variables.put("courriel", ou(faits.courriel()));
        variables.put("heure", LocalDateTime.now().format(HORODATAGE));
        variables.put("ip", ou(faits.adresseIp()));
        variables.put("agent", ou(faits.userAgent()));
        variables.put("pays", ou(geo == null ? null : geo.getCountry()));
        variables.put("codePays", ou(geo == null ? null : geo.getCountryCode()));
        variables.put("ville", ou(geo == null ? null : geo.getCity()));
        variables.put("region", ou(geo == null ? null : geo.getRegion()));
        variables.put("fuseau", ou(geo == null ? null : geo.getTimezone()));
        variables.put("latitude", ou(geo == null ? null : geo.getLatitude()));
        variables.put("longitude", ou(geo == null ? null : geo.getLongitude()));
        variables.put("fournisseur", ou(geo == null ? null : geo.getIsp()));
        variables.put("organisation", ou(geo == null ? null : geo.getOrganization()));
        variables.put("asn", ou(geo == null ? null : geo.getAsn()));
        variables.put("typeAppareil", ou(geo == null ? null : geo.getDeviceType()));
        variables.put("navigateur", deuxParties(
                geo == null ? null : geo.getBrowserFamily(),
                geo == null ? null : geo.getBrowserVersion()));
        variables.put("systeme", deuxParties(
                geo == null ? null : geo.getOsFamily(),
                geo == null ? null : geo.getOsVersion()));
        return variables;
    }

    /**
     * Une valeur affichable, ou {@link #INCONNU}.
     *
     * <p>Le blanc compte comme absent : un service de geolocalisation qui rend
     * une chaine vide ne dit rien de plus qu'un service injoignable, et la
     * difference n'interesse pas la personne qui lit l'alerte.
     */
    private static String ou(Object valeur) {
        if (valeur == null) {
            return INCONNU;
        }
        String texte = String.valueOf(valeur).trim();
        return texte.isEmpty() ? INCONNU : texte;
    }

    /**
     * « Famille version », ou ce qui est connu des deux, ou {@link #INCONNU}.
     *
     * <p>Sans cela, un navigateur identifie sans numero de version afficherait
     * « Chrome non disponible », qui se lit comme une anomalie alors que c'est
     * une information partielle.
     */
    private static String deuxParties(String famille, String version) {
        String f = ou(famille);
        String v = ou(version);
        if (f.equals(INCONNU)) {
            return v;
        }
        return v.equals(INCONNU) ? f : f + " " + v;
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
