package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.service.CanalDeRemise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
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

    /**
     * Le logo integre au corps HTML, s'il est configure.
     *
     * <h3>Un CHEMIN DE RESSOURCE SPRING, et facultatif</h3>
     *
     * La valeur passe par {@code ResourceLoader}, donc les deux formes servent
     * sans second mecanisme : {@code classpath:assets/logo.png} pour l'image
     * livree avec le service, ou un chemin absolu pour la remplacer sans
     * reconstruire. C'est le protocole de ressources de Spring qui tranche, pas
     * un test sur la chaine.
     *
     * <p>Facultatif, et l'absence ne bloque RIEN : le courriel part sans logo,
     * l'image manquante s'affichant comme telle. Refuser l'envoi pour une
     * image serait disproportionne — c'est un code de verification qui est dans
     * le message.
     *
     * <p>Le gabarit HTML y fait reference par {@code cid:logo}. Si le gabarit
     * ne le cite pas, la piece jointe n'est pas ajoutee : un courriel qui
     * transporte une image que personne n'affiche grossit pour rien et attire
     * les filtres anti-indesirables.
     */
    @Value("${notification.email.logo-integre:}")
    private String cheminDuLogo;

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

    /**
     * Une tentative d'envoi, protegee par le disjoncteur nomme.
     *
     * <p>L'expediteur est un PARAMETRE et non une deduction : c'est ce qui
     * permet de rejouer le meme message par un autre compte.
     */
    private Mono<Resultat> tenter(MessageARemettre message, JavaMailSender expediteurChoisi,
                                  String nomDuDisjoncteur) {
        return disjoncteur.proteger(nomDuDisjoncteur, Mono.fromCallable(() -> {
                    // TOUT est construit ici, dans le scope boundedElastic.
                    if (message.corpsHtml() == null) {
                        envoyerTexteSeul(message, expediteurChoisi);
                    } else {
                        envoyerAvecVarianteHtml(message, expediteurChoisi);
                    }
                    return Resultat.remis();
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Une CRITIQUE dont le compte dedie echoue repart par le compte ordinaire.
     *
     * <h3>Le defaut que cela ferme, mesure</h3>
     *
     * La configuration annonce qu'un second compte « ameliore la remise, il
     * n'en est pas la condition ». Son repli ne couvrait pourtant que des
     * valeurs ABSENTES, pas des valeurs FAUSSES.
     *
     * <p>Constat du 2026-11-10 sur l'environnement de developpement : le compte
     * ordinaire s'authentifie, le compte critique est refuse par le serveur —
     * identifiants devenus invalides. Les alertes de securite et les codes de
     * verification, qui sont precisement ce qui passe en CRITIQUE, etaient donc
     * les SEULS messages perdus. Exactement l'inverse de ce que le second
     * compte cherche a obtenir.
     *
     * <h3>Pourquoi le repli ne vaut QUE pour les critiques</h3>
     *
     * Un message ordinaire dont l'envoi echoue est deja reessaye par la reprise,
     * et rien ne justifie de le faire partir d'un compte dont la reputation est
     * reservee a ce qui doit arriver. Une CRITIQUE, elle, n'a pas de seconde
     * chance utile : un code de verification reessaye dans trois minutes
     * n'interesse plus personne.
     *
     * <h3>Une seule tentative de repli</h3>
     *
     * Si le compte ordinaire echoue aussi, l'erreur remonte et devient un
     * ECHOUE normal. Pas de troisieme essai : les deux comptes injoignables
     * signifient que c'est le serveur ou le reseau, et insister occuperait des
     * threads de boundedElastic — ceux qui portent aussi les OTP.
     *
     * <p>Le disjoncteur du compte ordinaire s'applique a cette seconde
     * tentative : un compte critique durablement casse ne doit pas faire
     * ouvrir le disjoncteur ordinaire par ricochet au-dela de son propre seuil.
     */
    private Mono<Resultat> replierSurLOrdinaire(MessageARemettre message,
                                                Throwable premierEchec) {
        if (!estCritique(message)) {
            return Mono.error(premierEchec);
        }
        log.warn("Compte critique indisponible ({}) : la notification CRITIQUE "
                        + "repart par le compte ordinaire",
                premierEchec.getClass().getSimpleName());
        return tenter(message, expediteur, DISJONCTEUR_ORDINAIRE);
    }

    /**
     * Un courriel de texte brut.
     *
     * <p>{@code SimpleMailMessage} suffit et reste le chemin de la grande
     * majorite des modeles. Il est conserve plutot que de tout faire passer par
     * {@code MimeMessageHelper} : moins de pieces a assembler sur le chemin le
     * plus emprunte.
     */
    private void envoyerTexteSeul(MessageARemettre message, JavaMailSender expediteurChoisi) {
        SimpleMailMessage courriel = new SimpleMailMessage();
        courriel.setFrom(adresseExpediteur);
        if (adresseReponse != null && !adresseReponse.isBlank()) {
            courriel.setReplyTo(adresseReponse);
        }
        courriel.setTo(message.coordonnee());
        courriel.setSubject(message.sujet());
        courriel.setText(message.corps());
        expediteurChoisi.send(courriel);
    }

    /**
     * Un courriel multipart : texte brut ET variante HTML.
     *
     * <h3>Les DEUX variantes, et dans cet ordre</h3>
     *
     * {@code setText(texte, html)} produit un {@code multipart/alternative} ou
     * le texte precede l'HTML. L'ordre est impose par la norme MIME : le client
     * affiche la DERNIERE variante qu'il sait lire, donc l'HTML quand il le
     * peut, et le texte sinon.
     *
     * <p>Envoyer l'HTML seul serait plus simple et nettement moins bon : un
     * message sans variante texte est un signal reconnu des filtres
     * anti-indesirables, et le modele est de toute façon oblige de declarer
     * {@code corps} — {@code rendre} refuse un modele sans texte brut.
     *
     * <h3>{@code multipart = true}, impose par le logo</h3>
     *
     * Une image integree demande un {@code multipart/related} autour de
     * l'alternative. {@code MimeMessageHelper} le construit a condition d'etre
     * cree en mode multipart, et il faut le decider a la construction : le
     * passer a {@code false} ferait echouer {@code addInline} plus bas, sur une
     * exception qui ne nomme pas la cause.
     */
    private void envoyerAvecVarianteHtml(MessageARemettre message,
                                         JavaMailSender expediteurChoisi) throws Exception {
        var mime = expediteurChoisi.createMimeMessage();
        var assistant = new MimeMessageHelper(mime, true, "UTF-8");

        assistant.setFrom(adresseExpediteur);
        if (adresseReponse != null && !adresseReponse.isBlank()) {
            assistant.setReplyTo(adresseReponse);
        }
        assistant.setTo(message.coordonnee());
        assistant.setSubject(message.sujet());
        assistant.setText(message.corps(), message.corpsHtml());

        integrerLeLogo(assistant, message.corpsHtml());
        expediteurChoisi.send(mime);
    }

    /**
     * Ajoute le logo SI le gabarit le cite et SI le fichier existe.
     *
     * <p>Les deux conditions, et aucune n'empeche l'envoi :
     *
     * <ul>
     *   <li>le gabarit ne cite pas {@code cid:logo} : la piece jointe ferait
     *       grossir le message pour rien, et un courriel inutilement lourd
     *       attire les filtres ;</li>
     *   <li>le fichier manque ou n'est pas lisible : le courriel part sans
     *       logo. Refuser l'envoi d'un code de verification parce qu'une image
     *       manque serait hors de proportion.</li>
     * </ul>
     *
     * <p>L'echec est journalise en WARN et non avale en silence : une image
     * absente sur tous les courriels se remarque tard, et le journal est le
     * seul endroit qui dira pourquoi.
     */
    private void integrerLeLogo(MimeMessageHelper assistant, String corpsHtml) {
        if (cheminDuLogo == null || cheminDuLogo.isBlank()
                || !corpsHtml.contains("cid:" + IDENTIFIANT_DU_LOGO)) {
            return;
        }
        Resource fichier = CHARGEUR.getResource(cheminDuLogo);
        if (!fichier.exists() || !fichier.isReadable()) {
            log.warn("Logo integre illisible a {} : le courriel part sans image",
                    cheminDuLogo);
            return;
        }
        try {
            assistant.addInline(IDENTIFIANT_DU_LOGO, fichier);
        } catch (jakarta.mail.MessagingException refus) {
            log.warn("Logo integre non ajoute ({}) : le courriel part sans image",
                    refus.getClass().getSimpleName());
        }
    }

    /**
     * L'identifiant de contenu du logo, cite par les gabarits.
     *
     * <p>Nomme une fois : le gabarit ecrit {@code cid:logo} et ce code cherche
     * la meme chaine. Les deux divergeraient au premier renommage, et le
     * symptome serait une image cassee dans tous les courriels.
     */
    private static final String IDENTIFIANT_DU_LOGO = "logo";

    /**
     * Le chargeur de ressources, sans etat et partage.
     *
     * <p>{@code DefaultResourceLoader} resout {@code classpath:},
     * {@code file:} et un chemin nu. L'instancier ici plutot que d'injecter
     * {@code ResourceLoader} evite d'ajouter une dependance au constructeur
     * pour un objet qui n'a ni etat ni configuration.
     */
    private static final ResourceLoader CHARGEUR = new DefaultResourceLoader();

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

        return tenter(message, expediteurPour(message), disjoncteurPour(message))
                // LE REPLI DES CRITIQUES SUR LE COMPTE ORDINAIRE.
                //
                // Pose avant les deux rattrapages ci-dessous : il doit voir
                // l'echec AVANT qu'il ne soit converti en Resultat.
                .onErrorResume(premierEchec -> replierSurLOrdinaire(message, premierEchec))
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
