package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.ContexteDeSecurite;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.IAlerteDeSecuriteService;
import com.abdatytch.auth_service.service.IEmailService;
import com.abdatytch.auth_service.service.ISmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.format.DateTimeFormatter;
import java.util.List;

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
     * Horodatage lisible par un humain, et non ISO-8601.
     *
     * <p>« 2026-10-14T16:12:03.472 » dans un courriel se lit comme une trace
     * technique. La personne doit pouvoir dire « non, a cette heure-la je
     * dormais », ce qui demande une date qu'elle lise sans effort.
     */
    private static final DateTimeFormatter LISIBLE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'a' HH'h'mm");

    private final UserRepository userRepository;
    private final IEmailService emailService;
    private final ISmsService smsService;
    private final AuthMetrics authMetrics;

    @Override
    public Mono<Void> motDePasseModifie(User user, ContexteDeSecurite contexte) {
        // Le courriel d'abord : rien n'a change dans les coordonnees, donc le
        // canal le plus riche et le moins couteux passe devant.
        return prevenir(user, EVENEMENT_MOT_DE_PASSE,
                AppConstants.ALERTE_SUJET_MOT_DE_PASSE,
                corps(AppConstants.ALERTE_CORPS_MOT_DE_PASSE, contexte),
                AppConstants.ALERTE_SMS_MOT_DE_PASSE,
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
                AppConstants.ALERTE_SUJET_ADRESSE,
                corps(AppConstants.ALERTE_CORPS_ADRESSE, contexte),
                AppConstants.ALERTE_SMS_ADRESSE,
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
                AppConstants.ALERTE_SUJET_NUMERO,
                corps(AppConstants.ALERTE_CORPS_NUMERO, contexte),
                AppConstants.ALERTE_SMS_NUMERO,
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
                AppConstants.ALERTE_SUJET_CONNEXION,
                corps(AppConstants.ALERTE_CORPS_CONNEXION, contexte),
                null,
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

    /** Un canal et son destinataire. Destinataire nul : canal inutilisable. */
    private record Destination(String canal, String destinataire) {

        static final String COURRIEL = "courriel";
        static final String SMS = "sms";

        static Destination courriel(String adresse) {
            return new Destination(COURRIEL, adresse);
        }

        static Destination sms(String numero) {
            return new Destination(SMS, numero);
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
    private Mono<Void> prevenir(User user, String evenement, String sujet, String corps,
                                String messageCourt, List<Destination> ordre) {
        for (Destination destination : ordre) {
            if (!destination.utilisable()) {
                continue;
            }
            if (Destination.COURRIEL.equals(destination.canal())) {
                return envoye(emailService.envoyerAvisDeSecurite(
                        destination.destinataire(), sujet, corps), evenement, user);
            }
            if (messageCourt == null) {
                // Pas de texte court pour ce canal : il n'est pas utilisable
                // ici, et le sauter vaut mieux qu'envoyer un SMS tronque.
                continue;
            }
            return envoye(smsService.sendSms(destination.destinataire(), messageCourt),
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

    private Mono<Void> envoye(Mono<Void> envoi, String evenement, User user) {
        return envoi
                .doOnSuccess(unused -> authMetrics.recordAlerteDeSecurite(
                        evenement, RESULTAT_ENVOYEE))
                .onErrorResume(erreur -> echecAvale(evenement, user, erreur));
    }

    private Mono<Void> echecAvale(String evenement, User user, Throwable erreur) {
        // Le TYPE de l'erreur, jamais son message : un message SMTP peut
        // contenir l'adresse complete du destinataire, et les journaux n'ont
        // pas a la porter.
        log.error(AppConstants.LOG_ERROR_ALERTE_NON_ENVOYEE,
                evenement, user.getId(), erreur.getClass().getSimpleName());
        authMetrics.recordAlerteDeSecurite(evenement, RESULTAT_ECHEC);
        return Mono.empty();
    }

    /**
     * Assemble le corps de l'avis avec les circonstances.
     *
     * <p>Le lieu est omis quand il est inconnu, et non remplace par « Unknown » :
     * « depuis Unknown » se lit comme une anomalie du systeme plutot que comme
     * une information manquante, et detourne l'attention de ce que l'avis
     * annonce.
     */
    private static String corps(String entete, ContexteDeSecurite contexte) {
        StringBuilder texte = new StringBuilder(entete);
        texte.append(AppConstants.ALERTE_SEPARATEUR);
        texte.append(String.format(AppConstants.ALERTE_LIGNE_QUAND,
                contexte.getQuand().format(LISIBLE)));

        String lieu = contexte.lieu();
        if (lieu != null) {
            texte.append(String.format(AppConstants.ALERTE_LIGNE_LIEU, lieu));
        }
        if (contexte.getIpAddress() != null) {
            texte.append(String.format(AppConstants.ALERTE_LIGNE_ADRESSE_IP,
                    contexte.getIpAddress()));
        }
        if (contexte.getUserAgent() != null) {
            texte.append(String.format(AppConstants.ALERTE_LIGNE_APPAREIL,
                    contexte.getUserAgent()));
        }
        texte.append(AppConstants.ALERTE_CONDUITE_A_TENIR);
        return texte.toString();
    }

    private static String adresseVerifieeDe(User user) {
        return Boolean.TRUE.equals(user.getIsEmailVerified()) ? user.getEmail() : null;
    }

    private static String telephoneVerifieDe(User user) {
        return Boolean.TRUE.equals(user.getIsPhoneVerified()) ? user.getPhoneNumber() : null;
    }
}
