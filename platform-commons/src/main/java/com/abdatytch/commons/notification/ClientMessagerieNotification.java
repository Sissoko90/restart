package com.abdatytch.commons.notification;

import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Les messages de ce module, remis par notification-service.
 *
 * <h2>Ce qu'il remplace</h2>
 *
 * {@code ClientSmsNotification} — qui ne savait que le SMS — et
 * {@code EmailServiceImpl}, qui ouvrait sa propre connexion SMTP depuis ce
 * module. Un seul client, un seul endpoint, un seul disjoncteur.
 *
 * <p>Le depart d'{@code EmailServiceImpl} retire aussi de ce module le
 * {@code JavaMailSender}, les reglages {@code email.*} et la composition de
 * texte. Le courriel n'est plus une affaire d'auth-service.
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
 * <p>Les garde-fous de depense appartiennent donc a notification-service, seul a
 * tenir le registre des envois et les accuses de reception — donc seul a pouvoir
 * distinguer un envoi jamais parti d'un envoi accepte mais non encore acquitte.
 * Un reessai pose ici multiplierait son plafond au lieu de le respecter.
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
 *
 * <h2>La reponse est LUE, et ce n'etait pas le cas</h2>
 *
 * {@code ClientSmsNotification} appelait {@code toBodilessEntity()} : il jetait
 * le corps {@code {remis, motif}} que notification-service prend soin de rendre.
 * Son propre javadoc le disait — « lire `remis` corrigerait cela, et c'est la
 * prochaine amelioration utile sur ce chemin ».
 *
 * <p>Consequence de ce rattrapage : ce module distingue enfin « remis au
 * fournisseur » de « refuse par un garde-fou de depense », et le journalise avec
 * le MOTIF. Un plafond journalier atteint se lisait jusqu'ici comme un succes.
 */
public class ClientMessagerieNotification implements IMessagerieSortante {

    private static final Logger log =
            LoggerFactory.getLogger(ClientMessagerieNotification.class);

    static final String DISJONCTEUR = "notification-service-message";

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;
    private final boolean envoiSmsActif;

    public ClientMessagerieNotification(
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
            @Value("${sms.enabled:false}") boolean envoiSmsActif) {

        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.urlDuService = urlDuService == null ? "" : urlDuService.trim();
        this.secretInterne = secretInterne == null ? "" : secretInterne;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
        this.envoiSmsActif = envoiSmsActif;

        // Declare au demarrage : sans cela l'instance n'existerait qu'apres le
        // premier appel, donc invisible de la supervision jusque-la.
        disjoncteur.declarer(DISJONCTEUR);

        if (this.urlDuService.isBlank()) {
            log.error("notification-service.url n'est pas configure : aucun "
                    + "message ne partira. Renseigner NOTIFICATION_SERVICE_URL.");
        }
        if (this.secretInterne.isBlank()) {
            log.error("security.internal-secret n'est pas configure : "
                    + "notification-service refusera les demandes d'envoi.");
        }
    }

    @Override
    public Mono<Boolean> envoyer(Demande demande) {
        if (urlDuService.isBlank()) {
            // Faux, et non une erreur : une configuration incomplete ne doit pas
            // transformer un message manquant en echec de l'operation qui l'a
            // declenchee.
            return Mono.just(false);
        }

        Mono<Reponse> appel = webClient.post()
                .uri(urlDuService + InternalMessage.CHEMIN)
                .header(InternalMessage.EN_TETE_SECRET, secretInterne)
                .bodyValue(new Corps(
                        demande.canal().name(),
                        demande.destinataire(),
                        demande.modele(),
                        demande.variables() == null ? Map.of() : demande.variables(),
                        demande.urgence() == null ? null : demande.urgence().name()))
                .retrieve()
                .bodyToMono(Reponse.class)
                // Corps vide : on ne sait pas, donc on ne pretend pas que c'est
                // parti. Affirmer le succes sur une reponse muette ferait
                // annoncer un code que personne n'a recu.
                .defaultIfEmpty(new Reponse(false, "reponse sans corps"));

        return disjoncteur.proteger(DISJONCTEUR, appel
                        .timeout(delaiMaximal)
                        .onErrorMap(TimeoutException.class, e -> {
                            log.error("notification-service n'a pas repondu en {} "
                                            + "pour un envoi par {}", delaiMaximal,
                                    demande.canal());
                            return e;
                        }))
                .map(reponse -> {
                    if (!reponse.remis()) {
                        // Le MOTIF, et c'est l'apport de cette passe : un
                        // plafond journalier atteint ne se lit plus comme un
                        // succes. Le motif est garanti sans donnee personnelle
                        // par le contrat de notification-service — ni adresse,
                        // ni numero, ni code.
                        log.warn("Message par {} non remis : {}", demande.canal(),
                                reponse.motif());
                    }
                    return reponse.remis();
                })
                // Un message qui ne part pas ne doit pas faire echouer
                // l'operation : l'appelant a deja enregistre son code, et
                // l'utilisateur peut en demander un autre. Ni le numero ni
                // l'adresse ne sont journalises — ce sont des donnees
                // personnelles.
                .onErrorResume(erreur -> {
                    log.warn("Envoi par {} impossible via notification-service : {}",
                            demande.canal(), erreur.getMessage());
                    return Mono.just(false);
                });
    }

    @Override
    public boolean envoiSmsActif() {
        return envoiSmsActif;
    }

    /**
     * Le corps envoye.
     *
     * <p>Un record et non une {@code Map} : {@code Map.of} refuse une valeur
     * nulle, et {@code urgence} comme {@code variables} peuvent legitimement
     * l'etre. Avec une carte, le cas se serait vu en
     * {@code NullPointerException} a l'envoi d'un message sans variable.
     *
     * <p>Le canal et l'urgence voyagent en chaines : Jackson les relit en
     * enumerations du cote de notification-service, par leur nom.
     */
    private record Corps(String canal, String destinataire, String modele,
                         Map<String, String> variables, String urgence) {
    }

    /**
     * Ce que notification-service repond.
     *
     * <p>Doit correspondre a {@code InternalMessageController.Remise}. Un champ
     * ajoute la-bas et absent ici est ignore par Jackson ; un champ renomme
     * rendrait {@code remis} faux en silence — d'ou le journal du motif, qui
     * rendrait le cas visible.
     */
    private record Reponse(boolean remis, String motif) {
    }

    /**
     * Le contrat de l'endpoint appele.
     *
     * <p>Chemin et nom d'en-tete en un seul endroit de ce module, pour qu'une
     * faute de frappe se voie a la compilation plutot qu'en 403 a l'execution.
     * Les valeurs sont celles d'{@code InternalMessageController} et
     * d'{@code AutorisationInterne}.
     */
    static final class InternalMessage {
        static final String CHEMIN = "/internal/message";
        static final String EN_TETE_SECRET = "X-Internal-Secret";

        private InternalMessage() {
        }
    }
}
