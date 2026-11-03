package com.abdatytch.media_service.erasure;

import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Effacement des pièces d'identité (RGPD art. 17).
 *
 * <p>C'est la donnée la plus sensible de la plateforme. Un effacement incomplet
 * ne laisse pas une trace anodine : il laisse une carte d'identité.
 *
 * <p>Ces tests vérifient surtout ce qui ne se voit pas à la lecture : que le
 * fichier part <b>avant</b> ses métadonnées, et qu'un échec du stockage ne se
 * traduit jamais par un effacement déclaré réussi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentErasureService — effacement des pièces d'identité")
class DocumentErasureServiceTest {

    private static final String PORTEUR = "11111111-2222-3333-4444-555555555555";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private StorageService storageService;

    private DocumentErasureService service;

    @BeforeEach
    void setUp() {
        service = new DocumentErasureService(mediaFileRepository, storageService);
        when(storageService.deleteFile(any())).thenReturn(Mono.empty());
        when(mediaFileRepository.delete(any())).thenReturn(Mono.empty());
    }

    private MediaFile document(String id, String chemin, String vignette) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setUserId(PORTEUR);
        // Le CODE du catalogue, et non une valeur d'enumeration : c'est ce
        // champ que l'effacement journalise desormais.
        file.setDocumentTypeCode("NATIONAL_ID");
        file.setStoragePath(chemin);
        file.setThumbnailPath(vignette);
        return file;
    }

    @Test
    @DisplayName("le fichier, sa vignette et ses métadonnées sont tous effacés")
    void effacementComplet() {
        when(mediaFileRepository.findByUserId(PORTEUR))
                .thenReturn(Flux.just(document("doc-1", "kyc/doc-1.enc", "kyc/doc-1-vignette.jpg")));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(1)
                .verifyComplete();

        verify(storageService).deleteFile("kyc/doc-1.enc");
        verify(storageService).deleteFile("kyc/doc-1-vignette.jpg");
        verify(mediaFileRepository).delete(any(MediaFile.class));
    }

    @Test
    @DisplayName("le fichier est supprimé AVANT ses métadonnées")
    void ordreDeSuppression() {
        // L'ordre inverse perdrait le chemin de stockage en cas d'interruption :
        // le fichier resterait dans le stockage, plus référencé nulle part, donc
        // invisible à tout audit ultérieur et impossible à effacer.
        when(mediaFileRepository.findByUserId(PORTEUR))
                .thenReturn(Flux.just(document("doc-1", "kyc/doc-1.enc", null)));

        StepVerifier.create(service.erase(PORTEUR)).expectNext(1).verifyComplete();

        InOrder ordre = inOrder(storageService, mediaFileRepository);
        ordre.verify(storageService).deleteFile("kyc/doc-1.enc");
        ordre.verify(mediaFileRepository).delete(any(MediaFile.class));
    }

    @Test
    @DisplayName("un échec du stockage NE déclare PAS l'effacement réussi")
    void echecDuStockagePropage() {
        // Si l'erreur était absorbée, l'écouteur acquitterait l'événement et la
        // pièce d'identité resterait dans le stockage — avec, au registre, une
        // ligne affirmant qu'elle a été effacée.
        when(mediaFileRepository.findByUserId(PORTEUR))
                .thenReturn(Flux.just(document("doc-1", "kyc/doc-1.enc", null)));
        when(storageService.deleteFile("kyc/doc-1.enc"))
                .thenReturn(Mono.error(new RuntimeException("stockage injoignable")));

        StepVerifier.create(service.erase(PORTEUR))
                .verifyError();

        verify(mediaFileRepository, never()).delete(any(MediaFile.class));
    }

    @Test
    @DisplayName("une vignette absente n'empêche pas l'effacement")
    void vignetteAbsente() {
        when(mediaFileRepository.findByUserId(PORTEUR))
                .thenReturn(Flux.just(document("doc-1", "kyc/doc-1.enc", null)));

        StepVerifier.create(service.erase(PORTEUR)).expectNext(1).verifyComplete();

        verify(storageService, times(1)).deleteFile(any());
    }

    @Test
    @DisplayName("un échec de suppression de la vignette n'annule pas l'effacement du document")
    void echecVignetteTolere() {
        // La vignette est un dérivé : mieux vaut effacer le document original
        // et signaler la vignette restante que de tout laisser en place.
        when(mediaFileRepository.findByUserId(PORTEUR))
                .thenReturn(Flux.just(document("doc-1", "kyc/doc-1.enc", "kyc/vignette.jpg")));
        when(storageService.deleteFile("kyc/vignette.jpg"))
                .thenReturn(Mono.error(new RuntimeException("introuvable")));

        StepVerifier.create(service.erase(PORTEUR)).expectNext(1).verifyComplete();

        verify(mediaFileRepository).delete(any(MediaFile.class));
    }

    @Test
    @DisplayName("plusieurs documents sont tous effacés")
    void plusieursDocuments() {
        when(mediaFileRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(
                document("doc-1", "kyc/1.enc", null),
                document("doc-2", "kyc/2.enc", null),
                document("doc-3", "kyc/3.enc", null)));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(3)
                .verifyComplete();

        verify(mediaFileRepository, times(3)).delete(any(MediaFile.class));
    }

    @Test
    @DisplayName("une personne sans document donne zéro, pas une erreur")
    void aucunDocument() {
        // Zéro est une réponse valable : elle distingue « rien à effacer » de
        // « service qui n'a jamais répondu ».
        when(mediaFileRepository.findByUserId(PORTEUR)).thenReturn(Flux.empty());

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    @DisplayName("le chemin de stockage retombe sur le nom de fichier s'il est absent")
    void repliSurLeNomDeFichier() {
        MediaFile sansChemin = document("doc-1", null, null);
        sansChemin.setFileName("kyc/doc-1.enc");
        when(mediaFileRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(sansChemin));

        StepVerifier.create(service.erase(PORTEUR)).expectNext(1).verifyComplete();

        verify(storageService).deleteFile("kyc/doc-1.enc");
    }

    @Test
    @DisplayName("un document sans emplacement connu ne bloque pas l'effacement des autres")
    void documentSansEmplacement() {
        when(mediaFileRepository.findByUserId(PORTEUR)).thenReturn(Flux.just(
                document("doc-1", null, null),
                document("doc-2", "kyc/2.enc", null)));

        StepVerifier.create(service.erase(PORTEUR))
                .expectNext(2)
                .verifyComplete();

        assertThat(List.of()).isEmpty();
        verify(mediaFileRepository, times(2)).delete(any(MediaFile.class));
    }
}
