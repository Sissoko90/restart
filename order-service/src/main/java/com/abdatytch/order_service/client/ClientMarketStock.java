package com.abdatytch.order_service.client;

import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.order_service.exception.OrderException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Le stock, chez market-service.
 *
 * <h2>Deux disjoncteurs pour un seul service, et ce n'est pas une erreur</h2>
 *
 * {@code market-service-stock} ici, {@code market-service-catalogue} pour les
 * lectures. Les deux visent le meme service et tombent separement : une lecture
 * de catalogue qui ralentit ne doit pas fermer le chemin qui reserve du stock,
 * parce que ce chemin-la est celui du passage en caisse.
 *
 * <h2>Une indisponibilite de stock n'est PAS une panne</h2>
 *
 * market-service rend 409 quand un article manque. Ce 409 est une REPONSE, et
 * il est traduit en {@link OrderException#stockIndisponible()} sans passer par
 * le disjoncteur : une rafale d'articles epuises ne doit pas couper l'acces a
 * un service parfaitement sain. C'est la meme regle que
 * {@code DisjoncteurSortantConfig} applique de l'autre cote.
 *
 * <h2>Rien n'est mis en cache</h2>
 *
 * Ni la reservation, qui ecrit, ni son resultat. Un stock en cache est un stock
 * faux, et ici le faux se paie en articles vendus deux fois.
 */
@Component
public class ClientMarketStock {

    private static final Logger log = LoggerFactory.getLogger(ClientMarketStock.class);

    static final String DISJONCTEUR = "market-service-stock";
    static final String CHEMIN_RESERVER = "/internal/market/stock/reserver";
    static final String CHEMIN_RENDRE = "/internal/market/stock/rendre";
    static final String EN_TETE_SECRET = "X-Internal-Secret";

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;

    public ClientMarketStock(
            WebClient.Builder constructeurWebClient,
            IDisjoncteurSortant disjoncteur,
            @Value("${market-service.url:}") String urlDuService,
            @Value("${security.internal-secret:}") String secretInterne,
            @Value("${market-service.timeout-seconds:8}") long delaiEnSecondes) {

        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.urlDuService = urlDuService == null ? "" : urlDuService.trim();
        this.secretInterne = secretInterne == null ? "" : secretInterne;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);

        disjoncteur.declarer(DISJONCTEUR);

        if (this.urlDuService.isBlank()) {
            log.error("market-service.url n'est pas configure : AUCUNE COMMANDE ne "
                    + "pourra etre creee, le stock ne pouvant pas etre reserve. "
                    + "Renseigner MARKET_SERVICE_URL.");
        }
    }

    /**
     * Reserve le stock d'un panier, en tout ou rien.
     *
     * <p>Rend l'instantane de chaque ligne : nom, prix, couleur, taille,
     * boutique, devise. Ces valeurs figent les lignes de commande SANS
     * relecture — une relecture laisserait le prix changer entre la reservation
     * et l'enregistrement.
     *
     * @throws OrderException 503 si market-service est injoignable, 409 si un
     *         article manque. Les deux se distinguent, parce qu'ils n'appellent
     *         pas la meme conduite : on reessaie plus tard dans le premier cas,
     *         on previent l'acheteur dans le second
     */
    public Mono<List<LigneReservee>> reserver(ReservationDeStock demande) {
        if (urlDuService.isBlank()) {
            return Mono.error(OrderException.dependanceIndisponible("market-service"));
        }

        Mono<List<LigneReservee>> appel = webClient.post()
                .uri(urlDuService + CHEMIN_RESERVER)
                .header(EN_TETE_SECRET, secretInterne)
                .bodyValue(demande)
                // Le 409 est intercepte AVANT le disjoncteur et traduit en une
                // erreur metier : sans cela, le disjoncteur compterait comme
                // panne un cas parfaitement attendu, et une rupture de stock sur
                // un article populaire fermerait le passage en caisse pour tout
                // le monde.
                .retrieve()
                .onStatus(statut -> statut.value() == 409,
                        reponse -> Mono.error(OrderException.stockIndisponible()))
                .onStatus(HttpStatusCode::is4xxClientError,
                        reponse -> Mono.error(OrderException.introuvable()))
                .bodyToMono(new ParameterizedTypeReference<List<LigneReservee>>() { })
                // Un corps vide la ou on attend des lignes : market-service a
                // repondu 200 sans rien reserver. Le laisser passer creerait une
                // commande a zero ligne, donc a zero franc, parfaitement valide
                // pour la base et absurde pour tout le monde.
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "market-service a repondu sans corps a une reservation")));

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                .onErrorMap(erreur -> erreur instanceof OrderException ? erreur
                        : OrderException.dependanceIndisponible("market-service"));
    }

    /**
     * Rend du stock : commande impayee annulee, ou retour recu.
     *
     * <h3>L'echec est RENDU, et non avale</h3>
     *
     * Un {@code Mono<Void>} qui se termine ne distingue pas « stock rendu » de
     * « rien rendu ». Ici la difference est du stock perdu pour une boutique,
     * invisible jusqu'au prochain inventaire. L'appelant recoit donc le nombre
     * de lignes creditees et doit le journaliser.
     *
     * @return le nombre de lignes creditees chez market-service
     */
    public Mono<Integer> rendre(RestitutionDeStock demande) {
        if (urlDuService.isBlank()) {
            return Mono.error(OrderException.dependanceIndisponible("market-service"));
        }

        Mono<Integer> appel = webClient.post()
                .uri(urlDuService + CHEMIN_RENDRE)
                .header(EN_TETE_SECRET, secretInterne)
                .bodyValue(demande)
                .retrieve()
                .bodyToMono(Integer.class)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "market-service a repondu sans corps a une restitution")));

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                .onErrorMap(erreur -> erreur instanceof OrderException ? erreur
                        : OrderException.dependanceIndisponible("market-service"));
    }

    // ==================================================================
    // LE CONTRAT AVEC market-service
    // ==================================================================
    // Les trois records sont declares ICI et non dans dto/request ou
    // dto/response : ce ne sont pas des messages recus ou rendus par ce
    // service, c'est le contrat d'un appel SORTANT. Les mettre parmi les DTO
    // d'entree laisserait croire qu'un client peut les poster, et parmi les DTO
    // de sortie qu'ils sont rendus a quelqu'un.

    /**
     * La demande de reservation : UN IDENTIFIANT et des lignes.
     *
     * <p>Aucune portee n'est transmise — ni pays, ni boutique, ni qualite de
     * professionnel. market-service les resout lui-meme depuis l'identifiant.
     *
     * <p>Ce n'est pas une economie de champs, c'est une question de securite :
     * si ce service annoncait la portee, un defaut ici — ou un appel forge —
     * suffirait a se declarer professionnel et a acheter un article reserve.
     * La boutique d'un compte et sa qualite de professionnel vivent chez
     * market-service, et c'est lui qui conclut.
     */
    public record ReservationDeStock(
            String authUserId,
            List<Ligne> lignes) {

        public record Ligne(java.util.UUID produitId, java.util.UUID varianteId,
                            int quantite) {
        }
    }

    /** Le corps d'une restitution. Aucune portee : rendre du stock n'expose rien. */
    public record RestitutionDeStock(List<LigneARendre> lignes) {
    }

    public record LigneARendre(java.util.UUID varianteId, int quantite) {
    }

    /**
     * Une ligne reservee, avec l'instantane de ce qui a ete pris.
     *
     * <p>{@code @JsonIgnoreProperties} : market-service peut enrichir sa
     * reponse sans casser ce client. Declarer tous les champs ferait de ce
     * record un second exemplaire d'un contrat qui ne lui appartient pas.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LigneReservee(
            java.util.UUID produitId,
            java.util.UUID varianteId,
            java.util.UUID boutiqueId,
            String pays,
            String nomProduit,
            String couleur,
            String taille,
            String sku,
            long prixUnitaire,
            String devise,
            int quantite) {
    }
}
