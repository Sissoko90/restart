package com.abdatytch.media_service.web;

import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.repository.DocumentTypeConfigRepository;
import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Les liens signes, pour un service qui a deja decide qu'on pouvait voir.
 *
 * <h2>Le probleme que cet endpoint resout</h2>
 *
 * {@code downloadDocument} ne sert un document qu'a son deposant. C'est juste
 * pour un RCCM ou une piece d'identite, et cela rendrait une photo de produit
 * invisible pour tout acheteur : une place de marche dont les photos ne
 * s'affichent pas n'en est pas une.
 *
 * <h2>LA CONFIANCE EST BORNEE PAR LE TYPE, PAS PAR L'APPELANT</h2>
 *
 * Cet endpoint ne demande pas qui regarde. Il fait confiance a l'appelant
 * interne pour avoir decide — market-service connait la portee du catalogue,
 * pas ce service.
 *
 * <p>Mais cette confiance est <b>bornee</b> : seuls les types marques
 * {@code signableSansProprietaire} peuvent etre signes. Un appelant interne
 * compromis n'obtient donc pas un lien vers une piece d'identite, parce que
 * aucun type d'identite ne porte ce drapeau. C'est la difference entre faire
 * confiance a quelqu'un et lui donner les clefs.
 *
 * <h2>Un document refuse est ABSENT de la reponse</h2>
 *
 * Pas d'erreur, pas de message : la carte rendue ne contient que ce qui a pu
 * etre signe. Distinguer « type non signable » de « document inexistant »
 * apprendrait a un appelant quels identifiants existent.
 *
 * <h2>Deux controles protegent ce chemin</h2>
 *
 * Le pair TCP prive, verifie par {@code GatewayAccessWebFilter}, et le secret
 * partage, compare a temps constant par {@code AutorisationInterne}. Aucune
 * passerelle ne route {@code /internal/**} : c'est ce qui le rend injoignable
 * depuis l'exterieur.
 */
@RestController
@RequestMapping(InternalMediaController.CHEMIN)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Interne", description = "Appels de service a service. Non route par la passerelle.")
public class InternalMediaController {

    static final String CHEMIN = "/internal/media";

    /**
     * La validite demandee.
     *
     * <p>{@code StorageService} la plafonne de son cote : c'est lui qui signe,
     * donc c'est lui qui porte la consequence d'un lien trop long.
     */
    private static final Duration VALIDITE = Duration.ofMinutes(15);

    /**
     * Le nombre de documents signes en un appel.
     *
     * <p>Une page de catalogue en demande une vingtaine. Le plafond empeche un
     * appel de faire autant de lectures que l'appelant le souhaite.
     */
    private static final int MAXIMUM_PAR_APPEL = 60;

    private final MediaFileRepository fichiers;
    private final DocumentTypeConfigRepository types;
    private final StorageService stockage;

    @PostMapping("/liens")
    @Operation(summary = "Liens signes pour des documents publiables",
            description = "Appel de service a service. Exige l'en-tete "
                    + "X-Internal-Secret. Seuls les types marques signables "
                    + "sans proprietaire sont servis ; les autres sont absents "
                    + "de la reponse.")
    public Mono<Map<String, String>> liens(@Valid @RequestBody Demande demande) {
        List<String> demandes = demande.documentIds().stream()
                .distinct()
                .limit(MAXIMUM_PAR_APPEL)
                .toList();

        return Flux.fromIterable(demandes)
                .flatMap(this::signerSiPermis)
                .collectMap(Lien::documentId, Lien::url);
    }

    /**
     * Signe un document, ou rend vide.
     *
     * <p>L'ordre des verifications va du moins couteux au plus couteux : le
     * fichier d'abord — une lecture par identifiant —, son type ensuite, et la
     * signature en dernier.
     */
    private Mono<Lien> signerSiPermis(String documentId) {
        return fichiers.findById(documentId)
                .flatMap(fichier -> types.findByCode(fichier.getDocumentTypeCode())
                        .filter(type -> type.estSignableSansProprietaire())
                        .flatMap(type -> signer(fichier, documentId))
                        // Type inconnu ou non signable : rien, sans journal de
                        // niveau WARN. Un catalogue mixte — photos publiques et
                        // pieces privees — produirait sinon du bruit a chaque
                        // page.
                        .switchIfEmpty(Mono.fromRunnable(() -> log.debug(
                                "Document {} non signable : type {}", documentId,
                                fichier.getDocumentTypeCode()))));
    }

    private Mono<Lien> signer(MediaFile fichier, String documentId) {
        return stockage.genererLienSigne(fichier.getStoragePath(), VALIDITE)
                .map(url -> new Lien(documentId, url));
    }

    /**
     * Les identifiants a signer.
     *
     * <p>Un POST et non un GET : une liste de soixante identifiants dans une
     * chaine de requete finirait dans les journaux d'acces de chaque
     * intermediaire, et toucherait les limites de longueur d'URL.
     */
    public record Demande(@NotEmpty(message = "Au moins un identifiant est requis")
                          List<String> documentIds) {
    }

    /** Paire interne, pour que {@code collectMap} ait deux champs a lire. */
    private record Lien(String documentId, String url) {
    }
}
