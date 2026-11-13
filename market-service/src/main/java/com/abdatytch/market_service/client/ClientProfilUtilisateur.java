package com.abdatytch.market_service.client;

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
 * Le profil applicatif d'un compte, chez user-service.
 *
 * <h2>Ce qu'on vient y chercher : le PAYS</h2>
 *
 * Et rien d'autre, ou presque. Le pays decide de ce qu'un particulier voit du
 * catalogue, donc il ne peut pas venir de la requete : un parametre laisserait
 * quiconque elargir sa portee en se declarant d'ailleurs.
 *
 * <h2>Pourquoi un appel direct et non la passerelle</h2>
 *
 * {@code /internal/**} n'est route par aucune passerelle — c'est ce qui rend
 * cette surface injoignable depuis l'exterieur. L'appel y va donc en direct, et
 * deux controles l'autorisent du cote de user-service : le pair TCP prive, et
 * le secret partage.
 *
 * <h2>Le delai est a l'INTERIEUR, le disjoncteur a l'exterieur</h2>
 *
 * Pose a l'interieur, le disjoncteur ne verrait jamais une dependance figee —
 * l'appel ne se terminant pas. Meme ordre que partout ailleurs dans ce depot.
 *
 * <h2>Une panne de user-service ne rend pas le catalogue vide</h2>
 *
 * Elle rend {@link #INCONNU}, dont le pays est nul. Un particulier sans pays
 * connu ne voit alors aucun produit reserve a un pays — il voit moins, jamais
 * plus. C'est la degradation qu'il faut : l'inverse, supposer un pays par
 * defaut, montrerait a quelqu'un un catalogue qui ne le concerne pas.
 */
@Component
public class ClientProfilUtilisateur {

    private static final Logger log =
            LoggerFactory.getLogger(ClientProfilUtilisateur.class);

    static final String DISJONCTEUR = "user-service-profil";
    static final String CHEMIN = "/internal/users/auth/";
    static final String EN_TETE_SECRET = "X-Internal-Secret";

    /** Profil non resolu : aucun pays, donc aucune portee elargie. */
    public static final Profil INCONNU = new Profil(null, null, null);

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;

    public ClientProfilUtilisateur(
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
            log.error("user-service.url n'est pas configure : aucun pays ne sera "
                    + "resolu, et les particuliers ne verront aucun produit. "
                    + "Renseigner USER_SERVICE_URL.");
        }
    }

    /** @param authUserId le {@code sub} du porteur */
    public Mono<Profil> pour(String authUserId) {
        if (urlDuService.isBlank() || authUserId == null || authUserId.isBlank()) {
            return Mono.just(INCONNU);
        }

        Mono<Profil> appel = webClient.get()
                .uri(urlDuService + CHEMIN + authUserId)
                .header(EN_TETE_SECRET, secretInterne)
                .retrieve()
                // 404 : compte sans profil applicatif. Une reponse normale, et
                // non une erreur — sans quoi le disjoncteur compterait comme
                // panne un cas parfaitement attendu.
                .onStatus(HttpStatusCode::is4xxClientError, reponse -> Mono.empty())
                .bodyToMono(Profil.class)
                .defaultIfEmpty(INCONNU);

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                // L'identifiant n'est PAS journalise : c'est le `sub` du
                // porteur, donc une donnee personnelle, et ce journal part vers
                // Loki.
                .onErrorResume(erreur -> {
                    log.warn("Profil non resolu ({}) : la portee du catalogue sera "
                            + "restreinte", erreur.getClass().getSimpleName());
                    return Mono.just(INCONNU);
                });
    }

    /**
     * Ce qu'on lit de {@code UserDetailDTO}.
     *
     * <p>{@code @JsonIgnoreProperties} : user-service en rend une vingtaine de
     * champs, et ce service n'en veut que trois. Les declarer tous ferait de ce
     * record un second exemplaire d'un contrat qui ne lui appartient pas, a
     * tenir d'accord a chaque evolution.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profil(String country, String role, String email) {

        /** Le pays, ou {@code null} s'il n'est pas connu. */
        public String pays() {
            return country == null || country.isBlank() ? null : country.trim();
        }

        /**
         * L'adresse, ou {@code null}.
         *
         * <p>Lue ICI et non saisie : prevenir un vendeur a une adresse qu'il
         * aurait lui-meme declaree permettrait de faire partir un courriel au
         * nom de la plateforme vers n'importe qui.
         */
        public String adresse() {
            return email == null || email.isBlank() ? null : email.trim();
        }
    }
}
