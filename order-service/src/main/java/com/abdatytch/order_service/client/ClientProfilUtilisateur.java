package com.abdatytch.order_service.client;

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
 * Et rien d'autre, ou presque. Le pays est FIGE sur la commande et transmis a
 * market-service pour le controle de visibilite : il ne peut donc pas venir de
 * la requete, car un parametre laisserait quiconque acheter un article d'un
 * autre pays en se declarant d'ailleurs.
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
 * <h2>UNE PANNE DE user-service EMPECHE DE COMMANDER, et c'est voulu</h2>
 *
 * C'est la difference avec market-service, qui se contente de montrer moins.
 * Ici le pays n'est pas un filtre d'affichage : il est inscrit sur la commande,
 * il sert au controle de visibilite chez market-service, et il determinera plus
 * tard la livraison et la fiscalite.
 *
 * <p>Le client rend donc {@link #INCONNU} — pays nul — et c'est l'appelant qui
 * REFUSE de creer la commande. Degrader serait pire que refuser : une commande
 * passee avec un pays faux est une commande qu'il faudra annuler apres avoir
 * reserve du stock, peut-etre encaisse, et qu'un acheteur aura vue aboutir.
 *
 * <p>La degradation silencieuse est acceptable sur une LECTURE ; elle ne l'est
 * pas sur une ecriture qui engage de l'argent.
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
                    + "resolu, donc AUCUNE COMMANDE ne pourra etre creee. "
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
                    // INCONNU et non une erreur : c'est l'APPELANT qui decide
                    // quoi faire d'un pays inconnu, et les deux appelants n'en
                    // font pas la meme chose. Afficher un panier sans pays est
                    // acceptable ; creer une commande sans pays ne l'est pas.
                    log.warn("Profil non resolu ({}) : aucune commande ne pourra "
                            + "etre creee pour ce compte", erreur.getClass().getSimpleName());
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
