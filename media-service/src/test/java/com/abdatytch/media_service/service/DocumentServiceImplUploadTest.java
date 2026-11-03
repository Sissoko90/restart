package com.abdatytch.media_service.service;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.dto.response.DocumentUploadResponse;
import com.abdatytch.media_service.entity.MediaAccessLog;
import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.entity.UserDocumentRequirement;
import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.SecurityThreatType;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.exception.InvalidFileTypeException;
import com.abdatytch.media_service.exception.StorageException;
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
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le pipeline d'envoi d'un document, fixé avant d'être restructuré.
 *
 * <h2>Pourquoi ce test précède le découpage</h2>
 *
 * {@code processDocumentUpload} enchaîne douze étapes en une douzaine de
 * {@code flatMap} imbriqués, jusqu'à <b>84 espaces d'indentation</b>. C'est la
 * méthode à découper — et <b>aucun test ne l'exerçait</b>.
 *
 * <p>Restructurer du réactif imbriqué sans filet, sur le chemin d'envoi d'un
 * service qui stocke des pièces d'identité, c'est la configuration exacte des
 * deux incidents de ce projet. Ce test est donc écrit <b>d'abord</b>, et il
 * décrit le comportement <b>actuel</b> — pas celui qu'on souhaiterait. Après le
 * découpage, il doit passer sans être modifié ; toute retouche qu'il faudrait
 * lui faire est le signe d'un changement de comportement à justifier.
 *
 * <h2>Ce qu'il fixe, et pourquoi chaque point compte</h2>
 *
 * L'ordre des étapes, les branches conditionnelles réservées aux images, et
 * surtout <b>quels refus incrémentent le compteur de tentatives et lesquels
 * non</b> — c'est la règle la plus facile à déplacer par accident, et celle qui
 * décide si une personne se retrouve bloquée au bout de dix essais.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DocumentServiceImpl — le pipeline d'envoi")
class DocumentServiceImplUploadTest {

    private static final String UTILISATEUR = "user-1";
    private static final String CODE = "NATIONAL_ID";
    private static final byte[] CONTENU = {1, 2, 3, 4};

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
    @Mock private TransactionalOperator transactionalOperator;

    @InjectMocks
    private DocumentServiceImpl service;

    private TypeDeDocumentResolu type;

