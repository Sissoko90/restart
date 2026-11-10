package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.entities.JetonDAppareil;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.notification_service.repositories.JetonDAppareilRepository;
import com.abdatytch.notification_service.service.CanalDeRemise;
import com.abdatytch.notification_service.service.push.IClientPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Remise par notification push, via Firebase Cloud Messaging.
 *
 * <h2>Ce canal lit ses propres coordonnees, et c'est le changement de fond</h2>
 *
 * Les autres canaux a coordonnee recoivent une adresse ou un numero resolu chez
 * user-service. Le push, non : un <b>jeton d'appareil</b> n'est pas une
 * coordonnee de profil. Il appartient a un telephone, pas a une personne ; il
 * est renouvele par le systeme sans que le compte change ; et une meme personne
 * en a autant qu'elle possede d'appareils.
 *
 * <p>Les jetons vivent donc dans la table {@code jeton_d_appareil} de CE
 * service, et ce canal les lit lui-meme par l'identifiant du porteur.
 *
 * <p>Jusqu'ici, {@code ResolveurDeCoordonnees} lui passait le NUMERO DE
 * TELEPHONE faute de mieux — ce que son propre commentaire admettait : « PUSH
 * utilise le numero faute de jeton d'appareil ; le canal refusera, et c'est
 * exact ». Le canal refusait donc toujours, et chaque push payait un appel a
 * user-service pour rien.
 *
 * <p>PUSH a par consequent rejoint {@code SANS_COORDONNEE} dans le resolveur.
 * Consequence heureuse : <b>un push n'echoue plus parce que user-service est
 * indisponible</b>.
 *
 * <h2>Les jetons morts sont SUPPRIMES, pas reessayes</h2>
 *
 * FCM distingue deux familles d'echec, et les confondre coute cher dans les
 * deux sens :
 *
 * <ul>
 *   <li>{@code UNREGISTERED} et {@code INVALID_ARGUMENT} sont DEFINITIFS —
 *       application desinstallee, jeton remplace. Les reessayer pousse
 *       indefiniment vers un appareil disparu : un appel reseau par
 *       notification, et un echec perpetuel dans chaque compte rendu ;</li>
 *   <li>{@code UNAVAILABLE}, {@code INTERNAL}, {@code QUOTA_EXCEEDED} sont
 *       PASSAGERS. Les traiter comme definitifs effacerait la base d'appareils
 *       de toute la plateforme sur une panne FCM d'une minute.</li>
 * </ul>
 *
 * <p>Le tri est fait par {@code ClientPushFcm}, qui seul voit les codes. Ce
 * canal se contente de retirer ce qu'on lui designe comme mort.
 *
 * <h2>Un seul appareil joint suffit a dire REMIS</h2>
 *
 * Et non « tous ». Une personne qui a trois telephones dont un efface doit
 * etre consideree comme prevenue : exiger l'unanimite ferait rejouer la
 * notification sur ses appareils vivants, donc la lui montrer plusieurs fois,
 * pour un appareil qu'elle a jete.
 */
@Component
@RefreshScope
public class CanalPush implements CanalDeRemise {

    private static final Logger log = LoggerFactory.getLogger(CanalPush.class);

    private final JetonDAppareilRepository appareils;
    private final IClientPush client;

    /**
     * Interrupteur d'exploitation, distinct de la presence des identifiants.
     *
     * <p>Les deux conditions sont necessaires et disent des choses differentes :
     * {@code enabled} est un CHOIX — couper le push sur un environnement — et
     * le client configure est un FAIT. Un seul reglage pour les deux rendrait
     * impossible de distinguer « on ne veut pas » de « on ne peut pas », et le
     * depot a deja paye deux interrupteurs pour une meme question du cote SMS.
     */
    @Value("${notification.push.enabled:false}")
    private boolean actif;

    /**
     * Le disjoncteur de FCM.
     *
     * <h3>Ce qu'il protege</h3>
     *
     * {@code sendEachForMulticast} part vers l'API de Google. Si elle est
     * injoignable, l'appel n'echoue pas vite : il expire. Un lot de 500 jetons
     * par notification, multiplie par les demandes en attente, et le service
     * passe son temps a attendre un service mort.
     *
     * <h3>Pourquoi il ne remplace pas le tri des codes d'erreur</h3>
     *
     * {@code ClientPushFcm.depouiller} distingue deja un jeton MORT d'une panne
     * PASSAGERE, et c'est une autre question : elle decide s'il faut effacer le
     * jeton. Le disjoncteur, lui, decide s'il faut encore appeler. Confondre
     * les deux effacerait la base d'appareils a la premiere panne de Google.
     */
    static final String DISJONCTEUR = "fcm";

    private final IDisjoncteurSortant disjoncteur;

