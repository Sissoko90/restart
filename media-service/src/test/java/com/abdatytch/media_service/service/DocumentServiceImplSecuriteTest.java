package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.dto.response.DocumentDetailResponse;
import com.abdatytch.media_service.entity.MediaAccessLog;
import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.entity.UserDocumentRequirement;
import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.exception.InvalidFileTypeException;
import com.abdatytch.media_service.repository.MediaAccessLogRepository;
import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.repository.UserDocumentRequirementRepository;
import com.abdatytch.media_service.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Les contrôles de sécurité du service de documents, chacun garde d'une
 * correction.
 *
 * <h2>Pourquoi ce test existe</h2>
 *
 * Cinq protections ont été ajoutées ou réparées. Sans test, chacune reviendrait
 * à ce qu'elle était : une protection écrite, présente, jamais atteinte — le
 * défaut dominant de ce dépôt, trouvé plus de soixante-dix fois. Chaque classe
 * imbriquée ci-dessous garde exactement une correction, et son message d'échec
 * dit ce qui se casse si elle disparaît.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentServiceImpl - les controles de securite")
class DocumentServiceImplSecuriteTest {

    private static final String UTILISATEUR = "user-1";
    private static final String CODE = "NATIONAL_ID";
    private static final String CHEMIN = "users/user-1/documents/NATIONAL_ID/empreinte";
    private static final String CHEMIN_VIGNETTE = CHEMIN + "_thumb";

    @Mock private MediaFileRepository mediaFileRepository;
    @Mock private MediaAccessLogRepository accessLogRepository;
    @Mock private UserDocumentRequirementRepository requirementRepository;
    @Mock private StorageService storageService;
    @Mock private DocumentValidationService validationService;
    @Mock private MalwareDetectionService malwareDetectionService;
    @Mock private DocumentQualityService qualityService;
    @Mock private UnifiedEncryptionService unifiedEncryptionService;
    @Mock private WatermarkService watermarkService;
    @Mock private ThumbnailService thumbnailService;
    @Mock private ImageCompressionService imageCompressionService;
    @Mock private DocumentMetricsService documentMetricsService;
    @Mock private CacheService cacheService;

