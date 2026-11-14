package com.abdatytch.order_service.client;

import com.abdatytch.commons.resilience.IDisjoncteurSortant;
import com.abdatytch.order_service.exception.OrderException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Le catalogue en LECTURE, chez market-service.
 *
 * <h2>Un disjoncteur distinct de celui du stock, et c'est le point</h2>
 *
 * {@code market-service-catalogue} ici, {@code market-service-stock} dans
 * {@link ClientMarketStock}. Les deux visent le meme service et tombent
 * SEPAREMENT.
 *
 * <p>Sans cette separation, un affichage de panier qui ralentit — parce que
 * quelqu'un consulte un panier de quarante lignes — ouvrirait le disjoncteur
 * partage et fermerait le passage en caisse pour tout le monde. Le chemin qui
 * encaisse ne doit pas dependre de la sante du chemin qui affiche.
 *
 * <h2>Pourquoi aucun repli silencieux</h2>
 *
 * Si market-service ne repond pas, le panier n'est pas affiche. Rendre un
 * panier sans prix ni disponibilite serait pire qu'une erreur : l'acheteur
 * croirait son panier vide, ou commanderait a l'aveugle.
 */
@Component
public class ClientMarketCatalogue {

    private static final Logger log = LoggerFactory.getLogger(ClientMarketCatalogue.class);

    static final String DISJONCTEUR = "market-service-catalogue";
    static final String CHEMIN = "/internal/market/stock/decrire";
    static final String CHEMIN_PORTEE = "/internal/market/comptes/{id}/portee";
    static final String EN_TETE_SECRET = "X-Internal-Secret";

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;

    public ClientMarketCatalogue(
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
            log.error("market-service.url n'est pas configure : aucun panier ne pourra "
                    + "etre affiche. Renseigner MARKET_SERVICE_URL.");
        }
    }

    /**
     * Decrit les articles d'un panier : nom, prix, disponibilite.
     *
     * <p>La demande porte la PORTEE de l'acheteur, parce que c'est
     * market-service qui applique la regle de visibilite. Un article qui a
     * cesse d'etre visible pour cet acheteur revient avec
     * {@code disponible = false} plutot que d'etre omis : omettre ferait
     * disparaitre une ligne sans explication.
     */
    public Mono<List<LigneDecrite>> decrire(ClientMarketStock.ReservationDeStock demande) {
        if (urlDuService.isBlank()) {
            return Mono.error(OrderException.dependanceIndisponible("market-service"));
        }

        Mono<List<LigneDecrite>> appel = webClient.post()
                .uri(urlDuService + CHEMIN)
                .header(EN_TETE_SECRET, secretInterne)
                .bodyValue(demande)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<LigneDecrite>>() { })
                // Un corps vide pour une demande non vide : market-service a
                // repondu 200 sans rien decrire. Le laisser passer afficherait
                // un panier vide a quelqu'un qui a des articles dedans.
                .switchIfEmpty(Mono.error(() -> new IllegalStateException(
                        "market-service a repondu sans corps a une description")));

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                .onErrorMap(erreur -> erreur instanceof OrderException ? erreur
                        : OrderException.dependanceIndisponible("market-service"));
    }

    /**
     * La boutique d'un compte, sa qualite de professionnel, son pays.
     *
     * <p>Pour servir « les commandes de MA boutique » : cette information vit
     * chez market-service, avec le dossier de validation.
     *
     * <p>Un echec rend une portee SANS boutique plutot qu'une erreur. La
     * consequence est bornee et lisible : l'acheteur garde ses propres
     * commandes, et la vue boutique est vide. Faire echouer la lecture de
     * commandes personnelles parce que la boutique n'a pas pu etre resolue
     * serait disproportionne.
     */
    public Mono<PorteeDuCompte> porteeDuCompte(String authUserId) {
        if (urlDuService.isBlank() || authUserId == null || authUserId.isBlank()) {
            return Mono.just(PorteeDuCompte.SANS_BOUTIQUE);
        }

        Mono<PorteeDuCompte> appel = webClient.get()
                .uri(urlDuService + CHEMIN_PORTEE, authUserId)
                .header(EN_TETE_SECRET, secretInterne)
                .retrieve()
                .bodyToMono(PorteeDuCompte.class)
                .defaultIfEmpty(PorteeDuCompte.SANS_BOUTIQUE);

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                .onErrorResume(erreur -> {
                    log.warn("Portee de compte non resolue ({}) : la vue boutique "
                            + "sera vide", erreur.getClass().getSimpleName());
                    return Mono.just(PorteeDuCompte.SANS_BOUTIQUE);
                });
    }

    /** Ce que market-service sait d'un compte. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PorteeDuCompte(UUID boutiqueId, boolean professionnelle, String pays) {

        /** Le cas le plus courant : un acheteur sans boutique. */
        public static final PorteeDuCompte SANS_BOUTIQUE =
                new PorteeDuCompte(null, false, null);
    }

    /**
     * Un article decrit.
     *
     * <p>Les montants sont des objets et non des primitives : une ligne
     * indisponible revient sans prix, et un {@code long} y vaudrait zero — un
     * article « a zero franc » plutot qu'« un article dont on ignore le prix ».
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LigneDecrite(
            UUID produitId,
            UUID varianteId,
            UUID boutiqueId,
            String pays,
            String nomProduit,
            String couleur,
            String taille,
            String sku,
            Long prixUnitaire,
            String devise,
            int quantiteDemandee,
            boolean disponible,
            String motif) {
    }
}
