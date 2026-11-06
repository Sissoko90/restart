package com.abdatytch.auth_service.client;

import com.abdatytch.auth_service.service.ISmsService;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * L'envoi de SMS, delegue a notification-service.
 *
 * <h2>Pourquoi un appel direct et non la passerelle</h2>
 *
 * {@code /internal/**} n'est route par la passerelle dans <b>aucun</b> service :
 * c'est precisement ce qui rend cette surface injoignable depuis l'exterieur.
 * Un appel inter-services y va donc en direct, et deux controles l'autorisent du
 * cote de notification-service — pair TCP prive, et le secret partage ci-dessous.
 *
 * <p>C'est le meme chemin que {@code InterfaceServiceClient} vers
 * {@code /internal/jwt/sign}, et ce n'est pas un contournement de la regle
 * « tout passe par la passerelle » : cette regle vise le trafic venu de
 * l'exterieur, et aucune route n'y mene depuis l'exterieur.
 *
 * <h2>Aucun reessai, deliberement</h2>
 *
 * Un SMS se paie. Reessayer un envoi dont on ne sait pas s'il est parti le fait
 * payer deux fois, et le destinataire recoit deux messages.
 *
 * <p>Le plafond de <b>3 tentatives</b> appartient donc a notification-service,
 * seul a tenir le registre des envois et les accuses de reception — donc seul a
 * pouvoir distinguer un envoi jamais parti d'un envoi accepte mais non encore
 * acquitte. Un reessai pose ici multiplierait ce plafond au lieu de le
 * respecter : trois tentatives ici fois trois la-bas feraient neuf SMS.
 *
 * <h2>Pourquoi le disjoncteur, et dans quel ordre</h2>
 *
 * Si notification-service est indisponible, chaque demande de code attendrait le
 * delai maximal entier pour un echec connu d'avance — sur le chemin de la
 * connexion. Le disjoncteur retient l'information et refuse immediatement.
 *
 * <p>Le delai est a l'INTERIEUR, le disjoncteur a l'exterieur : pose a
 * l'interieur, il ne verrait jamais une dependance figee, l'appel ne se
 * terminant pas. Meme raisonnement, et meme ordre, que dans
 * {@code InterfaceServiceClient}.
 */
@Component
public class ClientSmsNotification implements ISmsService {

    private static final Logger log = LoggerFactory.getLogger(ClientSmsNotification.class);

    static final String DISJONCTEUR = "notification-service-sms";

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;
    private final boolean envoiActif;

    public ClientSmsNotification(
            WebClient.Builder constructeurWebClient,
            IDisjoncteurSortant disjoncteur,
            @Value("${notification-service.url:}") String urlDuService,
            @Value("${security.internal-secret:}") String secretInterne,
            @Value("${notification-service.timeout-seconds:5}") long delaiEnSecondes,
            // La MEME cle que celle que notification-service applique, declaree
            // une seule fois dans la configuration partagee. Ce service la lit
            // pour formuler sa reponse, il ne la fait pas respecter : deux
            // declarations d'une meme valeur finissent par diverger, et le depot
            // a deja paye cela plusieurs fois.
            @Value("${sms.enabled:false}") boolean envoiActif) {

        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.urlDuService = urlDuService == null ? "" : urlDuService.trim();
        this.secretInterne = secretInterne == null ? "" : secretInterne;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
        this.envoiActif = envoiActif;

        // Declare au demarrage : sans cela l'instance n'existerait qu'apres le
        // premier appel, donc invisible de la supervision jusque-la.
        disjoncteur.declarer(DISJONCTEUR);

        if (this.urlDuService.isBlank()) {
            log.error("notification-service.url n'est pas configure : aucun SMS "
                    + "ne partira. Renseigner NOTIFICATION_SERVICE_URL.");
        }
        if (this.secretInterne.isBlank()) {
            log.error("security.internal-secret n'est pas configure : "
                    + "notification-service refusera les demandes d'envoi de SMS.");
        }
    }

    @Override
    public Mono<Void> sendSms(String phoneNumber, String message) {
        if (urlDuService.isBlank()) {
            // Termine vide : rien n'est parti, et l'appelant sait le dire.
            // Lever une erreur ici transformerait une configuration incomplete
            // en echec de la connexion.
            return Mono.empty();
        }

        Mono<Void> appel = webClient.post()
                .uri(urlDuService + InternalSms.CHEMIN)
                .header(InternalSms.EN_TETE_SECRET, secretInterne)
                .bodyValue(Map.of("numero", phoneNumber, "message", message))
                .retrieve()
                .toBodilessEntity()
                .then();

        return disjoncteur.proteger(DISJONCTEUR, appel
                        .timeout(delaiMaximal)
                        .onErrorMap(TimeoutException.class, e -> {
                            log.error("notification-service n'a pas repondu en {} "
                                    + "pour un envoi de SMS", delaiMaximal);
                            return e;
                        }))
                // Un SMS qui ne part pas ne doit pas faire echouer la demande de
                // code : l'appelant a deja genere et enregistre le code, et
                // l'utilisateur peut en demander un autre. Le numero n'est pas
                // journalise — c'est une donnee personnelle.
                .onErrorResume(erreur -> {
                    log.warn("Envoi de SMS impossible via notification-service : {}",
                            erreur.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public boolean envoiActif() {
        return envoiActif;
    }

    /**
     * Le contrat de l'endpoint appele.
     *
     * <p>Chemin et nom d'en-tete en un seul endroit de ce module, pour qu'une
     * faute de frappe se voie a la compilation plutot qu'en 403 a l'execution.
     * Les valeurs sont celles de {@code InternalSmsController} et
     * {@code AutorisationInterne}.
     */
    static final class InternalSms {
        static final String CHEMIN = "/internal/sms";
        static final String EN_TETE_SECRET = "X-Internal-Secret";

        private InternalSms() {
        }
    }
}
