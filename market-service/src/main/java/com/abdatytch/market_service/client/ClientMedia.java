package com.abdatytch.market_service.client;

import com.abdatytch.commons.resilience.IDisjoncteurSortant;
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
import java.util.Map;

/**
 * Les liens signes des photos, chez media-service.
 *
 * <h2>Pourquoi ce service ne sert pas les images lui-meme</h2>
 *
 * Il ne detient aucun fichier, et ne doit pas en detenir : un second magasin,
 * c'est un second endroit ou un document d'identite peut fuir. media-service
 * signe une URL MinIO temporaire, et le navigateur telecharge ensuite
 * DIRECTEMENT depuis MinIO — ni ce service ni media-service ne transportent les
 * octets.
 *
 * <h2>La confiance est bornee du cote de media-service</h2>
 *
 * Cet appel ne dit pas qui regarde : la portee du catalogue est decidee ici,
 * avant. Mais media-service ne signe que les types marques signables sans
 * proprietaire, donc une demande portant l'identifiant d'une piece d'identite
 * ne rend rien — meme venue d'ici.
 *
 * <h2>Une panne ne fait pas disparaitre le produit</h2>
 *
 * Elle rend une carte VIDE : la fiche s'affiche sans ses images. Propager
 * l'erreur ferait disparaitre un article du catalogue parce qu'une photo n'a
 * pas pu etre signee, ce qui est hors de proportion.
 */
@Component
public class ClientMedia {

    private static final Logger log = LoggerFactory.getLogger(ClientMedia.class);

    static final String DISJONCTEUR = "media-service-document";
    static final String CHEMIN = "/internal/media/liens";
    static final String EN_TETE_SECRET = "X-Internal-Secret";

    private static final ParameterizedTypeReference<Map<String, String>> CARTE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final IDisjoncteurSortant disjoncteur;
    private final String urlDuService;
    private final String secretInterne;
    private final Duration delaiMaximal;

    public ClientMedia(WebClient.Builder constructeurWebClient,
                       IDisjoncteurSortant disjoncteur,
                       @Value("${media-service.url:}") String urlDuService,
                       @Value("${security.internal-secret:}") String secretInterne,
                       @Value("${media-service.timeout-seconds:5}") long delaiEnSecondes) {
        this.webClient = constructeurWebClient.build();
        this.disjoncteur = disjoncteur;
        this.urlDuService = urlDuService == null ? "" : urlDuService.trim();
        this.secretInterne = secretInterne == null ? "" : secretInterne;
        this.delaiMaximal = Duration.ofSeconds(delaiEnSecondes);

        disjoncteur.declarer(DISJONCTEUR);

        if (this.urlDuService.isBlank()) {
            log.error("media-service.url n'est pas configure : aucune photo ne "
                    + "sera affichee. Renseigner MEDIA_SERVICE_URL.");
        }
    }

    /**
     * Signe ce qui peut l'etre.
     *
     * @param documentIds les identifiants rattaches aux produits
     * @return identifiant vers URL signee. Une entree ABSENTE signifie « non
     *         signable » — type prive, document inexistant, ou repli local
     *         chiffre — et l'appelant omet simplement la photo
     */
    public Mono<Map<String, String>> liensSignes(List<String> documentIds) {
        if (urlDuService.isBlank() || documentIds == null || documentIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        Mono<Map<String, String>> appel = webClient.post()
                .uri(urlDuService + CHEMIN)
                .header(EN_TETE_SECRET, secretInterne)
                .bodyValue(new Demande(documentIds))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, reponse -> Mono.empty())
                .bodyToMono(CARTE)
                .defaultIfEmpty(Map.of());

        return disjoncteur.proteger(DISJONCTEUR, appel.timeout(delaiMaximal))
                .onErrorResume(erreur -> {
                    log.warn("Liens de photos non obtenus ({}) : les fiches "
                                    + "s'afficheront sans images",
                            erreur.getClass().getSimpleName());
                    return Mono.just(Map.of());
                });
    }

    /** Doit correspondre a {@code InternalMediaController.Demande}. */
    private record Demande(List<String> documentIds) {
    }
}
