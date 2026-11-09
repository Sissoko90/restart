package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAlerteDeSecuriteService;
import com.abdatytch.auth_service.service.IMessagerieSortante;
import com.abdatytch.commons.notification.CanalDeNotification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Alertes de sécurité : voir {@link IAlerteDeSecuriteService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlerteDeSecuriteServiceImpl implements IAlerteDeSecuriteService {

    /** Evenements. Valeurs BORNEES : etiquettes Prometheus. */
    static final String EVENEMENT_MOT_DE_PASSE = "mot_de_passe_modifie";
    static final String EVENEMENT_ADRESSE = "adresse_modifiee";
    static final String EVENEMENT_NUMERO = "numero_modifie";
    static final String EVENEMENT_CONNEXION = "nouvelle_connexion";

    /** Resultats. Valeurs BORNEES. */
    static final String RESULTAT_ENVOYEE = "envoyee";
    static final String RESULTAT_SANS_DESTINATAIRE = "sans_destinataire";
    static final String RESULTAT_DESACTIVEE = "desactivee";
    static final String RESULTAT_ECHEC = "echec";

    /**
     * L'envoi a eu lieu et a ete REFUSE : ni panne, ni absence de coordonnee.
     *
     * <p>Canal coupe, numero hors liste autorisee, plafond journalier atteint,
     * modele sans variante pour ce canal. La personne n'a pas ete prevenue, et
     * c'est ce qui importe pour la supervision — mais la conduite a tenir n'est
     * pas celle d'une panne, d'ou une valeur distincte.
     *
     * <p>Ce cas etait compte {@code envoyee}. Voir {@code envoye}.
     */
    static final String RESULTAT_NON_REMISE = "non_remise";

    /**
     * Horodatage lisible par un humain, et non ISO-8601.
     *
     * <p>« 2026-10-14T16:12:03.472 » dans un courriel se lit comme une trace
     * technique. La personne doit pouvoir dire « non, a cette heure-la je
     * dormais », ce qui demande une date qu'elle lise sans effort.
     */
    private static final DateTimeFormatter LISIBLE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a' HH'h'mm");

    private final UserRepository userRepository;

    /**
     * UNE dependance d'envoi, et il y en avait deux.
     *
     * <p>{@code IEmailService} et {@code ISmsService} differaient par le CANAL,
     * qui est une donnee. Les deux ont ete remplacees par
     * {@link IMessagerieSortante}, qui le prend en parametre.
     */
    private final IMessagerieSortante messagerie;

    private final AuthMetrics authMetrics;

    @Override
    public Mono<Void> motDePasseModifie(User user, ContexteDeSecurite contexte) {
        // Le courriel d'abord : rien n'a change dans les coordonnees, donc le
        // canal le plus riche et le moins couteux passe devant.
        return prevenir(user, EVENEMENT_MOT_DE_PASSE,
                AppConstants.MODELE_ALERTE_MOT_DE_PASSE,
                circonstances(contexte),
                List.of(Destination.courriel(adresseVerifieeDe(user)),
                        Destination.sms(telephoneVerifieDe(user))));
    }

    @Override
    public Mono<Void> adresseModifiee(User user, String ancienneAdresse,
                                      boolean ancienneEtaitVerifiee,
                                      ContexteDeSecurite contexte) {
        // L'ANCIENNE adresse, passee en parametre, et jamais user.getEmail() :
        // l'entite porte deja la nouvelle a cet instant, et l'y relire
        // enverrait l'alerte a celui qui vient de la choisir.
        //
        // Et seulement si elle avait ete confirmee : une adresse que personne
        // n'a prouvee n'appartient pas forcement au titulaire — elle peut avoir
        // ete posee par l'attaquant au tour precedent, ce qui ferait de cette
        // alerte un accuse de reception a son intention.
        String destinataire = ancienneEtaitVerifiee ? ancienneAdresse : null;

        // L'ANCIENNE adresse d'abord : c'est la coordonnee qui disparait.
        return prevenir(user, EVENEMENT_ADRESSE,
                AppConstants.MODELE_ALERTE_ADRESSE,
                circonstances(contexte),
                List.of(Destination.courriel(destinataire),
                        Destination.sms(telephoneVerifieDe(user))));
    }

    @Override
    public Mono<Void> numeroModifie(User user, String ancienNumero,
                                    boolean ancienEtaitVerifie,
                                    ContexteDeSecurite contexte) {
        // L'ANCIEN numero, passe en parametre, et jamais user.getPhoneNumber() :
        // l'entite porte deja le nouveau a cet instant.
        String destinataire = ancienEtaitVerifie ? ancienNumero : null;

        // Le SMS D'ABORD, et c'est l'inverse des deux autres alertes.
        //
        // L'ancien numero est la coordonnee qui disparait : c'est lui qu'il faut
        // prevenir. Envoyer d'abord au courriel — qui, lui, n'a pas change —
        // laisserait le titulaire du numero perdu sans rien savoir, alors que
        // c'est precisement la personne qui vient d'etre depossedee.
        //
        // Le courriel reste en repli : un compte dont l'ancien numero n'etait
        // pas verifie doit quand meme apprendre le changement.
        return prevenir(user, EVENEMENT_NUMERO,
                AppConstants.MODELE_ALERTE_NUMERO,
                circonstances(contexte),
                List.of(Destination.sms(destinataire),
                        Destination.courriel(adresseVerifieeDe(user))));
    }

    @Override
    public Mono<Void> nouvelleConnexion(User user, ContexteDeSecurite contexte) {
        if (!Boolean.TRUE.equals(user.getAlerteNouvelleConnexion())) {
            // Compte a part d'une absence de destinataire : « desactivee » est
            // un choix de la personne, « sans_destinataire » est un compte
            // qu'aucune alerte ne peut atteindre. Confondus, la courbe ne
            // distinguerait plus un reglage d'un trou.
            authMetrics.recordAlerteDeSecurite(EVENEMENT_CONNEXION, RESULTAT_DESACTIVEE);
            return Mono.empty();
        }
        // Aucun repli par SMS ici, contrairement aux deux autres.
        //
        // La personne a demande une alerte PAR COURRIEL, et une connexion est
        // frequente : basculer en SMS enverrait un message payant a chaque
        // connexion, pour un reglage dont elle n'a pas demande ce canal.
        return prevenir(user, EVENEMENT_CONNEXION,
                AppConstants.MODELE_ALERTE_CONNEXION,
                circonstances(contexte),
                List.of(Destination.courriel(adresseVerifieeDe(user))));
    }

    @Override
    public Mono<Boolean> changerLalerteDeConnexion(String userId, boolean actif) {
        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                .flatMap(user -> {
                    if (actif && !Boolean.TRUE.equals(user.getIsEmailVerified())) {
                        // REFUS, et non une acceptation silencieuse.
                        //
                        // Un reglage affiche comme actif qui n'enverrait jamais
                        // rien est la pire des deux reponses : la personne se
                        // croirait prevenue de chaque connexion, et ne
                        // chercherait donc pas d'autre moyen de surveiller son
                        // compte.
                        log.info(AppConstants.LOG_INFO_ALERTE_SANS_ADRESSE, userId);
                        return Mono.error(new AuthException(ErrorCode.ADRESSE_NON_VERIFIEE));
                    }
                    user.setAlerteNouvelleConnexion(actif);
                    return userRepository.save(user)
                            .doOnSuccess(enregistre -> log.info(
                                    AppConstants.LOG_INFO_ALERTE_DE_CONNEXION,
                                    actif, enregistre.getId()))
                            .thenReturn(actif);
                });
    }

    /**
     * Un canal et son destinataire. Destinataire nul : canal inutilisable.
     *
     * <p>Le canal est desormais {@link CanalDeNotification}, le vocabulaire
     * partage de la plateforme, et non deux chaines locales. Elles servaient a
     * choisir entre deux services d'envoi ; il n'y en a plus qu'un, qui prend le
     * canal en donnee.
     */
    private record Destination(CanalDeNotification canal, String destinataire) {

        static Destination courriel(String adresse) {
            return new Destination(CanalDeNotification.COURRIEL, adresse);
        }

        static Destination sms(String numero) {
            return new Destination(CanalDeNotification.SMS, numero);
        }

        boolean utilisable() {
            return destinataire != null;
        }
    }

    /**
     * Envoie l'avis au PREMIER canal utilisable de la liste.
     *
     * <h2>Pourquoi l'ordre est donne par l'appelant</h2>
     *
     * Il depend de ce qui a change. Pour un mot de passe, le courriel passe
     * devant : il est gratuit et porte un texte complet — date, lieu, appareil,
     * conduite a tenir — la ou 160 caracteres de SMS ne racontent rien.
     *
     * <p>Mais pour un <b>changement de numero</b>, l'ancien numero est la
     * coordonnee qui disparait : c'est LUI qu'il faut prevenir, donc le SMS
     * passe devant. Un ordre fixe dans cette methode aurait envoye l'avis au
     * courriel — qui, lui, n'a pas change — et le titulaire du numero perdu
     * n'aurait rien su.
     *
     * <h2>Pourquoi UN seul canal, et non tous</h2>
     *
     * Envoyer les deux systematiquement doublerait la facture SMS sans rien
     * apprendre de plus. Le repli n'est pas un confort : c'est ce qui evite
     * qu'un compte sans adresse verifiee — inscrit par telephone, cas courant
     * ici — ne soit jamais prevenu de rien.
     *
     * <h2>Une alerte ne fait jamais echouer l'operation</h2>
     *
     * L'operation signalee est deja enregistree. Une panne de SMTP ne doit pas
     * la faire paraitre echouee : la personne croirait son mot de passe
     * inchange alors qu'il a change, et reessaierait avec l'ancien.
     *
     * <p>L'erreur est donc avalee — mais COMPTEE. Un envoi avale et seulement
     * journalise est une protection qui s'eteint sans bruit.
     */
    private Mono<Void> prevenir(User user, String evenement, String modele,
                                String circonstances, List<Destination> ordre) {
        for (Destination destination : ordre) {
            if (!destination.utilisable()) {
                continue;
            }
            // Un seul appel pour les deux canaux, et le canal est une donnee.
            //
            // LA GARDE « messageCourt == null » A DISPARU, et elle ne pouvait
            // jamais se declencher : le seul evenement sans texte court —
            // nouvelle connexion — ne propose deja que le courriel dans son
            // ordre de canaux. Du code defensif qui n'a jamais rien defendu.
            //
            // La question qu'elle posait est d'ailleurs desormais celle du
            // MODELE : notification-service ne replie plus un SMS sur le texte
            // commun, donc un modele sans variante courte refuse l'envoi au
            // lieu d'expedier un corps de courriel tronque. La regle est a un
            // seul endroit, chez celui qui detient les textes.
            return envoye(messagerie.envoyer(IMessagerieSortante.Demande.critique(
                            destination.canal(), destination.destinataire(), modele,
                            Map.of(AppConstants.VARIABLE_CIRCONSTANCES, circonstances))),
                    evenement, user);
        }
        // Aucune coordonnee verifiee. Ce n'est pas une panne, et c'est compte a
        // part : une courbe qui monte ici signale des comptes qu'aucune alerte
        // ne protege — un etat a corriger par la verification des contacts, pas
        // par une reprise d'envoi.
        log.warn(AppConstants.LOG_WARN_ALERTE_SANS_DESTINATAIRE, evenement, user.getId());
        authMetrics.recordAlerteDeSecurite(evenement, RESULTAT_SANS_DESTINATAIRE);
        return Mono.empty();
    }

    /**
     * Compte le sort de l'envoi, et la metrique disait FAUX.
     *
     * <h2>Le defaut corrige</h2>
     *
     * Cette methode recevait un {@code Mono<Void>} et posait
     * {@code RESULTAT_ENVOYEE} dans un {@code doOnSuccess}. Or
     * {@code doOnSuccess} se declenche <b>aussi</b> sur une terminaison VIDE —
     * et l'ancien client SMS se terminait precisement vide quand rien n'etait
     * parti : {@code sms.enabled} faux, repli du disjoncteur, numero hors liste
     * autorisee, plafond journalier atteint.
     *
     * <p>La metrique comptait donc des alertes « envoyees » qui ne partaient
     * pas. Et la regle Prometheus {@code AlertesDeSecuriteNonEnvoyees}, dont le
     * commentaire annonce « sans cette regle l'echec serait avale ET
     * invisible », etait aveugle a ce cas : elle ne surveille que
     * {@code resultat="echec"}, c'est-a-dire une EXCEPTION, alors que le client
     * les avalait toutes.
     *
     * <p>Avec un {@code Mono<Boolean>}, le refus se distingue du succes. Il est
     * compte {@code non_remise} — ni {@code echec}, qui est une panne, ni
     * {@code sans_destinataire}, qui est un compte sans coordonnee. Les trois
     * appellent des conduites differentes, et les confondre rendrait la courbe
     * illisible : c'est la raison pour laquelle {@code desactivee} existe deja a
     * part.
     */
    private Mono<Void> envoye(Mono<Boolean> envoi, String evenement, User user) {
        return envoi
                .doOnNext(remis -> {
                    if (Boolean.TRUE.equals(remis)) {
                        authMetrics.recordAlerteDeSecurite(evenement, RESULTAT_ENVOYEE);
                        return;
                    }
                    authMetrics.recordAlerteDeSecurite(evenement, RESULTAT_NON_REMISE);
                    // Le motif precis est journalise par le client, avec le
                    // canal. Ici l'evenement et le compte seulement, et le
                    // compte par son identifiant — jamais sa coordonnee.
                    log.warn(AppConstants.LOG_WARN_ALERTE_NON_REMISE,
                            evenement, user.getId());
                })
                .onErrorResume(erreur -> echecAvale(evenement, user, erreur))
                .then();
    }

    private Mono<Boolean> echecAvale(String evenement, User user, Throwable erreur) {
        // Le TYPE de l'erreur, jamais son message : un message SMTP peut
        // contenir l'adresse complete du destinataire, et les journaux n'ont
        // pas a la porter.
        log.error(AppConstants.LOG_ERROR_ALERTE_NON_ENVOYEE,
                evenement, user.getId(), erreur.getClass().getSimpleName());
        authMetrics.recordAlerteDeSecurite(evenement, RESULTAT_ECHEC);
        return Mono.empty();
    }

    /**
     * Assemble les CIRCONSTANCES. Plus le corps du message.
     *
     * <h2>Ce qui est parti, et ce qui reste</h2>
     *
     * Cette methode construisait le texte entier : l'entete, les circonstances,
     * puis la conduite a tenir. Les deux textes sont desormais dans le modele,
     * chez notification-service, et modifiables a chaud. Il ne reste ici que
     * l'assemblage des FAITS, que ce service est seul a connaitre.
     *
     * <h2>Pourquoi l'assemblage reste ici, et non quatre variables</h2>
     *
     * Parce que la structure est CONDITIONNELLE : le lieu, l'adresse IP et
     * l'appareil sont parfois inconnus, et la ligne doit alors disparaitre.
     *
     * <p>Avec quatre variables, un marqueur sans valeur resterait visible — le
     * moteur laisse {@code {lieu}} en place plutot que d'ecrire « null », ce qui
     * est le bon choix pour diagnostiquer mais le mauvais pour un destinataire —
     * et une valeur vide laisserait une ligne « Lieu : » orpheline. Le moteur ne
     * porte volontairement aucune conditionnelle : elle supposerait l'evaluation
     * d'expressions, donc une surface d'injection sur un texte desormais
     * modifiable en base.
     *
     * <p>Le lieu est omis quand il est inconnu, et non remplace par « Unknown » :
     * « depuis Unknown » se lit comme une anomalie du systeme plutot que comme
     * une information manquante, et detourne l'attention de ce que l'avis
     * annonce.
     */
    private static String circonstances(ContexteDeSecurite contexte) {
        StringBuilder faits = new StringBuilder();
        faits.append(String.format(AppConstants.ALERTE_LIGNE_QUAND,
                contexte.getQuand().format(LISIBLE)));

        String lieu = contexte.lieu();
        if (lieu != null) {
            faits.append(String.format(AppConstants.ALERTE_LIGNE_LIEU, lieu));
        }
        if (contexte.getIpAddress() != null) {
            faits.append(String.format(AppConstants.ALERTE_LIGNE_ADRESSE_IP,
                    contexte.getIpAddress()));
        }
        if (contexte.getUserAgent() != null) {
            faits.append(String.format(AppConstants.ALERTE_LIGNE_APPAREIL,
                    contexte.getUserAgent()));
        }
        return faits.toString();
    }

    private static String adresseVerifieeDe(User user) {
        return Boolean.TRUE.equals(user.getIsEmailVerified()) ? user.getEmail() : null;
    }

    private static String telephoneVerifieDe(User user) {
        return Boolean.TRUE.equals(user.getIsPhoneVerified()) ? user.getPhoneNumber() : null;
    }
}
