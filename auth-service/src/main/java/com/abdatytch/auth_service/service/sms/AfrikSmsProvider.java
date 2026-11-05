package com.abdatytch.auth_service.service.sms;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Envoi de SMS par AfrikSMS, le fournisseur retenu pour la plateforme.
 *
 * <p>L'API prend cinq paramètres obligatoires en <b>chaîne de requête</b> —
 * {@code ClientId}, {@code ApiKey}, {@code SenderId}, {@code Message},
 * {@code MobileNumbers} — et répond un JSON dont {@code code} vaut
 * {@value #CODE_SUCCES} en cas de succès.
 *
 * <h2>Pourquoi WebClient et non l'exemple OkHttp de la documentation</h2>
 *
 * L'exemple fourni par AfrikSMS utilise {@code client.newCall(request).execute()},
 * qui bloque le fil appelant. Sur le chemin d'une requête WebFlux, cela
 * immobilise un fil de la boucle d'événements — et {@code BlockHound}, actif
 * dans les tests de ce dépôt, fait échouer l'appel. Le transport est donc
 * {@code WebClient}, qui ne bloque pas.
 *
 * <h2>Le piège : l'URI porte à la fois la clé d'API et le code à usage unique</h2>
 *
 * Parce que les paramètres voyagent dans la chaîne de requête, l'URI de l'appel
 * contient l'{@code ApiKey} <b>et</b> le message, donc le code de vérification.
 * Or le message d'un {@code WebClientResponseException} ou d'un
 * {@code WebClientRequestException} de Spring <b>inclut l'URI de la requête</b>.
 * Laisser remonter cette exception suffirait à tout écrire dans les journaux, et
 * pas même ici : {@code VerificationCodeServiceImpl} journalise déjà l'erreur
 * reçue par {@code log.error(..., phoneNumber, error)}. La fuite serait apparue
 * dans un fichier que l'on n'aurait pas pensé à relire.
 *
 * <p>Deux mesures, et il faut les deux :
 *
 * <ol>
 *   <li>{@code exchangeToMono} au lieu de {@code retrieve()}, pour qu'un statut
 *       d'erreur HTTP ne <b>fabrique</b> jamais une telle exception ;</li>
 *   <li>{@code onErrorMap} vers un {@link AuthException} <b>sans cause</b> : une
 *       panne de transport (DNS, connexion refusée, expiration) est convertie en
 *       une exception dont le message ne contient que ce que l'on a choisi. Le
 *       diagnostic est conservé par la journalisation du <b>nom de la classe</b>
 *       de l'erreur et du statut, jamais de l'objet exception.</li>
 * </ol>
 *
 * <p>Rien dans cette classe ne passe {@code contenu} ni {@code apiKey} à un
 * logger. {@code AucunJustificatifDansLesJournauxTest} le vérifie sur tous les
 * modules, et {@code AfrikSmsProviderTest} le vérifie ici en capturant les
 * journaux réellement émis pendant un échec.
 */
@Component
@Slf4j
public class AfrikSmsProvider implements ISmsProvider {

    /** Nom attendu dans {@code sms.provider}. */
    public static final String NOM = "afriksms";

    /** Valeur de {@code code} qu'AfrikSMS renvoie en cas de succès. */
    static final int CODE_SUCCES = 100;

    /**
     * Longueur maximale du nom d'expéditeur, imposée par AfrikSMS.
     *
     * <p>Un nom plus long est tronqué, et l'avertissement est émis <b>au
     * démarrage</b> et non à chaque envoi : répété à chaque SMS, il serait
     * ignoré.
     */
    static final int LONGUEUR_MAX_EXPEDITEUR = 11;

    private static final ObjectMapper LECTEUR_JSON = new ObjectMapper();

    private final WebClient webClient;
    private final IRegistreDesEnvoisSms registre;
    private final String urlEnvoi;
    private final String clientId;
    private final String apiKey;
    private final String expediteur;
    private final Duration delaiMaximal;

    /**
     * Instance de disjoncteur pour AfrikSMS.
     *
     * <p>Une seule pour cette dependance : si AfrikSMS ne repond pas, aussi
     * bien l'envoi que les operations d'administration echouent. Deux
     * instances separees compteraient deux fois la meme panne, et chacune
     * mettrait plus longtemps a s'ouvrir.
     */
    static final String DISJONCTEUR = "afriksms";

    private final IDisjoncteurSortant disjoncteur;

    /**
     * @param urlEnvoi      point d'entrée d'AfrikSMS. Aucune valeur par défaut
     *                      n'est écrite ici : l'URL appartient à la
     *                      configuration, et ce paramètre vaut la chaîne vide
     *                      quand elle manque, ce que {@link #estConfigure()}
     *                      signale au démarrage.
     * @param expediteur    nom affiché, tronqué à {@value #LONGUEUR_MAX_EXPEDITEUR}
     */
    public AfrikSmsProvider(
            WebClient.Builder constructeurWebClient,
            IRegistreDesEnvoisSms registre,
            IDisjoncteurSortant disjoncteur,
            @Value("${sms.afriksms.url:}") String urlEnvoi,
            @Value("${sms.afriksms.client-id:}") String clientId,
            @Value("${sms.afriksms.api-key:}") String apiKey,
            @Value("${sms.afriksms.sender-id:}") String expediteur,
            @Value("${sms.afriksms.timeout-seconds:10}") long delaiEnSecondes) {
        this.webClient = constructeurWebClient.build();
        this.registre = registre;
        this.urlEnvoi = valeurOuVide(urlEnvoi);
        this.clientId = valeurOuVide(clientId);
        this.apiKey = valeurOuVide(apiKey);
        this.expediteur = tronquerExpediteur(valeurOuVide(expediteur));
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
        this.disjoncteur = disjoncteur;
        // Declare au demarrage : sinon l'instance n'existerait qu'apres le
        // premier SMS, donc invisible de la supervision jusque-la.
        disjoncteur.declarer(DISJONCTEUR);
    }

    @Override
    public String nom() {
        return NOM;
    }

    @Override
    public boolean estConfigure() {
        return !urlEnvoi.isEmpty() && !clientId.isEmpty() && !apiKey.isEmpty()
                && !expediteur.isEmpty();
    }

    /**
     * Remet le message à AfrikSMS.
     *
     * <p>Un statut HTTP 2xx ne suffit pas : l'API répond 200 avec un
     * {@code code} différent de {@value #CODE_SUCCES} lorsqu'elle refuse
     * l'envoi — crédit épuisé, expéditeur inconnu, numéro invalide. Ne regarder
     * que le statut HTTP ferait donc passer un échec pour un succès, et
     * l'utilisateur attendrait un SMS qui n'arrive jamais.
     */
    @Override
    public Mono<Void> envoyer(String numeroDeTelephone, String contenu) {
        if (!estConfigure()) {
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }

        String numero = NumeroPourAfrikSms.normaliser(numeroDeTelephone);
        if (numero.isEmpty()) {
            log.error(ServiceConstants.LOG_ERROR_SMS_NUMERO_INEXPLOITABLE);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }

        return disjoncteur.proteger(DISJONCTEUR, appelDEnvoi(numero, contenu));
    }

    /**
     * L'appel lui-meme, borne par un delai et enveloppe par le disjoncteur.
     *
     * <p>L'ordre importe : le delai est a l'INTERIEUR du disjoncteur. Pose a
     * l'exterieur, le disjoncteur ne verrait jamais echouer une dependance
     * figee — l'appel ne se terminerait pas, donc rien a compter.
     */
    private Mono<Void> appelDEnvoi(String numero, String contenu) {
        return webClient.get()
                .uri(construireUri(numero, contenu))
                .exchangeToMono(reponse -> reponse.bodyToMono(String.class)
                        // Un corps vide rendrait un Mono VIDE, et l'envoi
                        // paraitrait alors reussi sans que rien ne soit
                        // verifie : le defaultIfEmpty est pose ici, sur le
                        // Mono<String>, et non sur le Mono<Void> final.
                        .defaultIfEmpty("")
                        .flatMap(corps -> evaluer(reponse.statusCode(), corps, numero)))
                .timeout(delaiMaximal)
                .onErrorMap(erreur -> !(erreur instanceof AuthException), erreur -> {
                    // L'OBJET erreur n'est jamais journalise : son message
                    // contiendrait l'URI, donc l'ApiKey et le code. Seul le nom
                    // de sa classe l'est.
                    log.error(ServiceConstants.LOG_ERROR_SMS_TRANSPORT,
                            erreur.getClass().getSimpleName());
                    // Sans cause, pour la meme raison : un appelant qui
                    // journalise l'exception ne doit rien pouvoir en extraire.
                    //
                    // SMS_PROVIDER_UNAVAILABLE et non SMS_SERVICE_ERROR : ce
                    // chemin est celui du TRANSPORT — panne reseau, delai
                    // depasse, hote injoignable. Le distinguer d'un refus du
                    // fournisseur est ce qui permet au disjoncteur de compter
                    // les pannes sans compter les refus legitimes.
                    return new AuthException(ErrorCode.SMS_PROVIDER_UNAVAILABLE);
                })
                .then();
    }

    /**
     * Construit l'URI d'appel, en laissant {@code UriComponentsBuilder} encoder
     * les <b>valeurs</b>.
     *
     * <p>{@code encode()} est appelé <b>avant</b> {@code build()}, et les
     * valeurs sont passées comme variables de gabarit expansées ensuite. C'est
     * le mode strict : il encode aussi {@code &}, {@code =} et {@code %} à
     * l'intérieur d'une valeur. Le mode inverse — construire puis
     * {@code UriComponents.encode()} — ne les encoderait pas, puisqu'ils sont
     * légaux dans une chaîne de requête : un message contenant {@code &}
     * deviendrait alors deux paramètres.
     *
     * <p>L'{@code URI} est passé à {@code uri(URI)} et non à {@code uri(String)},
     * qui le traiterait comme un gabarit et le ré-encoderait — {@code %26}
     * deviendrait {@code %2526}. Le dépôt a déjà été mordu par cette forme, du
     * côté des tests.
     */
    private URI construireUri(String numero, String contenu) {
        return UriComponentsBuilder.fromUriString(urlEnvoi)
                .queryParam("ClientId", "{clientId}")
                .queryParam("ApiKey", "{apiKey}")
                .queryParam("SenderId", "{senderId}")
                .queryParam("Message", "{message}")
                .queryParam("MobileNumbers", "{mobileNumbers}")
                .encode()
                .build()
                .expand(Map.of(
                        "clientId", clientId,
                        "apiKey", apiKey,
                        "senderId", expediteur,
                        "message", contenu,
                        "mobileNumbers", numero))
                .toUri();
    }

    /**
     * Décide du sort de l'envoi d'après le statut HTTP et le {@code code} du
     * corps.
     *
     * <p>Le corps est lu comme du texte puis analysé, et non décodé
     * directement en objet : un fournisseur tiers qui répond avec un
     * {@code Content-Type} inattendu — {@code text/html} sur une page d'erreur,
     * par exemple — ferait échouer le décodeur JSON sur une exception de type de
     * média, qui ne dit rien de l'incident réel.
     */
    private Mono<Void> evaluer(HttpStatusCode statut, String corps, String numeroJournalisable) {
        if (!statut.is2xxSuccessful()) {
            log.error(ServiceConstants.LOG_ERROR_SMS_STATUT_HTTP, statut.value());
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }

        // La variable NE s'appelle PAS `code`, bien que ce soit le nom du champ
        // chez AfrikSMS. Elle porte un statut de fournisseur, pas un code a
        // usage unique, et le garde-fou qui interdit de journaliser un
        // justificatif surveille le mot `code` — a juste titre, puisque c'est
        // ainsi que s'appelle la variable du code de verification ailleurs dans
        // ce service. Un nom exact evite d'avoir a inscrire ce site comme une
        // dette toleree, et donc d'affaiblir le garde-fou.
        JsonNode racine = analyser(corps);
        Integer statutFournisseur = racine == null ? null : statutDeLaReponse(racine);
        if (statutFournisseur == null) {
            log.error(ServiceConstants.LOG_ERROR_SMS_REPONSE_ILLISIBLE);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        if (statutFournisseur != CODE_SUCCES) {
            // Seul le statut est journalise. Le champ `message` de la reponse
            // n'apporte rien ici et vient d'un tiers : on ne le recopie pas.
            log.error(ServiceConstants.LOG_ERROR_SMS_REFUS_FOURNISSEUR, statutFournisseur);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        // Le succes se journalise ICI, et nulle part en amont : seul le
        // fournisseur sait qu'un envoi a ete accepte. L'appelant le faisait sur
        // un `doOnSuccess`, qui se declenche aussi quand la source se termine
        // vide — donc quand l'envoi est coupe ou que le disjoncteur a repli.
        log.info(ServiceConstants.LOG_INFO_SMS_SENT_SUCCESS, numeroJournalisable);

        return noterPourLAccuse(racine, numeroJournalisable);
    }

    /**
     * Retient {@code resourceId -> numéro}, pour que l'accusé de réception à
     * venir désigne quelqu'un.
     *
     * <h3>Un enrichissement, jamais un motif d'échec</h3>
     *
     * À ce point, le SMS <b>est parti</b> : AfrikSMS l'a accepté. Faire échouer
     * l'envoi parce que Redis est indisponible annoncerait un échec à
     * l'utilisateur alors qu'il va recevoir son code, et le ferait recommencer
     * pour rien. Toute erreur d'écriture est donc absorbée.
     *
     * <p>{@code resourceId} absent de la réponse : l'envoi reste un succès. Ce
     * champ ne sert qu'au rapprochement ultérieur, et son absence ne remet pas
     * en cause l'acceptation du message.
     */
    private Mono<Void> noterPourLAccuse(JsonNode racine, String numero) {
        JsonNode champ = racine.get("resourceId");
        if (champ == null || champ.asText().isBlank()) {
            return Mono.empty();
        }
        return registre.enregistrer(champ.asText(), numero)
                .onErrorResume(erreur -> {
                    log.warn(ServiceConstants.LOG_WARN_SMS_REGISTRE_INDISPONIBLE,
                            erreur.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /** Le corps analysé, ou {@code null} s'il est illisible. */
    private JsonNode analyser(String corps) {
        if (corps.isBlank()) {
            return null;
        }
        try {
            return LECTEUR_JSON.readTree(corps);
        } catch (Exception e) {
            // Le corps N'EST PAS journalise : il vient d'un tiers et pourrait
            // contenir ce qu'on lui a envoye.
            return null;
        }
    }

    /** Le champ {@code code} du corps, ou {@code null} s'il est illisible. */
    private Integer statutDeLaReponse(JsonNode racine) {
        try {
            JsonNode champ = racine.get("code");
            if (champ == null) {
                return null;
            }
            // AfrikSMS documente un entier pour l'envoi et une chaine (« 000 »)
            // pour les accuses de reception : les deux formes sont acceptees.
            return champ.isNumber() ? champ.intValue() : Integer.parseInt(champ.asText().trim());
        } catch (Exception e) {
            // Le corps N'EST PAS journalise : il vient d'un tiers et pourrait
            // contenir ce qu'on lui a envoye.
            return null;
        }
    }

    private static String valeurOuVide(String valeur) {
        return valeur == null ? "" : valeur.trim();
    }

    private static String tronquerExpediteur(String valeur) {
        if (valeur.length() <= LONGUEUR_MAX_EXPEDITEUR) {
            return valeur;
        }
        String tronque = valeur.substring(0, LONGUEUR_MAX_EXPEDITEUR);
        // Emis une seule fois, a la construction du bean. Place dans l'envoi,
        // cet avertissement se repeterait a chaque SMS et finirait ignore.
        log.warn(ServiceConstants.LOG_WARN_SMS_EXPEDITEUR_TRONQUE,
                LONGUEUR_MAX_EXPEDITEUR, tronque);
        return tronque;
    }
}