    @InjectMocks
    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        when(validationService.canUserUploadDocument(anyString(), any(), anyString()))
                .thenReturn(Mono.just(new DocumentValidationService.ValidationResult(
                        true, "ok", 0, 10)));
        when(requirementRepository.findByUserIdAndDocumentTypeCode(anyString(), anyString()))
                .thenReturn(Mono.just(UserDocumentRequirement.builder()
                        .userId(UTILISATEUR).documentTypeCode(CODE).uploadAttempts(0).build()));
        when(requirementRepository.save(any(UserDocumentRequirement.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(accessLogRepository.save(any(MediaAccessLog.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(storageService.deleteFile(anyString())).thenReturn(Mono.empty());
        when(cacheService.delete(anyString())).thenReturn(Mono.just(true));
    }

    /** Un {@code FilePart} de la taille demandée. */
    private FilePart fichierDe(int octets, MediaType typeMime) {
        FilePart part = org.mockito.Mockito.mock(FilePart.class);
        when(part.filename()).thenReturn("piece.bin");
        HttpHeaders entetes = new HttpHeaders();
        entetes.setContentType(typeMime);
        when(part.headers()).thenReturn(entetes);
        DataBuffer tampon = new DefaultDataBufferFactory().wrap(new byte[octets]);
        when(part.content()).thenReturn(Flux.just(tampon));
        return part;
    }

    private Mono<?> envoyer(TypeDeDocumentResolu type, FilePart fichier) {
        return service.uploadDocument(UTILISATEUR, UserRole.DRIVER, type, fichier,
                DocumentUploadRequest.builder().documentTypeCode(CODE).build(),
                "41.223.10.5", "Mozilla/5.0");
    }

    private MediaFile document(boolean avecVignette) {
        MediaFile doc = MediaFile.builder()
                .userId(UTILISATEUR)
                .documentTypeCode(CODE)
                .storagePath(CHEMIN)
                .status(DocumentStatus.APPROVED)
                .build();
        doc.setId("doc-1");
        if (avecVignette) {
            doc.setThumbnailPath(CHEMIN_VIGNETTE);
        }
        return doc;
    }

    // ==================================================================
    @Nested
    @DisplayName("La taille est bornee PENDANT le transfert")
    class LimiteDeTaille {

        /**
         * Il n'existait <b>aucune</b> borne de taille.
         *
         * <p>{@code DataBufferUtils.join} était appelé sans {@code maxByteCount},
         * et {@code StorageConstants.MAX_FILE_SIZE} (100 Mo) n'était lu nulle
         * part. Le fichier était lu entier en mémoire puis recopié à chaque
         * étape — environ cinq copies par envoi simultané, sur le service qui
         * détient les pièces d'identité.
         */
        @Test
        @DisplayName("un fichier au-dela de la limite du type est refuse")
        void auDelaDeLaLimiteRefuse() {
            var type = new TypeDeDocumentResolu(CODE, "Pièce", true, List.of(), 1);

            StepVerifier.create(envoyer(type, fichierDe(2 * 1024 * 1024, MediaType.IMAGE_JPEG)))
                    .expectError(InvalidFileTypeException.class)
                    .verify();

            verify(malwareDetectionService, never()).scanFile(any(), anyString(), anyString());
            verify(mediaFileRepository, never()).save(any(MediaFile.class));
        }

        /**
         * La borne est posée <b>pendant</b> le transfert, pas vérifiée après.
         *
         * <p>Se fier au {@code Content-Length} d'une partie multipart
         * laisserait passer un envoi annonçant 1 Ko et transmettant 1 Go. Ce
         * test le constate : aucun en-tête de longueur n'est posé, et le refus
         * a lieu quand même.
         */
        @Test
        @DisplayName("le refus n'attend pas un en-tete de longueur")
        void refusSansEnTeteDeLongueur() {
            var type = new TypeDeDocumentResolu(CODE, "Pièce", true, List.of(), 1);
            FilePart sansLongueur = fichierDe(2 * 1024 * 1024, MediaType.IMAGE_JPEG);

            assertThat(sansLongueur.headers().getContentLength()).isEqualTo(-1);

            StepVerifier.create(envoyer(type, sansLongueur))
                    .expectError(InvalidFileTypeException.class)
                    .verify();
        }

        /**
         * Sans limite propre au type, le plafond global s'applique.
         *
         * <p>Une valeur nulle ne doit pas rendre la limite <b>infinie</b> : ce
         * serait transformer une absence de saisie en absence de protection.
         */
        @Test
        @DisplayName("sans limite de type, le fichier atteint le scan antimalware")
        void sansLimiteDeTypeLeGlobalSApplique() {
            var type = new TypeDeDocumentResolu(CODE, "Pièce", true, List.of(), null);

            assertThat(type.limiteEnOctets())
                    .withFailMessage("la limite effective est nulle ou negative : "
                            + "aucune borne ne s'appliquerait")
                    .isPositive();

            // Le scan refuse, pour arreter le pipeline ici : ce qui compte est
            // qu'il ait ete ATTEINT, donc que la taille n'ait pas refuse avant.
            when(malwareDetectionService.scanFile(any(), anyString(), anyString()))
                    .thenReturn(Mono.just(new MalwareDetectionService.SecurityScanResult(
                            false, List.of(), "arret du test ici", 0)));

            StepVerifier.create(envoyer(type, fichierDe(4096, MediaType.IMAGE_JPEG)))
                    .expectError()
                    .verify();

            verify(malwareDetectionService).scanFile(any(), anyString(), anyString());
        }

        @Test
        @DisplayName("une limite nulle ou negative retombe sur le plafond global")
        void limiteAberranteRetombeSurLeGlobal() {
            long global = new TypeDeDocumentResolu(CODE, "x", true, List.of(), null)
                    .limiteEnOctets();

            assertThat(new TypeDeDocumentResolu(CODE, "x", true, List.of(), 0).limiteEnOctets())
                    .isEqualTo(global);
            assertThat(new TypeDeDocumentResolu(CODE, "x", true, List.of(), -5).limiteEnOctets())
                    .isEqualTo(global);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les types MIME du catalogue sont appliques")
    class TypesMimeDuCatalogue {

        /**
         * {@code acceptedMimeTypes} n'était lu par personne.
         *
         * <p>Un administrateur restreignait un type de document aux PDF, l'API
         * l'acceptait et le persistait, et une image passait quand même.
         */
        @Test
        @DisplayName("un type MIME hors de la liste du type est refuse")
        void horsDeLaListeDuTypeRefuse() {
            var type = new TypeDeDocumentResolu(CODE, "Pièce", true,
                    List.of("application/pdf"), null);

            StepVerifier.create(envoyer(type, fichierDe(1024, MediaType.IMAGE_JPEG)))
                    .expectError(InvalidFileTypeException.class)
                    .verify();

            verify(malwareDetectionService, never()).scanFile(any(), anyString(), anyString());
        }

        /**
         * Une liste vide signifie « pas de restriction propre ».
         *
         * <p><b>Jamais</b> « rien n'est autorisé » : tous les types posés par le
         * seeder ont une liste vide, et l'interprétation inverse rendrait le
         * dépôt impossible pour l'ensemble du catalogue.
         */
        @Test
        @DisplayName("une liste vide, ou nulle, n'interdit rien")
        void listeVideNInterditRien() {
            var type = new TypeDeDocumentResolu(CODE, "Pièce", true, List.of(), null);

            assertThat(type.accepteLeTypeMime("image/jpeg")).isTrue();
            assertThat(type.accepteLeTypeMime("application/pdf")).isTrue();

            var sansListe = new TypeDeDocumentResolu(CODE, "Pièce", true, null, null);
            assertThat(sansListe.accepteLeTypeMime("image/jpeg"))
                    .withFailMessage("une liste NULLE interdit tout : le depot devient "
                            + "impossible pour les types que le seeder n'a pas configures")
                    .isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La vignette est supprimee avec le document")
    class SuppressionDeLaVignette {

        /**
         * Elle survivait à la suppression.
         *
         * <p>La vignette est <b>filigranée mais NON chiffrée</b> : une
         * réduction lisible d'une pièce d'identité, conservée après son
         * effacement. Un manquement RGPD que personne n'allait constater.
         */
        @Test
        @DisplayName("la suppression par la personne retire aussi la vignette")
        void suppressionUtilisateur() {
            when(mediaFileRepository.findById("doc-1")).thenReturn(Mono.just(document(true)));
            when(mediaFileRepository.deleteById("doc-1")).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteDocument("doc-1", UTILISATEUR)).verifyComplete();

            ArgumentCaptor<String> chemins = ArgumentCaptor.forClass(String.class);
            verify(storageService, org.mockito.Mockito.atLeastOnce())
                    .deleteFile(chemins.capture());
            assertThat(chemins.getAllValues())
                    .withFailMessage("la vignette %s n'est pas supprimee : une reduction "
                            + "LISIBLE de la piece d'identite survit a son effacement",
                            CHEMIN_VIGNETTE)
                    .contains(CHEMIN, CHEMIN_VIGNETTE);
        }

        @Test
        @DisplayName("la suppression par un administrateur retire aussi la vignette")
        void suppressionAdministrateur() {
            when(mediaFileRepository.findById("doc-1")).thenReturn(Mono.just(document(true)));
            when(mediaFileRepository.deleteById("doc-1")).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteDocumentAdmin("doc-1", "admin-1")).verifyComplete();

            ArgumentCaptor<String> chemins = ArgumentCaptor.forClass(String.class);
            verify(storageService, org.mockito.Mockito.atLeastOnce())
                    .deleteFile(chemins.capture());
            assertThat(chemins.getAllValues()).contains(CHEMIN, CHEMIN_VIGNETTE);
        }

        /** Un document sans vignette ne doit pas provoquer d'appel parasite. */
        @Test
        @DisplayName("sans vignette, un seul effacement de stockage")
        void sansVignette() {
            when(mediaFileRepository.findById("doc-1")).thenReturn(Mono.just(document(false)));
            when(mediaFileRepository.deleteById("doc-1")).thenReturn(Mono.empty());

            StepVerifier.create(service.deleteDocument("doc-1", UTILISATEUR)).verifyComplete();

            verify(storageService).deleteFile(CHEMIN);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le telechargement dechiffre")
    class Dechiffrement {

        /**
         * Il servait du chiffré.
         *
         * <p>{@code decryptDocument} n'était appelé <b>nulle part</b> dans
         * {@code src/main} : seules ses déclarations d'interface existaient.
         * L'envoi chiffre en AES-256-GCM, et la lecture rendait les octets
         * MinIO tels quels — un 200 porteur d'un flux illisible.
         */
        @Test
        @DisplayName("la personne proprietaire recoit du clair")
        void telechargementUtilisateur() {
            when(mediaFileRepository.findById("doc-1")).thenReturn(Mono.just(document(false)));
            when(storageService.downloadFile(CHEMIN)).thenReturn(Mono.just(new byte[]{9, 9}));
            when(unifiedEncryptionService.decryptDocument(any(), anyString(), anyString()))
                    .thenReturn(Mono.just(new byte[]{1, 2, 3}));

            StepVerifier.create(service.downloadDocument("doc-1", UTILISATEUR))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(unifiedEncryptionService)
                    .decryptDocument(any(), eq(UTILISATEUR), eq(CODE));
        }

        /**
         * L'administrateur déchiffre avec l'identité du <b>propriétaire</b>.
         *
         * <p>La clé est dérivée de {@code (userId du document, code du type)} à
         * l'envoi. Passer l'identifiant de l'administrateur dériverait une
         * autre clé : GCM authentifie, donc l'échec serait franc — mais
         * l'administrateur ne pourrait plus lire aucun document.
         */
        @Test
        @DisplayName("l'administrateur dechiffre avec l'identite du proprietaire")
        void telechargementAdministrateur() {
            when(mediaFileRepository.findById("doc-1")).thenReturn(Mono.just(document(false)));
            when(storageService.downloadFile(CHEMIN)).thenReturn(Mono.just(new byte[]{9, 9}));
            when(unifiedEncryptionService.decryptDocument(any(), anyString(), anyString()))
                    .thenReturn(Mono.just(new byte[]{1, 2, 3}));

            StepVerifier.create(service.downloadDocumentAdmin("doc-1"))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(unifiedEncryptionService)
                    .decryptDocument(any(), eq(UTILISATEUR), eq(CODE));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le lien de telechargement passe par la passerelle")
    class LienDeTelechargement {

        /**
         * C'était une URL MinIO présignée de <b>60 heures</b>.
         *
         * <p>L'appel passait {@code 3600} à un paramètre nommé
         * {@code expirationMinutes}. Mais la durée n'était pas le vrai
         * problème : l'URL pointait directement sur MinIO, donc contournait la
         * passerelle, le contrôle de propriété, le contrôle de statut
         * {@code APPROVED} et le déchiffrement. Elle rendait des octets
         * illisibles, à qui détenait le lien.
         */
        @Test
        @DisplayName("le detail rend un chemin de passerelle, pas une URL de stockage")
        void cheminDePasserelle() {
            when(mediaFileRepository.findById("doc-1")).thenReturn(Mono.just(document(false)));
            when(cacheService.getOrCompute(anyString(), any(), any(), any()))
                    .thenAnswer(i -> i.getArgument(2));

            DocumentDetailResponse detail =
                    service.getDocumentDetails("doc-1", UTILISATEUR).block();

            assertThat(detail).isNotNull();
            assertThat(detail.getDownloadUrl())
                    .withFailMessage("le lien ne passe pas par la passerelle : s'il pointe "
                            + "sur le stockage, il contourne la propriete, le statut et "
                            + "le dechiffrement")
                    .isEqualTo("/api/v1/media/download/doc-1");
        }
    }
}