    @BeforeEach
    void setUp() {
        // Les deux derniers composants sont les limites du catalogue, qui
        // n'etaient appliquees par personne : `null` et liste vide valent ici
        // « aucune restriction propre au type », donc le comportement fixe par
        // ce test est inchange — seule la signature a bouge.
        type = new TypeDeDocumentResolu(CODE, "Pièce d'identité nationale", true,
                List.of(), null);

        when(validationService.canUserUploadDocument(anyString(), any(), anyString()))
                .thenReturn(Mono.just(new DocumentValidationService.ValidationResult(
                        true, "ok", 0, 10)));

        when(malwareDetectionService.scanFile(any(), anyString(), anyString()))
                .thenReturn(Mono.just(new MalwareDetectionService.SecurityScanResult(
                        true, List.of(), "propre", 0)));
        when(malwareDetectionService.calculateFileHash(any()))
                .thenReturn(Mono.just("empreinte-abc"));
        when(malwareDetectionService.scanMetadata(any()))
                .thenReturn(Mono.just(new MalwareDetectionService.MetadataScanResult(
                        Map.of(), List.of(), false)));

        when(imageCompressionService.compressImage(any())).thenReturn(Mono.just(CONTENU));
        when(qualityService.analyzeImageQuality(any(), anyString()))
                .thenReturn(Mono.just(new DocumentQualityService.QualityAnalysisResult(
                        92.0, true, null, 800, 600, 1)));
        when(watermarkService.addWatermark(any(), anyString(), anyString()))
                .thenReturn(Mono.just(CONTENU));
        when(unifiedEncryptionService.encryptDocument(any(), anyString(), anyString()))
                .thenReturn(Mono.just(CONTENU));
        when(thumbnailService.generateThumbnail(any())).thenReturn(Mono.just(CONTENU));
        when(storageService.uploadFileFromBytes(anyString(), any(), anyString()))
                .thenReturn(Mono.just("users/user-1/documents/NATIONAL_ID/empreinte-abc"));

        when(mediaFileRepository.save(any(MediaFile.class)))
                .thenAnswer(i -> {
                    MediaFile enregistre = i.getArgument(0);
                    enregistre.setId("doc-1");
                    return Mono.just(enregistre);
                });
        when(requirementRepository.findByUserIdAndDocumentTypeCode(anyString(), anyString()))
                .thenReturn(Mono.empty());
        when(requirementRepository.save(any(UserDocumentRequirement.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(accessLogRepository.save(any(MediaAccessLog.class)))
                .thenAnswer(i -> Mono.just(i.getArgument(0)));
    }

    /** Un {@code FilePart} qui rend le contenu depuis la mémoire. */
    private FilePart fichier(String nom, MediaType typeMime) {
        FilePart part = org.mockito.Mockito.mock(FilePart.class);
        when(part.filename()).thenReturn(nom);
        HttpHeaders entetes = new HttpHeaders();
        entetes.setContentType(typeMime);
        when(part.headers()).thenReturn(entetes);
        DataBuffer tampon = new DefaultDataBufferFactory().wrap(CONTENU);
        when(part.content()).thenReturn(Flux.just(tampon));
        return part;
    }

    private Mono<DocumentUploadResponse> envoyer(MediaType typeMime) {
        return service.uploadDocument(
                UTILISATEUR, UserRole.DRIVER, type,
                fichier("piece.jpg", typeMime),
                DocumentUploadRequest.builder().documentTypeCode(CODE).build(),
                "41.223.10.5", "Mozilla/5.0");
    }

    // ==================================================================
    @Nested
    @DisplayName("Le chemin nominal")
    class CheminNominal {

        @Test
        @DisplayName("une image valide est enregistree et la reponse porte le CODE du type")
        void imageValideEnregistree() {
            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG))
                    .assertNext(reponse -> {
                        assertThat(reponse.getDocumentId()).isEqualTo("doc-1");
                        assertThat(reponse.getDocumentTypeCode()).isEqualTo(CODE);
                        assertThat(reponse.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
                    })
                    .verifyComplete();
        }

        /**
         * Le caractère obligatoire vient du type <b>résolu</b>.
         *
         * <p>Il venait de l'énumération compilée. S'il cessait d'être repris,
         * aucun document ne serait plus marqué obligatoire et le suivi de
         * complétion deviendrait faux sans erreur visible.
         */
        @Test
        @DisplayName("isMandatory est repris du type resolu, pas devine")
        void caractereObligatoireRepris() {
            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG)).expectNextCount(1).verifyComplete();

            ArgumentCaptor<MediaFile> enregistre = ArgumentCaptor.forClass(MediaFile.class);
            verify(mediaFileRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getIsMandatory()).isTrue();
            assertThat(enregistre.getValue().getDocumentTypeCode()).isEqualTo(CODE);
        }

        /**
         * Le chemin de stockage est bâti sur le code du catalogue.
         *
         * <p>Il employait le nom de la constante d'énumération. Le changer
         * rendrait introuvables les documents déjà stockés.
         */
        @Test
        @DisplayName("le chemin de stockage contient le code du type")
        void cheminDeStockage() {
            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG)).expectNextCount(1).verifyComplete();

