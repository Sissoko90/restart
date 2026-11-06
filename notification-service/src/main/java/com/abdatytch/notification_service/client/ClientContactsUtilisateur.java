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

/**
 * Ou joindre une personne : la question est posee a user-service.
 *
 * <h2>Pourquoi user-service et pas une copie locale</h2>
 *
 * user-service detient les contacts. Les recopier ici donnerait deux adresses
 * pour une personne, et un message partirait a l'ancienne apres un changement —
 * le defaut precis que {@code DemandeDeNotification} evite en transportant un
 * identifiant plutot qu'une coordonnee.
 *
 * <h2>AUCUN cache local, et c'est un choix mesure</h2>
 *
 * {@code getUserByAuthUserId} de user-service lit d'abord un cache Redis
 * ({@code CacheDesComptes}, retention 15 minutes) <b>invalide a chaque
 * modification du profil</b>. L'appel est donc deja bon marche, et la fraicheur
 * est garantie par le detenteur de la donnee.
 *
 * <p>Un cache ici-meme serait une seconde source de verite, avec sa propre duree
 * de vie et <b>aucun moyen d'etre invalide</b> : un contact modifie continuerait
 * a recevoir a l'ancienne adresse pendant toute cette duree. C'est exactement ce
 * que ce depot paie en boucle, et ce chemin-la porte des alertes de securite.
 *
 * <h2>L'identifiant concorde, verifie</h2>
 *
 * {@code DemandeDeNotification.destinataireId} est le {@code sub} du jeton.
 * {@code GET /users/me} de user-service prend {@code Authentication::getName} —
 * ce meme {@code sub} — et le passe tel quel a {@code getUserByAuthUserId}. Les
 * deux designent donc la meme personne, sans conversion.
 *
 * <h2>404 n'est PAS une erreur</h2>
 *
 * C'est une reponse : cette personne n'a pas de profil chez user-service. Le
 * distinguer d'une panne est ce qui decide du sort de la notification — « aucune
 * adresse connue » est definitif, « user-service injoignable » doit etre
 * reessaye. Voir {@code ResolveurDeCoordonnees}.
 */
@Component
public class ClientContactsUtilisateur {

    private static final Logger log =
            LoggerFactory.getLogger(ClientContactsUtilisateur.class);

    static final String DISJONCTEUR = "user-service-contacts";

    /** Non route par la passerelle, comme tout {@code /internal/**}. */
    static final String CHEMIN = "/internal/users/auth/";

    static final String EN_TETE_SECRET = "X-Internal-Secret";

    /**
     * Les coordonnees d'une personne.
     *
     * <p>Record LOCAL et non le {@code UserDetailDTO} de user-service : ce
     * module n'a besoin que de deux champs sur quatorze, et partager le DTO
     * ferait d'un ajout de champ chez eux une recompilation ici.
     *
     * <p>{@code ignoreUnknown} pour la meme raison : la reponse porte douze
     * champs que nous ignorons, et un treizieme ajoute demain ne doit pas faire
     * echouer la lecture.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contacts(String email, String phoneNumber) {
    }

    /** Rendu quand la personne n'a pas de profil : une reponse, pas une panne. */
    public static final Contacts AUCUN = new Contacts(null, null);

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;

    public ClientContactsUtilisateur(
            WebClient.Builder constructeurWebClient,
            IDisjoncteurSortant disjoncteur,
            @Value("${user-service.url:}") String urlDuService,
            @Value("${security.internal-secret:}") String secretInterne,
            @Value("${user-service.timeout-seconds:3}") long delaiEnSecondes) {

        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.urlDuService = urlDuService == null ? "" : urlDuService.trim();
        this.secretInterne = secretInterne == null ? "" : secretInterne;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);

        disjoncteur.declarer(DISJONCTEUR);

        if (this.urlDuService.isBlank()) {
            log.error("user-service.url n'est pas configure : aucune coordonnee ne "
                    + "pourra etre resolue, et les canaux COURRIEL, SMS et PUSH "
                    + "refuseront toute remise. Renseigner USER_SERVICE_URL.");
        }
    }

    /**
     * @param destinataireId le {@code sub} du porteur
     * @return les coordonnees, {@link #AUCUN} si la personne n'a pas de profil,
     *         ou une erreur si user-service n'a pas repondu
     */
    public Mono<Contacts> pour(String destinataireId) {
        if (urlDuService.isBlank() || destinataireId == null || destinataireId.isBlank()) {
            // Pas d'erreur : sans adresse de service ni identifiant, il n'y a
            // rien a reessayer. « Aucune coordonnee » est la reponse exacte.
            return Mono.just(AUCUN);
        }

        Mono<Contacts> appel = webClient.get()
                .uri(urlDuService + CHEMIN + destinataireId)
                .header(EN_TETE_SECRET, secretInterne)
                .retrieve()
                // 404 : pas de profil. Rendu comme une reponse normale, et non
                // propage en erreur — sans quoi une notification vers quelqu'un
                // sans profil serait reessayee indefiniment.
                .onStatus(HttpStatusCode::is4xxClientError, reponse -> Mono.empty())
                .bodyToMono(Contacts.class)
                // Corps vide — ce que rend `ResponseEntity.notFound()` — ou
                // corps d'erreur dont aucun champ ne nous concerne : les deux
                // aboutissent a « aucune coordonnee », qui est la bonne reponse.
                .defaultIfEmpty(AUCUN);

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                // L'identifiant n'est PAS journalise : c'est le `sub` du
                // porteur, donc une donnee personnelle, et ce journal part vers
                // Loki. Le nom de la classe d'erreur suffit a distinguer un
                // delai d'un refus de connexion.
                .doOnError(erreur -> log.warn(
                        "Coordonnees non resolues ({}) : les canaux a coordonnee "
                                + "seront marques ECHOUE et repris",
                        erreur.getClass().getSimpleName()));
    }
}
