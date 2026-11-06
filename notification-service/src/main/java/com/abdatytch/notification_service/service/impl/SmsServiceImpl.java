package com.abdatytch.notification_service.service.impl;

import com.abdatytch.notification_service.constants.ServiceConstants;
import com.abdatytch.notification_service.service.ISmsService;
import com.abdatytch.notification_service.service.sms.AfrikSmsProvider;
import com.abdatytch.notification_service.service.sms.ISmsProvider;
import com.abdatytch.notification_service.service.sms.NumerosAutorisesSms;
import com.abdatytch.notification_service.service.sms.PlafondJournalierSms;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Envoi des SMS de la plateforme, indépendamment du transporteur.
 *
 * <p>Ce service ne connaît aucune API de fournisseur : il choisit un
 * {@link ISmsProvider} d'après {@code sms.provider} et lui délègue. AfrikSMS est
 * le choix par défaut ; Twilio reste disponible et s'active par
 * {@code SMS_PROVIDER=twilio}, sans recompilation.
 *
 * <h2>Deux interrupteurs, qui ne disent pas la même chose</h2>
 *
 * <ul>
 *   <li>{@code sms.enabled} coupe l'envoi : utile en développement, ou le temps
 *       d'une panne de fournisseur ;</li>
 *   <li>{@code auth.dev.log-verification-codes} affiche le code en clair dans
 *       les journaux, et vaut <b>faux</b> par défaut.</li>
 * </ul>
 *
 * Les deux étaient confondus : l'affichage du code était conditionné à
 * {@code !sms.enabled}. Or couper les SMS est une décision d'<b>exploitation</b>,
 * qu'on prend aussi en production — et tous les codes de vérification partaient
 * alors dans les journaux sans que personne ne l'ait demandé. Voir
 * {@link #journaliserCodeEnDeveloppement}.
 *
 * <h2>Pourquoi le choix échoue au démarrage et non à l'envoi</h2>
 *
 * Les deux fournisseurs sont des beans en permanence, et la sélection se fait
 * par nom dans le constructeur. Un {@code SMS_PROVIDER} mal orthographié
 * empêche donc le service de démarrer, avec un message qui nomme les valeurs
 * acceptées. L'alternative — {@code @ConditionalOnProperty} sur chaque
 * fournisseur — aurait produit un contexte sans aucun transporteur : le service
 * démarrait, et l'envoi de SMS disparaissait en silence jusqu'à ce qu'un
 * utilisateur signale ne pas recevoir son code.
 */
@Service
@RefreshScope
@Slf4j
public class SmsServiceImpl implements ISmsService {

    /**
     * Affiche les codes de vérification en clair dans les journaux.
     *
     * <p>Faux par défaut, et à n'activer qu'en développement local.
     */
    private final boolean journaliserCodes;

    /** Interrupteur d'exploitation : faux coupe tout envoi. */
    private final boolean smsActif;

    private final ISmsProvider fournisseur;

    /**
     * Les deux garde-fous de depense, et ce qui les distingue.
     *
     * <p>{@link NumerosAutorisesSms} protege le <b>developpement</b> : il rend
     * impossible d'envoyer ailleurs que vers les numeros nommes. Inactif par
     * defaut.
     *
     * <p>{@link PlafondJournalierSms} protege la <b>production</b> : il borne ce
     * qu'une boucle fautive peut depenser avant que quiconque s'en apercoive.
     * Actif par defaut.
     *
     * <p>Les deux sont consultes ici, et nulle part ailleurs : c'est le seul
     * point par lequel passe tout envoi, pour les deux fournisseurs. Les placer
     * dans un fournisseur aurait demande de les ecrire deux fois, et AfrikSMS
     * comme Twilio auraient pu diverger.
     */
    private final NumerosAutorisesSms numerosAutorises;

    private final PlafondJournalierSms plafond;

    public SmsServiceImpl(
            List<ISmsProvider> fournisseursDisponibles,
            PlafondJournalierSms plafond,
            @Value("${sms.provider:" + AfrikSmsProvider.NOM + "}") String nomDuFournisseur,
            @Value("${sms.enabled:false}") boolean smsActif,
            @Value("${auth.dev.log-verification-codes:false}") boolean journaliserCodes,
            @Value("${sms.numeros-autorises:}") String listeDeNumeros) {
        this.smsActif = smsActif;
        this.journaliserCodes = journaliserCodes;
        this.fournisseur = selectionner(fournisseursDisponibles, nomDuFournisseur);
        this.plafond = plafond;
        this.numerosAutorises = new NumerosAutorisesSms(listeDeNumeros);
    }

    /**
     * Retient le fournisseur nommé, ou refuse de démarrer.
     *
     * <p>La comparaison ignore la casse et les espaces de bordure : une valeur
     * venue d'un fichier {@code .env} en arrive souvent pourvue.
     *
     * <h3>Un seul réglage désigne le fournisseur</h3>
     *
     * Pas d'interrupteur par fournisseur en plus de ce sélecteur : deux réglages
     * qui disent presque la même chose peuvent se contredire — fournisseur
     * désigné et coupé en même temps — et il faut alors du code pour arbitrer
     * une contradiction que personne n'a voulue. {@code sms.provider} désigne
     * celui qui sert, les autres ne servent pas : c'est suffisant, et il n'y a
     * qu'un endroit à lire pour savoir lequel est actif.
     */
    private static ISmsProvider selectionner(List<ISmsProvider> disponibles, String nomDemande) {
        String demande = nomDemande == null ? "" : nomDemande.trim().toLowerCase(Locale.ROOT);
        return disponibles.stream()
                .filter(f -> f.nom().equals(demande))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "sms.provider = '" + nomDemande + "' ne correspond à aucun "
                        + "fournisseur SMS. Valeurs acceptées : "
                        + disponibles.stream().map(ISmsProvider::nom).sorted().toList()));
    }

    /**
     * Signale, au démarrage, un fournisseur retenu mais non configuré.
     *
     * <p>Sans cet avertissement, le défaut n'apparaîtrait qu'au premier envoi
     * réel — c'est-à-dire à la première inscription d'un utilisateur, sur un
     * environnement que l'on croyait prêt.
     */
    @PostConstruct
    public void verifierLaConfiguration() {
        if (!smsActif) {
            log.warn(ServiceConstants.LOG_WARN_SMS_GLOBALEMENT_DESACTIVE);
            return;
        }
        if (!fournisseur.estConfigure()) {
            log.error(ServiceConstants.LOG_ERROR_SMS_FOURNISSEUR_NON_CONFIGURE, fournisseur.nom());
            return;
        }
        log.info(ServiceConstants.LOG_INFO_SMS_FOURNISSEUR_PRET, fournisseur.nom());
    }

    @Override
    public boolean envoiActif() {
        return smsActif;
    }

    @Override
    public Mono<Boolean> sendSms(String phoneNumber, String message) {
        // Le gabarit annonçait « pour type: {} » et recevait le MESSAGE, donc le
        // code de verification. Le decalage entre ce que le gabarit annonce et
        // ce qu'il recoit explique que la fuite soit passee inaperçue : lire la
        // chaine de format seule ne revele rien.
        log.info(ServiceConstants.LOG_INFO_SENDING_VERIFICATION_SMS, phoneNumber);

        // RIEN n'est parti : `false`, et non une completion vide.
        //
        // Un Mono<Void> vide etait indistinguable d'un envoi reussi, et
        // CanalSms aurait annonce REMIS. Le booleen rend la difference lisible
        // par l'appelant.
        if (!smsActif) {
            log.warn(ServiceConstants.LOG_WARN_SMS_DISABLED, phoneNumber);
            journaliserCodeEnDeveloppement(message);
            return Mono.just(false);
        }

        // Garde-fou de developpement, verifie AVANT le plafond : il ne consulte
        // rien et n'ecrit rien, donc un numero hors liste ne doit meme pas
        // couter une lecture Redis.
        if (!numerosAutorises.autorise(phoneNumber)) {
            log.warn(ServiceConstants.LOG_WARN_SMS_HORS_LISTE, phoneNumber);
            journaliserCodeEnDeveloppement(message);
            return Mono.just(false);
        }

        return plafond.resteDuCredit()
                .flatMap(reste -> {
                    if (!reste) {
                        log.error(ServiceConstants.LOG_ERROR_SMS_PLAFOND_ATTEINT, phoneNumber);
                        // `false` et non une erreur : lever ferait passer une
                        // limite de depense voulue pour une panne du service, et
                        // la reprise reessaierait indefiniment un refus
                        // deliberе.
                        return Mono.just(false);
                    }

                    // UNE seule tentative, et c'est delibere.
                    //
                    // Un SMS remis au fournisseur se paie. Un reessai dont on ne
                    // sait pas si le premier envoi est parti fait payer deux
                    // fois le meme message, et le destinataire le recoit en
                    // double. Un echec n'est pas perdu pour autant :
                    // l'utilisateur redemande un code, ce qui est un reessai
                    // decide par une personne et non par une boucle qui depense.
                    //
                    // Le comptage suit l'envoi et ne le precede pas : ce qui
                    // coute est un message que le fournisseur a ACCEPTE. Compte
                    // avant, une panne de reseau aurait fait baisser le plafond
                    // sans qu'aucun SMS ne parte.
                    return fournisseur.envoyer(phoneNumber, message)
                            .then(plafond.compterUnEnvoi())
                            // `true` seulement apres que l'envoi s'est termine
                            // SANS erreur : une erreur du fournisseur remonte
                            // telle quelle, et c'est voulu — l'appelant doit
                            // pouvoir distinguer « refuse » de « en panne ».
                            .thenReturn(true);
                });
    }

    /**
     * Repli du disjoncteur.
     *
     * <p>Le code n'est <b>pas</b> journalisé ici, même en développement : ce
     * chemin se déclenche pendant un incident, c'est-à-dire précisément quand
     * les journaux sont consultés par le plus de monde, exportés, et joints à un
     * rapport.
     *
     * <p>L'exception reçue n'est pas journalisée non plus, seulement le nom de
     * sa classe. Les fournisseurs la remplacent déjà par un {@code NotificationException}
     * sans cause, mais ce repli reçoit aussi les erreurs du disjoncteur
     * lui-même, et il ne doit pas dépendre de la discipline d'autrui.
     */
    @SuppressWarnings("unused")

    /**
     * Affiche le code en clair, <b>uniquement</b> si on l'a explicitement demandé.
     *
     * <p>Le besoin est celui du développement local : on ne reçoit pas le SMS,
     * donc on lit le code. Ce besoin mérite un interrupteur qui le dit, et qui
     * est faux par défaut — même raisonnement que
     * {@code jwt.dev.key.allow-ephemeral}.
     */
    private void journaliserCodeEnDeveloppement(String message) {
        if (journaliserCodes) {
            log.info(ServiceConstants.LOG_INFO_SMS_DEV_MODE, message);
        }
    }
}
