package com.abdatytch.auth_service.service.sms;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.exception.AuthException;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Envoi de SMS par Twilio.
 *
 * <p>Conservé comme solution de remplacement : AfrikSMS est le fournisseur
 * retenu, mais cette implémentation reste complète et redevient active en
 * posant {@code SMS_PROVIDER=twilio} dans l'environnement. Rien à recompiler.
 *
 * <h2>Le seul appel bloquant assumé du service</h2>
 *
 * Le SDK Twilio est synchrone : {@code Message.creator(...).create()} ouvre une
 * connexion HTTP et attend. Appelé directement depuis une chaîne réactive, il
 * immobiliserait un fil de la boucle d'événements, et {@code BlockHound} ferait
 * échouer le test. Il est donc isolé sur {@code Schedulers.boundedElastic()},
 * le pool prévu pour l'entrée-sortie bloquante. C'est la raison pour laquelle
 * AfrikSMS, qui passe par {@code WebClient}, est préférable : il n'emprunte
 * aucun fil.
 *
 * <h2>Les deux fuites corrigées en déplaçant ce code</h2>
 *
 * L'ancien {@code sendViaTwilio} journalisait le message, donc le code à usage
 * unique, deux fois par envoi :
 *
 * <pre>{@code
 * log.info(LOG_INFO_SENDING_VERIFICATION_SMS, phoneNumber, message);
 * log.debug(LOG_DEBUG_CODE_VALIDATION, message, phoneNumber);
 * }</pre>
 *
 * Les deux avaient survécu au balayage qui a trouvé dix autres sites, parce que
 * la variable s'appelle {@code message} — un nom trop banal pour figurer dans
 * la liste des mots surveillés. Mesure faite depuis : l'ajouter ne révèle que
 * ces trois sites-là dans tout le dépôt, dont un déjà toléré. Le mot est donc
 * désormais surveillé, et ces deux appels n'existent plus.
 *
 * <p>Le gabarit du premier annonçait « pour type: {} » et recevait le message :
 * lire la chaîne de format seule ne révélait rien. C'est l'argument qu'il faut
 * regarder.
 */
@Component
@Slf4j
public class TwilioSmsProvider implements ISmsProvider {

    /** Nom attendu dans {@code sms.provider}. */
    public static final String NOM = "twilio";

    private final String accountSid;
    private final String authToken;
    private final String expediteur;

    public TwilioSmsProvider(
            @Value("${sms.twilio.account-sid:}") String accountSid,
            @Value("${sms.twilio.auth-token:}") String authToken,
            @Value("${sms.twilio.sender-id:}") String expediteur) {
        this.accountSid = accountSid == null ? "" : accountSid.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.expediteur = expediteur == null ? "" : expediteur.trim();
    }

    /**
     * Arme le SDK Twilio, dont l'identification est un état <b>statique</b>
     * global.
     *
     * <p>L'initialisation a lieu dès que les identifiants sont présents, sans
     * regarder si Twilio est le fournisseur retenu : {@code Twilio.init} ne fait
     * qu'enregistrer des identifiants et n'ouvre aucune connexion. Conditionner
     * l'initialisation à la sélection obligerait ce bean à connaître
     * {@code sms.provider}, que {@code SmsServiceImpl} est seul à devoir lire.
     */
    @PostConstruct
    public void init() {
        if (estConfigure()) {
            Twilio.init(accountSid, authToken);
            log.info(ServiceConstants.LOG_INFO_SMS_FOURNISSEUR_PRET, NOM);
        }
    }

    @Override
    public String nom() {
        return NOM;
    }

    @Override
    public boolean estConfigure() {
        return !accountSid.isEmpty() && !authToken.isEmpty() && !expediteur.isEmpty();
    }

    @Override
    public Mono<Void> envoyer(String numeroDeTelephone, String contenu) {
        if (!estConfigure()) {
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }

        return Mono.fromRunnable(() -> appelerTwilio(numeroDeTelephone, contenu))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Appel bloquant au SDK, exécuté sur {@code boundedElastic}.
     *
     * <p>L'exception du SDK est <b>remplacée</b>, pas enveloppée. Twilio place
     * l'URL de l'API et parfois le corps de la requête dans le message de ses
     * {@code ApiException} : un appelant qui journalise la cause — et
     * {@code VerificationCodeServiceImpl} le fait — pourrait en extraire le
     * contenu du SMS. Seul le nom de la classe de l'erreur est conservé.
     */
    private void appelerTwilio(String numeroDeTelephone, String contenu) {
        try {
            creerMessage(numeroDeTelephone, contenu);
            log.info(ServiceConstants.LOG_INFO_SMS_SENT_SUCCESS, numeroDeTelephone);
        } catch (Exception e) {
            log.error(ServiceConstants.LOG_ERROR_SMS_TRANSPORT, e.getClass().getSimpleName());
            throw new AuthException(ErrorCode.SMS_SERVICE_ERROR);
        }
    }

    /**
     * L'appel au SDK, isolé pour pouvoir être remplacé en test.
     *
     * <h3>Pourquoi cette couture existe</h3>
     *
     * {@code Message.creator(...).create()} ouvre une connexion vers
     * {@code api.twilio.com}. Tant qu'il était écrit en ligne dans
     * {@link #appelerTwilio}, le traitement d'erreur juste au-dessus —
     * <b>celui qui remplace l'exception du SDK pour que le contenu du SMS ne
     * puisse pas remonter dans les journaux d'un appelant</b> — n'était
     * testable qu'avec un appel réseau réel, donc ne l'était pas. Une protection
     * écrite et jamais exercée : c'est la forme de défaut la plus fréquente de
     * ce dépôt, onze occurrences recensées.
     *
     * <p>Visible pour le test, qui la redéfinit pour lever une exception
     * portant l'URL de l'API et le contenu du message, et vérifie que ni l'une
     * ni l'autre ne survit.
     */
    void creerMessage(String numeroDeTelephone, String contenu) {
        Message.creator(
                new PhoneNumber(numeroDeTelephone),
                new PhoneNumber(expediteur),
                contenu
        ).create();
    }
}
