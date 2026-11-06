package com.abdatytch.notification_service.client;

import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Le consentement d'une personne, demande a setting-service.
 *
 * <h2>Pourquoi setting-service et pas une table ici</h2>
 *
 * setting-service detient deja les preferences par utilisateur, avec son
 * effacement RGPD et son administration. Une table de consentement dans ce
 * module serait une seconde liste de preferences, a effacer separement — et un
 * consentement oublie par un effacement est un probleme reglementaire, pas un
 * detail d'implementation.
 *
 * <h2>Tout le jeu en UN appel</h2>
 *
 * Et non une cle par canal. Une notification critique vise quatre canaux : un
 * appel par consentement aurait quadruple le trafic et les occasions de panne,
 * sur le chemin meme des alertes de securite.
 *
 * <h2>Une absence de reponse n'est PAS un refus</h2>
 *
 * C'est le point qui demande le plus d'attention. Trois situations se
 * ressemblent et n'ont pas le meme sens :
 *
 * <ul>
 *   <li>la personne a <b>refuse</b> un canal : on ne lui envoie pas ;</li>
 *   <li>elle n'a <b>rien dit</b> — le cas de la quasi-totalite des comptes : la
 *       politique de defaut tranche ;</li>
 *   <li><b>setting-service n'a pas repondu</b> : on ne sait pas.</li>
 * </ul>
 *
 * <p>Ce client rend une carte VIDE dans les deux derniers cas, et l'appelant
 * applique alors ses defauts. C'est un choix assume : traiter une panne de
 * setting-service comme un refus general couperait tous les canaux a coordonnee
 * pendant l'incident, y compris les codes de connexion — on empecherait les
 * gens de se connecter pour proteger une preference qu'ils n'ont probablement
 * pas exprimee.
 *
 * <p>Le repli n'est pas silencieux : il journalise en {@code WARN}. Un repli qui
 * masque sa panne serait le defaut ; un repli qui l'annonce est une decision.
 *
 * <h2>Pas de cache local</h2>
 *
 * Meme raison que pour les coordonnees : un cache ici serait une seconde verite
 * sans moyen d'etre invalide, et une personne qui vient de refuser les SMS en
 * recevrait encore pendant toute sa duree de vie. Un consentement perime est un
 * probleme reglementaire.
 */
@Component
public class ClientPreferencesUtilisateur {

    private static final Logger log =
            LoggerFactory.getLogger(ClientPreferencesUtilisateur.class);

    static final String DISJONCTEUR = "setting-service-preferences";

    /** Non route par la passerelle, comme tout {@code /internal/**}. */
    static final String CHEMIN = "/internal/settings/users/";

    static final String EN_TETE_SECRET = "X-Internal-Secret";

    /**
     * Une preference, telle que setting-service la rend.
     *
     * <p>Deux champs sur quatre : {@code userId} est deja connu de l'appelant et
     * {@code updatedAt} ne sert pas a decider. {@code ignoreUnknown} pour que
     * l'ajout d'un champ la-bas ne casse pas la lecture ici.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Preference(String key, String value) {
    }

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;

    public ClientPreferencesUtilisateur(
            WebClient.Builder constructeurWebClient,
            IDisjoncteurSortant disjoncteur,
            @Value("${setting-service.url:}") String urlDuService,
            @Value("${security.internal-secret:}") String secretInterne,
            @Value("${setting-service.timeout-seconds:3}") long delaiEnSecondes) {

        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.urlDuService = urlDuService == null ? "" : urlDuService.trim();
        this.secretInterne = secretInterne == null ? "" : secretInterne;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);

        disjoncteur.declarer(DISJONCTEUR);

        if (this.urlDuService.isBlank()) {
            log.warn("setting-service.url n'est pas configure : aucun consentement "
                    + "ne sera lu, et la politique de defaut s'appliquera a tous. "
                    + "Renseigner SETTING_SERVICE_URL.");
        }
    }

    /**
     * Les preferences d'une personne, en carte cle vers valeur.
     *
     * @return une carte, <b>jamais</b> {@code null} ni une erreur. Vide quand la
     *         personne n'a rien regle, et vide aussi quand setting-service n'a
     *         pas repondu — voir la javadoc de classe pour pourquoi ces deux cas
     *         sont traites pareil
     */
    public Mono<Map<String, String>> pour(String destinataireId) {
        if (urlDuService.isBlank() || destinataireId == null || destinataireId.isBlank()) {
            return Mono.just(Map.of());
        }

        Mono<Map<String, String>> appel = webClient.get()
                .uri(urlDuService + CHEMIN + destinataireId)
                .header(EN_TETE_SECRET, secretInterne)
                .retrieve()
                // 4xx : pas de preferences, ou personne inconnue. Une reponse,
                // pas une panne — et surtout pas quelque chose a reessayer.
                .onStatus(HttpStatusCode::is4xxClientError, reponse -> Mono.empty())
                .bodyToFlux(Preference.class)
                .filter(p -> p.key() != null && p.value() != null)
                // `collect` avec une fabrique typee, et non Collectors.toMap :
                // toMap leve sur une cle en double, et rien ne garantit que le
                // magasin de preferences n'en rende jamais deux. Une collecte
                // qui ecrase est preferable a une notification perdue.
                .<Map<String, String>>collect(HashMap::new,
                        (carte, p) -> carte.put(p.key(), p.value()));

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                .onErrorResume(erreur -> {
                    // L'identifiant n'est PAS journalise : donnee personnelle,
                    // et ce journal part vers Loki. Le nom de la classe d'erreur
                    // suffit a distinguer un delai d'un refus de connexion.
                    log.warn("Consentements non lus ({}) : la politique de defaut "
                            + "s'applique pour cette notification",
                            erreur.getClass().getSimpleName());
                    return Mono.just(Map.of());
                });
    }
}