    public CanalPush(JetonDAppareilRepository appareils, IClientPush client,
                     IDisjoncteurSortant disjoncteur) {
        this.appareils = appareils;
        this.client = client;
        this.disjoncteur = disjoncteur;
        disjoncteur.declarer(DISJONCTEUR);
    }

    @jakarta.annotation.PostConstruct
    void annoncerLEtat() {
        if (!actif) {
            log.info("Canal PUSH : desactive par notification.push.enabled — "
                    + "toute demande sera refusee, et comptee");
            return;
        }
        if (!client.estConfigure()) {
            // ERROR : le canal est VOULU actif, et il ne peut pas servir. C'est
            // une erreur de deploiement, pas un choix.
            log.error("Canal PUSH active mais client FCM non configure : toute "
                    + "demande sera refusee. Renseigner "
                    + "FIREBASE_CREDENTIALS_PATH.");
            return;
        }
        log.info("Canal PUSH : ACTIF");
    }

    @Override
    public CanalDeNotification canal() {
        return CanalDeNotification.PUSH;
    }

    @Override
    public boolean estActif() {
        return actif && client.estConfigure();
    }

    @Override
    public Mono<Resultat> remettre(MessageARemettre message) {
        if (!estActif()) {
            return Mono.just(Resultat.refuse("canal PUSH non configure"));
        }
        if (message.destinataireId() == null || message.destinataireId().isBlank()) {
            // Sans identifiant de porteur, aucun jeton a retrouver. C'est le cas
            // d'un message transactionnel direct : /internal/message refuse
            // d'ailleurs le canal PUSH en amont, par sa liste blanche.
            return Mono.just(Resultat.refuse("aucun destinataire pour retrouver "
                    + "ses appareils"));
        }

        return appareils.parDestinataire(message.destinataireId())
                .map(JetonDAppareil::getJeton)
                .collectList()
                .flatMap(jetons -> jetons.isEmpty()
                        // REFUSE et non ECHOUE : aucun appareil enregistre ne se
                        // repare en reessayant. Un ECHOUE ferait rejouer ce
                        // push toutes les trois minutes pour une personne qui
                        // n'a simplement pas installe l'application.
                        ? Mono.just(Resultat.refuse("aucun appareil enregistre"))
                        : pousserPuisPurger(jetons, message))
                .onErrorResume(panne -> {
                    // La lecture des jetons a echoue : c'est la BASE, pas FCM.
                    // ECHOUE, donc reessayable.
                    log.warn("Lecture des appareils impossible : {}",
                            panne.getClass().getSimpleName());
                    return Mono.just(Resultat.echoue(panne.getClass().getSimpleName()));
                });
    }

    private Mono<Resultat> pousserPuisPurger(List<String> jetons,
                                             MessageARemettre message) {
        return disjoncteur.proteger(DISJONCTEUR,
                        client.pousser(jetons, message.sujet(), message.corps()))
                .flatMap(resultat -> purger(resultat.jetonsInvalides())
                        .thenReturn(resultat))
                .map(resultat -> resultat.aAbouti()
                        ? Resultat.remis()
                        // ECHOUE et non REFUSE : les codes passagers de FCM —
                        // indisponibilite, quota — valent d'etre reessayes, et
                        // les jetons definitivement morts viennent d'etre
                        // retires, donc la reprise ne les reverra pas.
                        : Resultat.echoue(resultat.motif()))
                // Le refus du disjoncteur est rattrape ICI, et non par le
                // gestionnaire de `remettre`.
                //
                // Celui-la journalise « Lecture des appareils impossible » :
                // il parle de la BASE. Laisser une ouverture de disjoncteur
                // remonter jusqu'a lui ferait chercher une panne de base de
                // donnees pour une panne de FCM — exactement le genre de
                // journal qui coute une heure.
                .onErrorResume(panne -> {
                    log.warn("Envoi push impossible : {}", panne.getClass().getSimpleName());
                    return Mono.just(Resultat.echoue(
                            "FCM : " + panne.getClass().getSimpleName()));
                });
    }

    /**
     * Retire les jetons que FCM declare injoignables.
     *
     * <p>La purge ne doit JAMAIS faire echouer la remise : les autres appareils
     * ont peut-etre recu le message, et un echec de nettoyage n'annule pas un
     * envoi reussi. L'erreur est donc avalee — mais journalisee, parce qu'une
     * purge qui ne passe plus laisse grossir la table sans borne.
     */
    private Mono<Void> purger(List<String> morts) {
        if (morts.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(morts)
                .concatMap(appareils::retirerJetonInvalide)
                .onErrorContinue((erreur, jeton) -> log.warn(
                        "Retrait d'un jeton invalide impossible : {}",
                        erreur.getClass().getSimpleName()))
                .then();
    }
}
