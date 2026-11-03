package com.abdatytch.media_service.erasure;

import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**

 * Efface les pièces justificatives d'une personne.
 *
 * <p>C'est la donnée la plus sensible que détient la plateforme : cartes
 * d'identité, permis, registres de commerce. Un effacement incomplet ici ne
 * laisse pas une trace anodine, il laisse une pièce d'identité.
 *
 * <h2>Deux emplacements, et l'ordre compte</h2>
 *
 * Le fichier vit dans le stockage objet ; ses métadonnées vivent dans MongoDB.
 * <b>Le fichier est supprimé en premier.</b> L'ordre inverse — métadonnées puis
 * fichier — perdrait le chemin de stockage en cas d'interruption, et le fichier
 * deviendrait irrécupérable donc ineffaçable : présent dans le stockage, plus
 * référencé nulle part, invisible à tout audit ultérieur.
 *
 * <h2>Le chiffrement ne dispense pas d'effacer</h2>
 *
 * Les documents sont chiffrés en AES-256-GCM, mais les clés dérivent d'une clé
 * maîtresse commune que l'on conserve. Tant que celle-ci existe, un document
 * chiffré reste déchiffrable : il faut donc bel et bien le supprimer, et non se
 * contenter d'oublier sa clé.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentErasureService {

    private final MediaFileRepository mediaFileRepository;
    private final StorageService storageService;

    /**

     * @param userId identifiant du porteur
     * @return le nombre de documents effacés
     */
    public Mono<Integer> erase(String userId) {
        return mediaFileRepository.findByUserId(userId)
                .concatMap(this::eraseDocument)
                .count()
                .map(Long::intValue)
                .doOnSuccess(n -> log.info(" {} document(s) effacé(s) pour {}", n, userId));
    }

    /**

     * Chaque étape est enveloppée dans {@link Mono#defer} : {@code .then(x)}
     * évalue {@code x} <b>à l'assemblage</b>, c'est-à-dire avant que l'étape
     * précédente n'ait réussi. Sans cela, la suppression des métadonnées serait
     * construite alors même que la suppression du fichier est en train d'échouer.
     * Le Mono ne serait pas souscrit, donc rien ne serait supprimé — mais le code
     * exprimerait le contraire de son intention, et la moindre modification
     * ultérieure le rendrait faux.
     */
    private Mono<MediaFile> eraseDocument(MediaFile document) {
        return supprimerDuStockage(document)
                .then(Mono.defer(() -> supprimerVignette(document)))
                .then(Mono.defer(() -> mediaFileRepository.delete(document)))
                .thenReturn(document)
                .doOnSuccess(d -> log.info(" Document {} effacé (type {})",
                        d.getId(), d.getDocumentTypeCode()));
    }

    private Mono<Void> supprimerDuStockage(MediaFile document) {
        String path = document.getStoragePath() != null
                ? document.getStoragePath()
                : document.getFileName();

        if (path == null || path.isBlank()) {
            return Mono.empty();
        }

        return storageService.deleteFile(path)
                .onErrorResume(e -> {
                    // Un échec ici est grave : le document resterait dans le
                    // stockage. On le signale fort et on laisse l'erreur remonter
                    // pour que l'événement soit rejoué plutôt qu'acquitté à tort.
                    log.error(" Pièce justificative NON supprimée du stockage ({}) : {}",
                            path, e.getMessage());
                    return Mono.error(e);
                });
    }

    /**

     * Les vignettes sont des extraits lisibles du document original : les
     * oublier reviendrait à conserver une version dégradée de la pièce
     * d'identité.
     */
    private Mono<Void> supprimerVignette(MediaFile document) {
        String thumbnail = document.getThumbnailPath();
        if (thumbnail == null || thumbnail.isBlank()) {
            return Mono.empty();
        }
        return storageService.deleteFile(thumbnail)
                .onErrorResume(e -> {
                    log.warn(" Vignette non supprimée ({}) : {}", thumbnail, e.getMessage());
                    return Mono.empty();
                });
    }
}