            ArgumentCaptor<String> chemin = ArgumentCaptor.forClass(String.class);
            verify(storageService, org.mockito.Mockito.atLeastOnce())
                    .uploadFileFromBytes(chemin.capture(), any(), anyString());
            assertThat(chemin.getAllValues())
                    .anyMatch(c -> c.equals("users/user-1/documents/NATIONAL_ID/empreinte-abc"));
        }

        /** Filigrane, vignette et compression ne concernent que les images. */
        @Test
        @DisplayName("un PDF saute compression, filigrane et vignette")
        void pdfSansTraitementImage() {
            when(qualityService.analyzePdfQuality(any()))
                    .thenReturn(Mono.just(new DocumentQualityService.QualityAnalysisResult(
                            90.0, true, null, 0, 0, 3)));

            StepVerifier.create(envoyer(MediaType.APPLICATION_PDF))
                    .expectNextCount(1)
                    .verifyComplete();

            verify(imageCompressionService, never()).compressImage(any());
            verify(watermarkService, never()).addWatermark(any(), anyString(), anyString());
            verify(thumbnailService, never()).generateThumbnail(any());
        }

        @Test
        @DisplayName("le compteur d'envois et la duree sont enregistres")
        void metriquesEnregistrees() {
            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG)).expectNextCount(1).verifyComplete();

            verify(documentMetricsService).incrementUploadCounter();
            verify(documentMetricsService).recordUploadDuration(anyLong());
        }

        /**
         * Le compteur de tentatives est <b>remis à zéro</b> au succès.
         *
         * <p>Sans cela, dix envois réussis finiraient par bloquer la personne.
         */
        @Test
        @DisplayName("l'exigence est creee et son compteur de tentatives remis a zero")
        void exigenceMiseAJour() {
            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG)).expectNextCount(1).verifyComplete();

            ArgumentCaptor<UserDocumentRequirement> exigence =
                    ArgumentCaptor.forClass(UserDocumentRequirement.class);
            verify(requirementRepository, org.mockito.Mockito.atLeastOnce())
                    .save(exigence.capture());
            assertThat(exigence.getAllValues())
                    .anyMatch(e -> e.getUploadAttempts() != null && e.getUploadAttempts() == 0);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les refus, et lesquels comptent une tentative")
    class LesRefus {

        /**
         * Un type de fichier non autorisé <b>compte</b> une tentative.
         *
         * <p>C'est la règle la plus facile à déplacer par accident lors d'un
         * découpage, et celle qui décide si quelqu'un se retrouve bloqué.
         */
        @Test
        @DisplayName("un type MIME interdit est refuse ET compte une tentative")
        void typeMimeInterditCompteUneTentative() {
            when(requirementRepository.findByUserIdAndDocumentTypeCode(anyString(), anyString()))
                    .thenReturn(Mono.just(UserDocumentRequirement.builder()
                            .userId(UTILISATEUR).documentTypeCode(CODE)
                            .uploadAttempts(2).build()));

            StepVerifier.create(envoyer(MediaType.TEXT_HTML))
                    .expectError(InvalidFileTypeException.class)
                    .verify();

            verify(requirementRepository).save(any(UserDocumentRequirement.class));
            verify(mediaFileRepository, never()).save(any(MediaFile.class));
        }

        /** Une qualité insuffisante compte aussi une tentative. */
        @Test
        @DisplayName("une qualite sous le seuil est refusee ET compte une tentative")
        void qualiteInsuffisanteCompteUneTentative() {
            when(qualityService.analyzeImageQuality(any(), anyString()))
                    .thenReturn(Mono.just(new DocumentQualityService.QualityAnalysisResult(
                            41.0, false, "image floue", 800, 600, 1)));
            when(requirementRepository.findByUserIdAndDocumentTypeCode(anyString(), anyString()))
                    .thenReturn(Mono.just(UserDocumentRequirement.builder()
                            .userId(UTILISATEUR).documentTypeCode(CODE)
                            .uploadAttempts(1).build()));

            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG))
                    .expectError(InvalidFileTypeException.class)
                    .verify();

            verify(requirementRepository).save(any(UserDocumentRequirement.class));
            verify(mediaFileRepository, never()).save(any(MediaFile.class));
        }

        /**
         * Une menace de sécurité ne compte <b>pas</b> de tentative.
         *
         * <p>Elle suit un autre chemin — {@code handleSecurityThreat} — et ne
         * consomme donc pas le quota d'essais. Le comportement actuel est fixé
         * ici tel qu'il est, sans jugement : s'il doit changer, ce sera une
         * décision, pas un effet de bord du découpage.
         */
        @Test
        @DisplayName("une menace est refusee SANS compter de tentative")
        void menaceNeCompteAucuneTentative() {
            when(malwareDetectionService.scanFile(any(), anyString(), anyString()))
                    .thenReturn(Mono.just(new MalwareDetectionService.SecurityScanResult(
                            false, List.of(SecurityThreatType.KNOWN_MALWARE_SIGNATURE),
                            "signature connue", 95)));

            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG))
                    .expectError(StorageException.class)
                    .verify();

            verify(requirementRepository, never()).save(any(UserDocumentRequirement.class));
            verify(mediaFileRepository, never()).save(any(MediaFile.class));
        }

        /** Chaque menace détectée est comptée, par son type. */
        @Test
        @DisplayName("chaque menace est comptee par son type")
        void menacesCompteesParType() {
            when(malwareDetectionService.scanFile(any(), anyString(), anyString()))
                    .thenReturn(Mono.just(new MalwareDetectionService.SecurityScanResult(
                            false, List.of(SecurityThreatType.XSS_ATTEMPT,
                                    SecurityThreatType.EXECUTABLE_CONTENT),
                            "deux menaces", 70)));

            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG))
                    .expectError(StorageException.class)
                    .verify();

            verify(documentMetricsService).incrementSecurityThreatCounter("XSS_ATTEMPT");
            verify(documentMetricsService).incrementSecurityThreatCounter("EXECUTABLE_CONTENT");
        }

        /** Le plafond de tentatives refuse avant tout traitement du fichier. */
        @Test
        @DisplayName("le plafond de tentatives refuse sans rien scanner")
        void plafondDeTentatives() {
            when(validationService.canUserUploadDocument(anyString(), any(), anyString()))
                    .thenReturn(Mono.just(new DocumentValidationService.ValidationResult(
                            false, "Nombre maximum de tentatives atteint (10).", 10, 10)));

            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG))
                    .expectError(InvalidFileTypeException.class)
                    .verify();

            verify(malwareDetectionService, never()).scanFile(any(), anyString(), anyString());
            verify(mediaFileRepository, never()).save(any(MediaFile.class));
        }

        /**
         * Un document illisible est <b>refusé</b>, et non enregistré signalé.
         *
         * <p>Le seuil de qualité et la lisibilité sont deux conditions
         * distinctes, et {@code isReadable == false} suffit au refus même avec
         * un score élevé. {@code determineDocumentStatus} sait produire
         * {@code FLAGGED_POOR_QUALITY}, mais le rejet intervient avant — ce
         * statut n'est donc pas atteignable par ce chemin. Fixé tel quel.
         */
        @Test
        @DisplayName("un document illisible est refuse meme avec un bon score")
        void documentIllisibleRefuse() {
            when(qualityService.analyzeImageQuality(any(), anyString()))
                    .thenReturn(Mono.just(new DocumentQualityService.QualityAnalysisResult(
                            95.0, false, null, 800, 600, 1)));
            when(requirementRepository.findByUserIdAndDocumentTypeCode(anyString(), anyString()))
                    .thenReturn(Mono.just(UserDocumentRequirement.builder()
                            .userId(UTILISATEUR).documentTypeCode(CODE)
                            .uploadAttempts(0).build()));

            StepVerifier.create(envoyer(MediaType.IMAGE_JPEG))
                    .expectError(InvalidFileTypeException.class)
                    .verify();
        }
    }
}
