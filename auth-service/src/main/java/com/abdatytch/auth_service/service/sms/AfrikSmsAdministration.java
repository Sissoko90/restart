package com.abdatytch.auth_service.service.sms;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.constants.ServiceConstants;
import com.abdatytch.auth_service.exception.AuthException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Les deux appels d'administration d'AfrikSMS : déclarer l'URL de rappel, et
 * consulter le solde.
 *
 * <p>Séparés de {@link AfrikSmsProvider}, qui n'a qu'un seul travail — remettre
 * un message. Ces deux opérations ne sont pas sur le chemin d'un envoi, ne
 * portent aucun contenu de SMS, et sont déclenchées par un administrateur.
 *
 * <h2>Pourquoi le solde mérite un point d'entrée</h2>
 *
 * Un crédit épuisé chez AfrikSMS ne produit <b>aucune erreur HTTP</b> : les
 * envois répondent 200 avec un {@code code} de refus. Sans moyen de regarder le
 * solde, la panne se manifeste uniquement par des utilisateurs qui ne reçoivent
 * plus leur code — en masse, et sans que rien n'indique pourquoi.
 *
 * <h2>Le piège des journaux, identique à celui de l'envoi</h2>
 *
 * Ces deux appels portent eux aussi l'{@code ApiKey} dans la chaîne de requête.
 * Les exceptions de {@code WebClient} contiennent l'URI, donc la clé : aucune
 * n'est journalisée ni propagée, exactement comme dans {@code AfrikSmsProvider}.
 * Ils ne transportent pas de code à usage unique, mais une clé d'API divulguée
 * suffirait à envoyer des SMS à nos frais depuis notre compte.
 */
@Service
@Slf4j
public class AfrikSmsAdministration implements IAdministrationSms {

    /** Valeur de {@code code} qu'AfrikSMS renvoie en cas de succès. */
    static final int CODE_SUCCES = 100;

    /** {@code typeNotification} : AfrikSMS appellera notre URL en POST. */
    public static final int NOTIFICATION_PAR_POST = 1;

    /** {@code typeNotification} : AfrikSMS appellera notre URL en GET. */
    public static final int NOTIFICATION_PAR_GET = 2;

    private static final ObjectMapper LECTEUR_JSON = new ObjectMapper();

    private final WebClient webClient;
    private final String urlDeConfigurationDuRappel;
    private final String urlDuSolde;
    private final String clientId;
    private final String apiKey;
    private final String urlDeRappelDeclaree;
    private final int typeDeNotification;
    private final Duration delaiMaximal;

