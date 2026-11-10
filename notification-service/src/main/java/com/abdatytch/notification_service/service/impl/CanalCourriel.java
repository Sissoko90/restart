package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import com.abdatytch.commons.notification.DemandeDeNotification;
import org.springframework.beans.factory.annotation.Qualifier;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.cloud.context.config.annotation.RefreshScope;
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
@RefreshScope
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

    /**
     * Le compte des notifications CRITIQUES.
     *
     * <p>Alertes de securite, codes de verification. Un compte dedie garde leur
     * reputation d'expedition separee de celle du trafic ordinaire : un courriel
     * marque indesirable parce qu'une campagne a sature le compte commun est un
     * code que personne ne recoit.
     *
     * <p>Il retombe sur les reglages du compte ordinaire s'il n'est pas
     * configure — un second compte ameliore la remise, il n'en est pas la
     * condition.
     */
    private final JavaMailSender expediteurCritique;

    /**
     * Les deux injections portent un {@code @Qualifier}, et ce n'est pas du
     * zele.
     *
     * <p>interface-service avait trois {@code JavaMailSender} sans aucun
     * qualificateur, dont un {@code @Primary} : mesure faite, le parametre
     * nomme {@code mailSender} recevait le compte d'ADMINISTRATION, et tous les
     * courriels ordinaires partaient par lui. Deux des trois cablages etaient
     * pourtant corrects, ce qui rendait le defaut tres difficile a voir.
     */
    /**
     * Les deux disjoncteurs, un par compte d'expedition.
     *
     * <h3>Pourquoi DEUX et non un seul</h3>
     *
     * Les deux comptes peuvent viser des serveurs differents. Un disjoncteur
     * partage ferait couper les alertes de securite parce que le courrier
     * ordinaire echoue — ou l'inverse, ce qui est pire.
     *
     * <h3>Pourquoi un disjoncteur sur le chemin CRITIQUE</h3>
     *
     * La question se pose : couper, c'est renoncer a tenter. Mais l'alternative
     * n'est pas « tenter et reussir », c'est « tenter et attendre » — un SMTP
     * injoignable ne refuse pas, il fait expirer. Chaque envoi occupe alors un
     * thread de {@code boundedElastic} pendant le delai complet, et ce pool est
     * PARTAGE : il porte aussi les OTP. Dix alertes vers un serveur mort
     * retardent donc des connexions.
     *
     * <p>Le disjoncteur ne fait pas perdre un courriel qui serait parti : il
     * refuse vite ce qui aurait echoue lentement. La non-remise est deja
     * enregistree par l'appelant — {@code AlerteDeSecuriteServiceImpl} compte
     * {@code resultat=non_remise} — donc l'echec reste visible.
     */
    static final String DISJONCTEUR_ORDINAIRE = "smtp-ordinaire";
    static final String DISJONCTEUR_CRITIQUE = "smtp-critique";

    private final IDisjoncteurSortant disjoncteur;

    public CanalCourriel(
            @Qualifier("expediteurOrdinaire") JavaMailSender expediteur,
            @Qualifier("expediteurCritique") JavaMailSender expediteurCritique,
            IDisjoncteurSortant disjoncteur) {
        this.expediteur = expediteur;
        this.expediteurCritique = expediteurCritique;
        this.disjoncteur = disjoncteur;
        // Declares au demarrage, comme AfrikSmsProvider : sinon l'instance
        // n'existe qu'apres le premier envoi, donc invisible de la supervision
        // jusque-la — et c'est precisement avant le premier envoi qu'on veut
        // voir le panneau.
        disjoncteur.declarer(DISJONCTEUR_ORDINAIRE);
        disjoncteur.declarer(DISJONCTEUR_CRITIQUE);
    }

    /**
     * Le compte a employer pour ce message.
     *
     * <p>L'urgence vient de l'emetteur, par {@code DemandeDeNotification}, et
     * c'est la seule information dont ce canal dispose pour trancher. Elle
     * suffit : ce qui justifie un compte separe est l'enjeu du message, pas son
     * contenu.
     *
     * <p>{@code null} n'est pas un cas particulier a part : une urgence absente
     * vaut « ordinaire », ce qui est le repli sans surprise — on ne promeut pas
     * un message au compte critique faute de savoir.
     */
    private JavaMailSender expediteurPour(MessageARemettre message) {
        return estCritique(message) ? expediteurCritique : expediteur;
    }

    /**
     * Le disjoncteur du compte employe pour ce message.
     *
     * <p>Il suit la MEME condition que {@link #expediteurPour}, par
     * {@link #estCritique}. Ecrire le test deux fois ferait qu'un message parte
     * par un compte et compte dans le disjoncteur de l'autre — un
     * disjoncteur qui mesure un autre serveur que celui qu'il protege.
     */
    private String disjoncteurPour(MessageARemettre message) {
        return estCritique(message) ? DISJONCTEUR_CRITIQUE : DISJONCTEUR_ORDINAIRE;
    }

    /** La condition, ecrite une seule fois. */
    private static boolean estCritique(MessageARemettre message) {
        return message.urgence() == DemandeDeNotification.Urgence.CRITIQUE;
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

        return disjoncteur.proteger(disjoncteurPour(message), Mono.fromCallable(() -> {
                    // TOUT est construit ici, dans le scope boundedElastic.
                    SimpleMailMessage courriel = new SimpleMailMessage();
                    courriel.setFrom(adresseExpediteur);
                    if (adresseReponse != null && !adresseReponse.isBlank()) {
                        courriel.setReplyTo(adresseReponse);
                    }
                    courriel.setTo(message.coordonnee());
                    courriel.setSubject(message.sujet());
                    courriel.setText(message.corps());
                    expediteurPour(message).send(courriel);
                    return Resultat.remis();
                })
                .subscribeOn(Schedulers.boundedElastic()))
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