    /**
     * @param urlDeRappelDeclaree l'URL que l'on déclare à AfrikSMS. Elle vient
     *                            de la <b>configuration</b> et non d'un corps de
     *                            requête : voir
     *                            {@link #declarerUrlDeRappel()}.
     */
    public AfrikSmsAdministration(
            WebClient.Builder constructeurWebClient,
            @Value("${sms.afriksms.callback-url:}") String urlDeConfigurationDuRappel,
            @Value("${sms.afriksms.balance-url:}") String urlDuSolde,
            @Value("${sms.afriksms.client-id:}") String clientId,
            @Value("${sms.afriksms.api-key:}") String apiKey,
            @Value("${sms.afriksms.notify-url:}") String urlDeRappelDeclaree,
            @Value("${sms.afriksms.notify-type:1}") int typeDeNotification,
            @Value("${sms.afriksms.timeout-seconds:10}") long delaiEnSecondes) {
        this.webClient = constructeurWebClient.build();
        this.urlDeConfigurationDuRappel = valeurOuVide(urlDeConfigurationDuRappel);
        this.urlDuSolde = valeurOuVide(urlDuSolde);
        this.clientId = valeurOuVide(clientId);
        this.apiKey = valeurOuVide(apiKey);
        this.urlDeRappelDeclaree = valeurOuVide(urlDeRappelDeclaree);
        this.typeDeNotification = typeDeNotification;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);
    }

    /**
     * Déclare à AfrikSMS l'URL qu'il doit rappeler pour les accusés de
     * réception.
     *
     * <h3>L'URL n'est pas un paramètre de la requête d'administration</h3>
     *
     * Un point d'entrée admin qui accepterait une {@code notifyURL} arbitraire
     * laisserait un administrateur — ou quiconque obtiendrait un jeton
     * d'administration — <b>rediriger nos accusés de réception vers un serveur
     * de son choix</b>. Ces accusés contiennent nos {@code resourceId}, et
     * détourner le flux permettrait d'observer qui reçoit des SMS et quand.
     *
     * <p>L'URL vient donc de {@code sms.afriksms.notify-url}, c'est-à-dire de
     * la configuration de déploiement. L'appel d'administration ne fait que
     * <b>pousser la valeur configurée</b>. Il n'y a rien à injecter.
     *
     * @return le type de notification confirmé par AfrikSMS
     */
    @Override
    public Mono<String> declarerUrlDeRappel() {
        if (urlDeConfigurationDuRappel.isEmpty() || clientId.isEmpty() || apiKey.isEmpty()) {
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        if (urlDeRappelDeclaree.isEmpty()) {
            log.error(ServiceConstants.LOG_ERROR_SMS_URL_RAPPEL_ABSENTE);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        if (typeDeNotification != NOTIFICATION_PAR_POST
                && typeDeNotification != NOTIFICATION_PAR_GET) {
            log.error(ServiceConstants.LOG_ERROR_SMS_TYPE_NOTIFICATION, typeDeNotification);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }

        URI cible = UriComponentsBuilder.fromUriString(urlDeConfigurationDuRappel)
                .queryParam("ClientId", "{clientId}")
                .queryParam("ApiKey", "{apiKey}")
                .queryParam("notifyURL", "{notifyUrl}")
                // La documentation ecrit « typeNotification » dans le tableau
                // des parametres et « TypeNotification » dans son exemple cURL.
                // Les deux sont envoyes : c'est le seul moyen sur de satisfaire
                // l'API sans pouvoir l'interroger, et un parametre en trop est
                // ignore, alors qu'un parametre manquant fait echouer l'appel.
                .queryParam("typeNotification", "{type}")
                .queryParam("TypeNotification", "{type}")
                .encode()
                .build()
                .expand(Map.of(
                        "clientId", clientId,
                        "apiKey", apiKey,
                        "notifyUrl", urlDeRappelDeclaree,
                        "type", String.valueOf(typeDeNotification)))
                .toUri();

        return appeler(cible)
                .map(corps -> {
                    String type = texte(corps, "typeNotification");
                    log.info(ServiceConstants.LOG_INFO_SMS_URL_RAPPEL_DECLAREE, type);
                    return type;
                });
    }

    /**
     * Consulte le solde, par pays.
     *
     * <p>Un solde <b>négatif ou nul</b> est le signe que les SMS ont cessé de
     * partir, ou vont cesser. L'exemple de la documentation en montre un à
     * {@code -2} : la valeur peut passer sous zéro, donc tester {@code == 0} ne
     * suffirait pas.
     */
    @Override
    public Mono<List<SoldeParPays>> consulterSolde() {
        if (urlDuSolde.isEmpty() || clientId.isEmpty() || apiKey.isEmpty()) {
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }

        URI cible = UriComponentsBuilder.fromUriString(urlDuSolde)
                .queryParam("ClientId", "{clientId}")
                .queryParam("ApiKey", "{apiKey}")
                .encode()
                .build()
                .expand(Map.of("clientId", clientId, "apiKey", apiKey))
                .toUri();

        return appeler(cible).map(AfrikSmsAdministration::soldesDe);
    }

    /**
     * Appel commun aux deux opérations : exécute, vérifie, et ne divulgue rien.
     *
     * <p>{@code exchangeToMono} plutôt que {@code retrieve()}, et un
     * {@code AuthException} sans cause : même raisonnement que dans
     * {@code AfrikSmsProvider}, et pour la même raison — l'URI porte
     * l'{@code ApiKey}, et le message d'une exception {@code WebClient} porte
     * l'URI.
     */
    private Mono<JsonNode> appeler(URI cible) {
        return webClient.get()
                .uri(cible)
                .exchangeToMono(reponse -> reponse.bodyToMono(String.class)
                        // Sur un corps vide, bodyToMono rend un Mono VIDE :
                        // l'appel paraitrait reussi sans qu'aucun code n'ait ete
                        // verifie, et l'appelant recevrait un Mono vide la ou il
                        // attend une valeur.
                        .defaultIfEmpty("")
                        .flatMap(corps -> evaluer(reponse.statusCode(), corps)))
                .timeout(delaiMaximal)
                .onErrorMap(erreur -> !(erreur instanceof AuthException), erreur -> {
                    log.error(ServiceConstants.LOG_ERROR_SMS_TRANSPORT,
                            erreur.getClass().getSimpleName());
                    return new AuthException(ErrorCode.SMS_SERVICE_ERROR);
                });
    }

    private Mono<JsonNode> evaluer(HttpStatusCode statut, String corps) {
        if (!statut.is2xxSuccessful()) {
            log.error(ServiceConstants.LOG_ERROR_SMS_STATUT_HTTP, statut.value());
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        JsonNode racine = analyser(corps);
        if (racine == null) {
            log.error(ServiceConstants.LOG_ERROR_SMS_REPONSE_ILLISIBLE);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        JsonNode champ = racine.get("code");
        Integer statutFournisseur = champ == null ? null : entier(champ);
        if (statutFournisseur == null) {
            log.error(ServiceConstants.LOG_ERROR_SMS_REPONSE_ILLISIBLE);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        if (statutFournisseur != CODE_SUCCES) {
            log.error(ServiceConstants.LOG_ERROR_SMS_REFUS_FOURNISSEUR, statutFournisseur);
            return Mono.error(new AuthException(ErrorCode.SMS_SERVICE_ERROR));
        }
        return Mono.just(racine);
    }

    /**
     * Lit le tableau {@code information} de la réponse de solde.
     *
     * <p>Une entrée illisible est <b>ignorée</b> plutôt que de faire échouer
     * toute la consultation : savoir le solde de deux pays sur trois vaut mieux
     * que ne rien savoir parce qu'un pays a été ajouté avec un champ
     * inattendu.
     */
    private static List<SoldeParPays> soldesDe(JsonNode racine) {
        List<SoldeParPays> soldes = new ArrayList<>();
        JsonNode information = racine.get("information");
        if (information == null || !information.isArray()) {
            return soldes;
        }
        for (JsonNode entree : information) {
            JsonNode pays = entree.get("country");
            JsonNode solde = entree.get("solde");
            if (pays == null || solde == null) {
                continue;
            }
            Integer valeur = entier(solde);
            if (valeur == null) {
                continue;
            }
            soldes.add(new SoldeParPays(pays.asText(), valeur));
        }
        return soldes;
    }

    private static JsonNode analyser(String corps) {
        if (corps.isBlank()) {
            return null;
        }
        try {
            return LECTEUR_JSON.readTree(corps);
        } catch (Exception e) {
            // Le corps N'EST PAS journalise : il vient d'un tiers.
            return null;
        }
    }

    /** Un champ JSON lu comme entier, qu'il arrive en nombre ou en chaîne. */
    private static Integer entier(JsonNode champ) {
        if (champ.isNumber()) {
            return champ.intValue();
        }
        try {
            return Integer.parseInt(champ.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String texte(JsonNode racine, String nom) {
        JsonNode champ = racine.get(nom);
        return champ == null ? "" : champ.asText();
    }

    private static String valeurOuVide(String valeur) {
        return valeur == null ? "" : valeur.trim();
    }
}
