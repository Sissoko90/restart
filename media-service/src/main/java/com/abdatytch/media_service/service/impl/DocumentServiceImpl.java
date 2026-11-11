package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.dto.request.DocumentUploadRequest;
import com.abdatytch.media_service.dto.response.*;
import com.abdatytch.media_service.entity.MediaAccessLog;
import com.abdatytch.media_service.entity.MediaFile;
import com.abdatytch.media_service.entity.UserDocumentRequirement;
import com.abdatytch.media_service.service.DocumentService;
import com.abdatytch.media_service.service.UnifiedEncryptionService;
import com.abdatytch.media_service.enums.DocumentStatus;
import com.abdatytch.media_service.enums.SecurityThreatType;
import com.abdatytch.media_service.enums.UserRole;
import com.abdatytch.media_service.exception.InvalidFileTypeException;
import com.abdatytch.media_service.exception.MediaNotFoundException;
import com.abdatytch.media_service.exception.StorageException;
import com.abdatytch.media_service.exception.UnauthorizedAccessException;
import com.abdatytch.media_service.repository.MediaAccessLogRepository;
import com.abdatytch.media_service.repository.MediaFileRepository;
import com.abdatytch.media_service.repository.UserDocumentRequirementRepository;
import com.abdatytch.media_service.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

/**

 * Implémentation complète du service de gestion des documents
 * Intègre validation, sécurité, qualité et audit
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final MediaFileRepository mediaFileRepository;
    private final MediaAccessLogRepository accessLogRepository;
    private final UserDocumentRequirementRepository requirementRepository;

    private final StorageService storageService;
    private final DocumentValidationService validationService;
    private final MalwareDetectionService malwareDetectionService;
    private final DocumentQualityService qualityService;
    private final UnifiedEncryptionService unifiedEncryptionService;
    private final WatermarkService watermarkService;
    private final ThumbnailService thumbnailService;
    private final ImageCompressionService imageCompressionService;
    private final DocumentMetricsService documentMetricsService;
    private final CacheService cacheService;
    private final TransactionalOperator transactionalOperator;

    /**
     * L'envoi d'un document.
     *
     * <h3>{@code @Transactional} a ete RETIRE — trois raisons, toutes mesurees</h3>
     *
     * <ol>
     *   <li><b>elle englobait du travail non transactionnel</b> : compression,
     *       filigrane, chiffrement et depot MinIO. Elle promettait donc une
     *       atomicite que MinIO ne peut pas tenir — un objet depose n'est pas
     *       annule par un rollback Mongo — et depassait les 60 s de duree de vie
     *       par defaut d'une transaction Mongo sur une grosse image ;</li>
     *   <li><b>elle annulait les ecritures des REFUS</b>. Le compteur de
     *       tentatives et l'incident de securite sont ecrits, puis
     *       {@code Mono.error} traverse la frontiere transactionnelle : les deux
     *       ecritures dont tout l'objet est de survivre au refus etaient
     *       defaites. Plafond de tentatives jamais atteint, aucune trace d'un
     *       envoi malveillant ;</li>
     *   <li><b>aucun replica set n'existe</b> dans les deux fichiers de
     *       composition de ce depot, et MongoDB exige un replica set pour
     *       ouvrir une transaction. Elle ne pouvait donc meme pas demarrer.</li>
     * </ol>
     *
     * <p>Aucune transaction plus etroite n'a ete remise a la place, pour la
     * troisieme raison : l'infrastructure ne la permet pas. Les ecritures
     * secondaires de {@code enregistrerEtRepondre} sont rendues non fatales et
     * <b>comptees</b> — voir la methode. Le jour ou un replica set existera,
     * {@code transactionalOperator} reste disponible dans
     * {@code MongoTransactionConfig} pour ces seules ecritures finales.
     */
    @Override
    public Mono<DocumentUploadResponse> uploadDocument(
            String userId,
            UserRole userRole,
            TypeDeDocumentResolu type,
            FilePart filePart,
            DocumentUploadRequest request,
            String ipAddress,
            String userAgent) {

        long startTime = System.currentTimeMillis();
        log.info("Upload request - User: {}, Role: {}, DocType: {}", userId, userRole, type.code());

        return validationService.canUserUploadDocument(userId, userRole, type.code())
            .flatMap(validation -> {
                if (!validation.canUpload()) {
                    log.warn("Upload validation failed: {}", validation.reason());
                    return Mono.error(new InvalidFileTypeException(validation.reason()));
                }

                // Lecture BORNEE du fichier.
                //
                // LE DEFAUT CORRIGE : `join` etait appele sans `maxByteCount`,
                // donc sans AUCUNE borne de taille. Le fichier etait lu entier
                // en memoire, puis recopie a chaque etape — lecture,
                // compression, filigrane, chiffrement, depot — soit environ
                // cinq copies par envoi simultane. Un seul appelant pouvait
                // epuiser la memoire du service qui detient les pieces
                // d'identite.
                //
                // Et deux limites etaient declarees sans etre appliquees :
                // `StorageConstants.MAX_FILE_SIZE` (100 Mo) n'etait lu nulle
                // part, et le `maxFileSizeMB` du catalogue, saisi par un
                // administrateur et persiste, etait ignore du pipeline.
                //
                // La borne est posee PENDANT le transfert, et non verifiee
                // apres : se fier au `Content-Length` d'une partie multipart
                // laisserait passer un envoi annoncant 1 Ko et transmettant
                // 1 Go. Meme correction que celle deja faite sur la photo de
                // profil de user-service.
                long limite = type.limiteEnOctets();
                return DataBufferUtils.join(filePart.content(), (int) limite)
                    .onErrorMap(org.springframework.core.io.buffer.DataBufferLimitException.class,
                        depassement -> {
                            // Le LIBELLE et non le code : `code` figure dans la
                            // liste surveillee par
                            // AucunJustificatifDansLesJournauxTest, parce que la
                            // variable d'un code de verification s'appelle
                            // justement `code`. Le libelle est aussi plus lisible
                            // dans un journal.
                            log.warn("Envoi refuse : au-dela de {} octets pour le type {}",
                                limite, type.libelle());
                            return new InvalidFileTypeException(
                                "Fichier trop volumineux. Limite pour '" + type.libelle()
                                    + "' : " + (limite / (1024 * 1024)) + " Mo");
                        })
                    .flatMap(dataBuffer -> {
                        byte[] fileData = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(fileData);
                        DataBufferUtils.release(dataBuffer);

                        String fileName = filePart.filename();
                        String mimeType = filePart.headers().getContentType() != null ?
                            filePart.headers().getContentType().toString() : "application/octet-stream";

                        log.debug("File details - Name: {}, Size: {} bytes, MIME: {}",
                            fileName, fileData.length, mimeType);

                        // Pipeline de validation et sécurité
                        return processDocumentUpload(
                            userId, userRole, type,
                            fileData, fileName, mimeType,
                            request, ipAddress, userAgent,
                            validation.uploadAttempts()
                        );
                    });
            })
            .doOnSuccess(response -> {
                log.info("Document uploaded successfully - ID: {}, Status: {}",
                    response.getDocumentId(), response.getStatus());
                long duration = System.currentTimeMillis() - startTime;
                documentMetricsService.incrementUploadCounter();
                documentMetricsService.recordUploadDuration(duration);
            })
            .doOnError(error -> log.error("Document upload failed", error));
    }

    /**
     * Le pipeline d'envoi, en etapes nommees.
     *
     * <p>Cette methode enchainait douze etapes en autant de {@code flatMap}
     * imbriques, jusqu'a 84 espaces d'indentation. Chaque etape est desormais
     * une methode qui prend le contexte et le rend enrichi. La chaine se lit de
     * haut en bas, et chaque etape devient instrumentable separement.
     *
     * <p><b>L'ordre est celui d'avant, au pas pres</b>, parce que le changer
     * modifierait ce qui est stocke. Deux points en particulier, ou une
     * permutation compilerait sans broncher en changeant silencieusement le
     * contenu des fichiers deposes :
     * <ul>
     *   <li>l'empreinte est calculee sur les donnees <b>compressees</b>, et
     *       <b>avant</b> le filigrane — c'est elle qui nomme le chemin de
     *       stockage, donc la deplacer rendrait introuvables les documents
     *       deja stockes ;</li>
     *   <li>la vignette est produite depuis les donnees <b>filigranees</b>,
     *       pas depuis l'original.</li>
     * </ul>
     *
     * <p>{@code DocumentServiceImplUploadTest} fixe ce comportement et doit
     * passer sans retouche.
     */
    private Mono<DocumentUploadResponse> processDocumentUpload(
            String userId, UserRole userRole, TypeDeDocumentResolu type,
            byte[] fileData, String fileName, String mimeType,
            DocumentUploadRequest request, String ipAddress, String userAgent,
            int currentAttempts) {

        ContexteEnvoi contexte = new ContexteEnvoi(
            userId, userRole, type, fileData, fileName, mimeType,
            request, ipAddress, userAgent, currentAttempts
        );

        return verifierLeTypeMime(contexte)
            .flatMap(this::scannerLeFichier)
            .flatMap(this::compresserSiImage)
            .flatMap(this::calculerLEmpreinte)
            .flatMap(this::analyserLaQualite)
            .flatMap(this::apposerLeFiligrane)
            .flatMap(this::chiffrer)
            .flatMap(this::genererLaVignette)
            .flatMap(this::scannerLesMetadonnees)
            .flatMap(this::deposerDansLeStockage)
            .flatMap(this::enregistrerEtRepondre);
    }

    // ========================================================================
    // Les etapes du pipeline, dans l'ordre d'execution
    // ========================================================================

    /**
     * 1. Type MIME autorise.
     *
     * <p>Ce refus <b>compte une tentative</b>, contrairement au refus pour
     * menace de securite. L'ecart est delibere et fixe par le test.
     */
    private Mono<ContexteEnvoi> verifierLeTypeMime(ContexteEnvoi ctx) {
        if (!isAllowedMimeType(ctx.mimeType)) {
            log.warn("Unsupported file type - MIME: {}, File: {}", ctx.mimeType, ctx.fileName);
            return incrementUploadAttempt(ctx.userId, ctx.type.code(), ctx.currentAttempts)
                .then(Mono.error(new InvalidFileTypeException(
                    "Type de fichier non autorisé: " + ctx.mimeType + ". Types acceptés: image/*, application/pdf, application/msword, application/vnd.openxmlformats-officedocument.wordprocessingml.document")));
        }

        // La restriction PROPRE AU TYPE DE DOCUMENT, enfin appliquee.
        //
        // `acceptedMimeTypes` est saisi par un administrateur dans le
        // catalogue, persiste, et renvoye par l'API — et aucun code de l'envoi
        // ne le lisait. Un administrateur restreignait un type de document aux
        // PDF, l'API l'acceptait, et une image passait quand meme.
        //
        // Elle n'AJOUTE qu'une restriction : la liste globale ci-dessus
        // s'applique de toute facon, et une liste de type vide signifie « pas
        // de restriction propre » et non « rien n'est autorise » — tous les
        // types du seeder sont dans ce cas.
        if (!ctx.type.accepteLeTypeMime(ctx.mimeType)) {
            log.warn("Type MIME {} refuse par la configuration du type {}",
                ctx.mimeType, ctx.type.libelle());
            return incrementUploadAttempt(ctx.userId, ctx.type.code(), ctx.currentAttempts)
                .then(Mono.error(new InvalidFileTypeException(
                    "Le type de document '" + ctx.type.libelle() + "' n'accepte pas "
                        + ctx.mimeType + ". Types acceptés : "
                        + String.join(", ", ctx.type.typesMimeAcceptes()))));
        }

        return Mono.just(ctx);
    }

    /**
     * 2. Scan antimalware.
     *
     * <p>Chaque menace est comptee par son type, puis le document est rejete.
     * Ce refus ne consomme <b>pas</b> de tentative : il suit
     * {@code handleSecurityThreat}, qui enregistre un incident de securite.
     */
    private Mono<ContexteEnvoi> scannerLeFichier(ContexteEnvoi ctx) {
        return malwareDetectionService.scanFile(ctx.donnees, ctx.mimeType, ctx.fileName)
            .flatMap(scan -> {
                if (!scan.passed()) {
                    log.warn("Security scan failed - Threats: {}", scan.threats());
                    scan.threats().forEach(menace ->
                        documentMetricsService.incrementSecurityThreatCounter(menace.name())
                    );
                    return handleSecurityThreat(ctx.userId, scan, ctx.ipAddress, ctx.userAgent)
                        .then(Mono.error(new StorageException(
                            "Document rejeté pour raisons de sécurité: " + scan.description()
                        )));
                }
                ctx.scanSecurite = scan;
                return Mono.just(ctx);
            });
    }

    /** 3. Compression, pour les images seulement. */
    private Mono<ContexteEnvoi> compresserSiImage(ContexteEnvoi ctx) {
        if (!ctx.estUneImage()) {
            return Mono.just(ctx);
        }

        final int tailleAvant = ctx.donnees.length;
        return imageCompressionService.compressImage(ctx.donnees)
            .doOnSuccess(compressees -> log.info("Image compressed: {} -> {} bytes",
                tailleAvant, compressees.length))
            .onErrorResume(e -> {
                log.error("Image compression failed: {}", e.getMessage());
                return Mono.error(new StorageException("Échec de la compression d'image: " + e.getMessage()));
            })
            .map(compressees -> {
                ctx.donnees = compressees;
                return ctx;
            });
    }

    /**
     * 4. Empreinte du fichier, pour la detection de duplicatas.
     *
     * <p>Calculee sur les donnees compressees et <b>avant</b> le filigrane.
     * C'est elle qui nomme le chemin de stockage.
     */
    private Mono<ContexteEnvoi> calculerLEmpreinte(ContexteEnvoi ctx) {
        return malwareDetectionService.calculateFileHash(ctx.donnees)
            .onErrorResume(e -> {
                log.error("Hash calculation failed: {}", e.getMessage());
                return Mono.error(new StorageException("Échec du calcul du hash: " + e.getMessage()));
            })
            .map(empreinte -> {
                ctx.empreinte = empreinte;
                return ctx;
            });
    }

    /**
     * 5. Analyse de qualite, stricte.
     *
     * <p>Le seuil et la lisibilite sont deux conditions distinctes :
     * {@code isReadable == false} suffit au refus, meme avec un score eleve.
     * Ce refus <b>compte une tentative</b>.
     */
    private Mono<ContexteEnvoi> analyserLaQualite(ContexteEnvoi ctx) {
        return analyzeDocumentQuality(ctx.donnees, ctx.mimeType)
            .onErrorResume(e -> {
                log.error("Quality analysis failed: {}", e.getMessage());
                return Mono.error(new StorageException("Échec de l'analyse de qualité: " + e.getMessage()));
            })
            .flatMap(qualite -> {
                if (!qualite.isReadable() || qualite.qualityScore() < 70.0) {
                    log.warn("Quality check FAILED - Score: {}, Readable: {}, Reason: {}",
                        qualite.qualityScore(), qualite.isReadable(), qualite.rejectionReason());

                    // L'illisibilite prime sur le score : c'est le signal le plus
                    // fort, et un document illisible est refuse meme bien note.
                    documentMetricsService.incrementQualityRejectedCounter(
                        !qualite.isReadable() ? "ILLISIBLE" : "SCORE_INSUFFISANT");

                    return incrementUploadAttempt(ctx.userId, ctx.type.code(), ctx.currentAttempts)
                        .then(Mono.error(new InvalidFileTypeException(
                            "DOCUMENT REJETÉ - Qualité insuffisante (score: " +
                            String.format("%.1f", qualite.qualityScore()) + "/100): " +
                            qualite.rejectionReason()
                        )));
                }

                log.info("Quality check PASSED - Score: {}/100", qualite.qualityScore());
                ctx.qualite = qualite;
                return Mono.just(ctx);
            });
    }

    /** 6. Filigrane, pour les images seulement. */
    private Mono<ContexteEnvoi> apposerLeFiligrane(ContexteEnvoi ctx) {
        if (!ctx.estUneImage()) {
            return Mono.just(ctx);
        }

        return watermarkService.addWatermark(ctx.donnees, ctx.userId, ctx.type.code())
            .doOnSuccess(filigranees -> log.info("Watermark added"))
            .onErrorResume(e -> {
                log.error("Watermark failed: {}", e.getMessage());
                return Mono.error(new StorageException("Échec du watermark: " + e.getMessage()));
            })
            .map(filigranees -> {
                ctx.donnees = filigranees;
                return ctx;
            });
    }

    /** 7. Chiffrement AES-256-GCM, a cle derivee par document. */
    private Mono<ContexteEnvoi> chiffrer(ContexteEnvoi ctx) {
        return unifiedEncryptionService.encryptDocument(ctx.donnees, ctx.userId, ctx.type.code())
            .doOnSuccess(chiffrees -> log.info("Document encrypted"))
            .onErrorResume(e -> {
                log.error("Encryption failed: {}", e.getMessage());
                return Mono.error(new StorageException("Échec du chiffrement: " + e.getMessage()));
            })
            .map(chiffrees -> {
                ctx.donneesChiffrees = chiffrees;
                return ctx;
            });
    }

    /**
     * 8. Vignette, pour les images seulement — et <b>non critique</b>.
     *
     * <p>Produite depuis les donnees <b>filigranees</b>, et deposee en clair :
     * c'est une reduction destinee a l'affichage, pas une copie du document.
     *
     * <p>{@code Mono.just((String) null)} figurait ici DEUX fois, et levait une
     * {@code NullPointerException} a l'<b>assemblage</b> : {@code Mono.just}
     * refuse null. Consequences, mesurees par le test de caracterisation :
     * <ul>
     *   <li><b>aucun PDF ne pouvait etre televerse</b>, la branche « pas une
     *       image » passant par la ;</li>
     *   <li>une image dont la vignette echouait faisait echouer <b>tout</b>
     *       l'envoi, alors que le code annonce ce cas « non-critical ».</li>
     * </ul>
     *
     * <p>Et <b>non</b> {@code Mono.empty()} : un Mono vide traverserait le
     * {@code flatMap} suivant sans le declencher, donc la requete ne serait ni
     * transmise ni repondue. L'enveloppe {@code Optional} est l'idiome deja
     * retenu dans ce depot, pour cette raison.
     */
    private Mono<ContexteEnvoi> genererLaVignette(ContexteEnvoi ctx) {
        if (!ctx.estUneImage()) {
            return Mono.just(ctx);
        }

        return thumbnailService.generateThumbnail(ctx.donnees)
            .flatMap(donneesVignette -> {
                String cheminVignette = buildStoragePath(ctx.userId, ctx.type.code(), ctx.empreinte) + "_thumb";
                return storageService.uploadFileFromBytes(cheminVignette, donneesVignette, "image/jpeg")
                    .doOnSuccess(t -> log.info("Thumbnail generated"));
            })
            .map(java.util.Optional::of)
            .defaultIfEmpty(java.util.Optional.empty())
            .onErrorResume(e -> {
                log.warn("Thumbnail generation/upload failed (non-critical): {}", e.getMessage());
                return Mono.just(java.util.Optional.empty());
            })
            .map(chemin -> {
                ctx.cheminVignette = chemin;
                return ctx;
            });
    }

    /** 9. Scan des metadonnees fournies par l'appelant. */
    private Mono<ContexteEnvoi> scannerLesMetadonnees(ContexteEnvoi ctx) {
        java.util.Map<String, Object> metadonnees = ctx.request.getDescription() != null
            ? java.util.Map.of("description", ctx.request.getDescription())
            : java.util.Map.of();

        return malwareDetectionService.scanMetadata(metadonnees)
            .map(scan -> {
                ctx.scanMetadonnees = scan;
                return ctx;
            });
    }

    /** 10. Depot des donnees chiffrees dans MinIO. */
    private Mono<ContexteEnvoi> deposerDansLeStockage(ContexteEnvoi ctx) {
        ctx.cheminStockage = buildStoragePath(ctx.userId, ctx.type.code(), ctx.empreinte);

        return storageService.uploadFileFromBytes(ctx.cheminStockage, ctx.donneesChiffrees, ctx.mimeType)
            .map(resultat -> {
                ctx.resultatDepot = resultat;
                return ctx;
            });
    }

    /**
     * 11 a 13. Entite, enregistrement, exigence et journal d'acces.
     *
     * <p>{@code updateDocumentRequirement} <b>remet le compteur de tentatives a
     * zero</b>. Sans cela, dix envois reussis finiraient par bloquer la
     * personne.
     */
    private Mono<DocumentUploadResponse> enregistrerEtRepondre(ContexteEnvoi ctx) {
        MediaFile document = buildMediaFile(
            ctx.userId, ctx.userRole, ctx.type,
            ctx.fileName, ctx.mimeType, ctx.donneesChiffrees.length, ctx.empreinte,
            ctx.resultatDepot, ctx.request, ctx.scanSecurite, ctx.qualite,
            ctx.scanMetadonnees, ctx.ipAddress, ctx.userAgent, ctx.currentAttempts
        );
        document.setThumbnailPath(ctx.cheminVignette.orElse(null));

        return mediaFileRepository.save(document)
            .flatMap(enregistre ->
                // L'enregistrement du document est la SEULE ecriture
                // autoritaire. Les deux suivantes sont secondaires, et
                // explicitement non fatales.
                //
                // Elles etaient dans la portee d'un `@Transactional` qui ne
                // pouvait pas demarrer. Les laisser faire echouer l'envoi
                // serait pire : le fichier est deja dans MinIO et la fiche en
                // base, donc l'appelant recevrait une erreur pour un envoi
                // reussi, et reessaierait — produisant un second document.
                //
                // Mais les avaler sans trace troquerait une annulation
                // silencieuse contre une PERTE silencieuse. D'ou le compteur :
                // un suivi de completion faux ou un journal d'acces manquant se
                // voient dans les metriques, par etape.
                ecritureSecondaire(
                        updateDocumentRequirement(ctx.userId, ctx.type.code(), enregistre.getId()),
                        "EXIGENCE")
                    .then(ecritureSecondaire(
                        logAccess(enregistre.getId(), ctx.userId, "UPLOAD",
                                ctx.ipAddress, ctx.userAgent, null),
                        "JOURNAL_ACCES"))
                    .thenReturn(buildUploadResponse(enregistre))
            );
    }

    /**
     * Rend une ecriture secondaire non fatale, et son echec visible.
     *
     * <p>{@code onErrorResume} vers {@code Mono.empty()} et non vers une
     * valeur : ces ecritures rendent {@code Mono<Void>}, et le `then` suivant
     * s'enchaine sur la completion, qu'elle vienne du succes ou du repli.
     */
    private Mono<Void> ecritureSecondaire(Mono<Void> ecriture, String etape) {
        return ecriture.onErrorResume(echec -> {
            log.warn("Ecriture secondaire {} echouee apres un envoi reussi : {}",
                    etape, echec.getMessage());
            documentMetricsService.incrementEcritureSecondaireEchouee(etape);
            return Mono.empty();
        });
    }

    /**
     * L'etat qui traverse le pipeline d'envoi.
     *
     * <p>Mutable, et deliberement : l'alternative etait un {@code record} a
     * dix-sept composants recree par une methode {@code withX} a chaque etape,
     * ce qui aurait ajoute une centaine de lignes sans rien rendre plus sur.
     * Cette classe ne quitte jamais {@code DocumentServiceImpl}, et un envoi
     * est traite par une seule chaine reactive, donc un seul abonnement : il
     * n'y a pas de partage entre requetes.
     *
     * <p>{@code donnees} <b>evolue</b> au fil des etapes — brut, puis
     * compresse, puis filigrane. C'est voulu : chaque etape consomme la sortie
     * de la precedente. Les donnees chiffrees sont en revanche gardees a part,
     * parce que la vignette est produite depuis les donnees <b>en clair</b>.
     */
    private static final class ContexteEnvoi {

        // Entrees, fixees a la construction
        final String userId;
        final UserRole userRole;
        final TypeDeDocumentResolu type;
        final String fileName;
        final String mimeType;
        final DocumentUploadRequest request;
        final String ipAddress;
        final String userAgent;
        final int currentAttempts;

        // Accumule par les etapes, dans l'ordre du pipeline
        byte[] donnees;
        MalwareDetectionService.SecurityScanResult scanSecurite;
        String empreinte;
        DocumentQualityService.QualityAnalysisResult qualite;
        byte[] donneesChiffrees;
        java.util.Optional<String> cheminVignette = java.util.Optional.empty();
        MalwareDetectionService.MetadataScanResult scanMetadonnees;
        String cheminStockage;
        String resultatDepot;

        ContexteEnvoi(String userId, UserRole userRole, TypeDeDocumentResolu type,
                      byte[] donnees, String fileName, String mimeType,
                      DocumentUploadRequest request, String ipAddress, String userAgent,
                      int currentAttempts) {
            this.userId = userId;
            this.userRole = userRole;
            this.type = type;
            this.donnees = donnees;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.request = request;
            this.ipAddress = ipAddress;
            this.userAgent = userAgent;
            this.currentAttempts = currentAttempts;
        }

        /** Compression, filigrane et vignette ne concernent que les images. */
        boolean estUneImage() {
            return mimeType != null && mimeType.startsWith("image/");
        }
    }

    private Mono<DocumentQualityService.QualityAnalysisResult> analyzeDocumentQuality(byte[] fileData, String mimeType) {
        if (mimeType.startsWith("image/")) {
            return qualityService.analyzeImageQuality(fileData, mimeType);
        } else if (mimeType.equals("application/pdf")) {
            return qualityService.analyzePdfQuality(fileData);
        } else {
            // Pour les autres types, retourner un résultat par défaut
            return Mono.just(new DocumentQualityService.QualityAnalysisResult(
                100.0, true, null, 0, 0, 0
            ));
        }
    }

    private String buildStoragePath(String userId, String documentTypeCode, String fileHash) {
        return String.format("users/%s/documents/%s/%s", userId, documentTypeCode, fileHash);
    }

    private MediaFile buildMediaFile(
            String userId, UserRole userRole, TypeDeDocumentResolu type,
            String fileName, String mimeType, long fileSize, String fileHash,
            String storagePath, DocumentUploadRequest request,
            MalwareDetectionService.SecurityScanResult securityScan,
            DocumentQualityService.QualityAnalysisResult qualityResult,
            MalwareDetectionService.MetadataScanResult metadataScan,
            String ipAddress, String userAgent, int uploadAttempts) {

        DocumentStatus status = determineDocumentStatus(securityScan, qualityResult);

        return MediaFile.builder()
            .userId(userId)
            .userRole(userRole)
            .documentTypeCode(type.code())
            .fileName(UUID.randomUUID().toString() + "_" + fileName)
            .originalFileName(fileName)
            .mimeType(mimeType)
            .fileSize(fileSize)
            .fileHash(fileHash)
            .storageProvider("MINIO")
            .storagePath(storagePath)
            .bucketName("user-documents")
            .status(status)
            .metadata(java.util.Map.of(
                "width", qualityResult.width(),
                "height", qualityResult.height(),
                "pageCount", qualityResult.pageCount()
            ))
            .sanitizedMetadata(metadataScan.sanitizedMetadata())
            // Resolu en amont depuis DocumentRoleRequirement : cette valeur
            // venait de l'enumeration, qui repondait sans entree-sortie. La
            // demander ici obligerait a casser ce constructeur en deux.
            .isMandatory(type.obligatoire())
            .issueDate(request.getIssueDate())
            .expiryDate(request.getExpiryDate())
            .securityCheckPassed(securityScan.passed())
            .securityFlags(securityScan.threats().stream()
                .map(SecurityThreatType::name)
                .collect(Collectors.toList()))
            .qualityScore(qualityResult.qualityScore())
            .isReadable(qualityResult.isReadable())
            .uploadedFromIp(ipAddress)
            .userAgent(userAgent)
            .uploadAttempts(uploadAttempts + 1)
            .build();
    }

    private DocumentStatus determineDocumentStatus(
            MalwareDetectionService.SecurityScanResult securityScan,
            DocumentQualityService.QualityAnalysisResult qualityResult) {

        if (!securityScan.passed()) {
            if (securityScan.severityScore() >= 80) {
                return DocumentStatus.FLAGGED_MALWARE;
            }
            return DocumentStatus.FLAGGED_SUSPICIOUS;
        }

        if (!qualityResult.isReadable()) {
            return DocumentStatus.FLAGGED_POOR_QUALITY;
        }

        return DocumentStatus.UPLOADED;
    }

    private DocumentUploadResponse buildUploadResponse(MediaFile document) {
        return DocumentUploadResponse.builder()
            .documentId(document.getId())
            .documentTypeCode(document.getDocumentTypeCode())
            .fileName(document.getOriginalFileName())
            .fileSize(document.getFileSize())
            .mimeType(document.getMimeType())
            .status(document.getStatus())
            .securityCheckPassed(document.getSecurityCheckPassed())
            .securityFlags(document.getSecurityFlags())
            .qualityScore(document.getQualityScore())
            .isReadable(document.getIsReadable())
            .message(buildStatusMessage(document.getStatus()))
            .uploadedAt(document.getCreatedAt())
            .build();
    }

    private String buildStatusMessage(DocumentStatus status) {
        return switch (status) {
            case UPLOADED -> "Document uploadé avec succès. En attente de validation.";
            case FLAGGED_MALWARE -> "Document bloqué : menace de sécurité détectée.";
            case FLAGGED_SUSPICIOUS -> "Document signalé : contenu suspect détecté.";
            case FLAGGED_POOR_QUALITY -> "Document rejeté : qualité insuffisante.";
            default -> "Document traité.";
        };
    }

    private Mono<Void> handleSecurityThreat(
            String userId,
            MalwareDetectionService.SecurityScanResult securityScan,
            String ipAddress,
            String userAgent) {

        // Log security threat to application logs
        log.warn("Security threat detected - User: {}, Severity: {}, Description: {}",
            userId, securityScan.severityScore(), securityScan.description());

        // Store in access log for audit trail
        MediaAccessLog accessLog = MediaAccessLog.builder()
            .userId(userId)
            .mediaId(null)
            .action("SECURITY_THREAT")
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .requestId(UUID.randomUUID().toString())
            .timestamp(LocalDateTime.now())
            .geolocation(null)
            .suspicious(true)
            .build();

        return accessLogRepository.save(accessLog).then();
    }

    /**

     * Vérifie si le type MIME est autorisé
     */
    private boolean isAllowedMimeType(String mimeType) {
        if (mimeType == null) return false;

        return com.abdatytch.media_service.constants.StorageConstants.ALLOWED_IMAGE_TYPES.contains(mimeType) ||
               com.abdatytch.media_service.constants.StorageConstants.ALLOWED_DOCUMENT_TYPES.contains(mimeType) ||
               com.abdatytch.media_service.constants.StorageConstants.ALLOWED_VIDEO_TYPES.contains(mimeType);
    }

    private Mono<Void> incrementUploadAttempt(String userId, String documentTypeCode, int currentAttempts) {
        return requirementRepository.findByUserIdAndDocumentTypeCode(userId, documentTypeCode)
            .flatMap(requirement -> {
                requirement.setUploadAttempts(currentAttempts + 1);
                return requirementRepository.save(requirement);
            })
            .then();
    }

    private Mono<Void> updateDocumentRequirement(String userId, String documentTypeCode, String documentId) {
        return requirementRepository.findByUserIdAndDocumentTypeCode(userId, documentTypeCode)
            .switchIfEmpty(Mono.defer(() -> {
                // Créer si n'existe pas
                UserDocumentRequirement newReq = UserDocumentRequirement.builder()
                    .userId(userId)
                    .documentTypeCode(documentTypeCode)
                    .mandatory(false)
                    .completed(false)
                    .uploadAttempts(0)
                    .rejectionCount(0)
                    .build();
                return requirementRepository.save(newReq);
            }))
            .flatMap(requirement -> {
                requirement.setCurrentDocumentId(documentId);
                // RÉINITIALISER le compteur à 0 en cas de succès
                requirement.setUploadAttempts(0);
                log.info("Upload successful - Reset attempt counter to 0 for user: {}, docType: {}",
                    userId, documentTypeCode);
                return requirementRepository.save(requirement);
            })
            .then();
    }

    /**
     * Telechargement par la personne proprietaire.
     *
     * <p>La duree est enregistree ici. {@code recordDownloadDuration} etait
     * <b>declare et jamais appele</b> : le chronometre
     * {@code documents_download_duration_seconds} etait expose, scrape, et vide
     * depuis toujours. Le temps est mesure sur le {@code Mono} externe, qui
     * couvre la lecture MinIO complete — {@code downloadFile} rend le tableau
     * d'octets entier avant l'enveloppe en {@code Flux}.
     */
    @Override
    public Mono<Flux<DataBuffer>> downloadDocument(String documentId, String userId) {
        log.info("Download request - Document: {}, User: {}", documentId, userId);
        long debut = System.currentTimeMillis();

        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .flatMap(document -> {
                // Vérifier ownership
                if (!document.getUserId().equals(userId)) {
                    return Mono.error(new UnauthorizedAccessException("Accès non autorisé à ce document"));
                }

                // Vérifier statut
                if (document.getStatus() != DocumentStatus.APPROVED) {
                    return Mono.error(new UnauthorizedAccessException(
                        "Document non approuvé. Statut: " + document.getStatus()
                    ));
                }

                return logAccess(documentId, userId, "DOWNLOAD", null, null, null)
                    .then(storageService.downloadFile(document.getStoragePath())
                        // LE DEFAUT CORRIGE : le dechiffrement manquait.
                        //
                        // L'envoi chiffre en AES-256-GCM a l'etape 7, et ce
                        // chemin rendait les octets MinIO tels quels :
                        // `decryptDocument` n'etait appele NULLE PART dans
                        // src/main, seules ses deux declarations d'interface
                        // existaient. Une personne telechargeait sa piece
                        // d'identite et recevait un 200 porteur d'un flux GCM
                        // illisible.
                        //
                        // Les arguments doivent etre EXACTEMENT ceux de
                        // l'envoi, qui derive la cle de (userId, code du type)
                        // — le second parametre s'appelle `documentId` dans
                        // l'interface mais recoit le CODE DU TYPE. Les
                        // intervertir rendrait un dechiffrement en echec, non
                        // un contenu faux : GCM authentifie.
                        //
                        // Chiffrement desactive : `decryptDocument` rend le
                        // contenu tel quel, exactement comme `encryptDocument`.
                        // Les documents deja stockes en clair restent lisibles.
                        .flatMap(octets -> unifiedEncryptionService.decryptDocument(
                                octets, document.getUserId(), document.getDocumentTypeCode()))
                        // Le parametre de type est explicite : sans lui, le
                        // `doOnSuccess` ajoute en fin de chaine prive le
                        // compilateur du type de retour de la methode comme
                        // cible d'inference, et DefaultDataBuffer remonte.
                        .map(bytes -> Flux.<DataBuffer>just(
                            org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance.wrap(bytes)
                        ))
                    );
            })
            .doOnSuccess(flux -> documentMetricsService.recordDownloadDuration(
                System.currentTimeMillis() - debut));
    }

    @Override
    public Mono<DocumentDetailResponse> getDocumentDetails(String documentId, String userId) {
        String cacheKey = "document:details:" + documentId + ":" + userId;

        return cacheService.getOrCompute(
            cacheKey,
            DocumentDetailResponse.class,
            mediaFileRepository.findById(documentId)
                .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
                .flatMap(document -> {
                    if (!document.getUserId().equals(userId)) {
                        return Mono.error(new UnauthorizedAccessException("Accès non autorisé"));
                    }

                    // LE DEFAUT CORRIGE : une URL MinIO presignee de 60 HEURES
                    // qui contournait tout, et ne servait a rien.
                    //
                    // L'appel etait `generatePresignedUrl(chemin, 3600)` avec un
                    // parametre nomme `expirationMinutes` : 3600 minutes, soit
                    // 60 heures, la ou l'appelant visait une heure. Mais la duree
                    // n'etait pas le vrai probleme.
                    //
                    // Cette URL pointait DIRECTEMENT sur MinIO. Elle contournait
                    // donc :
                    //   - la passerelle, seule porte de cette plateforme ;
                    //   - le controle de propriete ;
                    //   - le controle de statut, que `downloadDocument` applique
                    //     (un document non APPROVED n'est pas telechargeable) ;
                    //   - et le dechiffrement, puisque le blob est chiffre en
                    //     AES-256-GCM a l'envoi.
                    //
                    // Elle rendait donc des octets illisibles, pendant 60 heures,
                    // a qui detenait le lien. Un risque sans aucun benefice.
                    //
                    // Le champ rend desormais le chemin de telechargement PUBLIC,
                    // celui qui passe par la passerelle et par `downloadDocument`
                    // — lequel verifie la propriete, le statut, journalise
                    // l'acces et dechiffre. Le DTO est inchange.
                    return Mono.just(buildDocumentDetailResponse(
                            document, cheminDeTelechargement(document.getId())));
                }),
            java.time.Duration.ofSeconds(600) // Cache 10 minutes
        );
    }


    /**
     * Supprime la vignette, s'il y en a une.
     *
     * <h3>Le défaut corrigé : elle survivait à la suppression</h3>
     *
     * Ni {@code deleteDocument} ni {@code deleteDocumentAdmin} ne retiraient
     * {@code thumbnailPath}. Une personne supprimait sa pièce d'identité : la
     * ligne disparaissait, l'objet chiffré disparaissait, la réponse était 200 —
     * et la vignette restait dans MinIO.
     *
     * <p>C'est la pire des trois traces possibles, parce qu'elle est
     * <b>filigranée mais NON chiffrée</b> : une réduction lisible d'une pièce
     * d'identité, conservée après son effacement. Un manquement RGPD
     * invisible, et le mot « invisible » est le problème — personne n'allait le
     * constater.
     *
     * <p>{@code DocumentErasureService.supprimerVignette} le faisait déjà
     * correctement, avec un commentaire reconnaissant que c'est « une version
     * dégradée de la pièce d'identité ». Deux chemins de suppression sur trois
     * l'ignoraient : le défaut classique de ce dépôt, une règle appliquée à un
     * seul endroit.
     *
     * <p>L'échec est non fatal et journalisé : la suppression du document
     * principal ne doit pas être annulée parce que la vignette résiste. Mais il
     * est compté, pour qu'une vignette qui survit se voie.
     */
    private Mono<Void> supprimerVignette(MediaFile document) {
        String vignette = document.getThumbnailPath();
        if (vignette == null || vignette.isBlank()) {
            return Mono.empty();
        }
        return storageService.deleteFile(vignette)
                .onErrorResume(echec -> {
                    log.warn("Vignette non supprimee ({}) : {}", vignette, echec.getMessage());
                    documentMetricsService.incrementStorageFailureCounter("SUPPRESSION_VIGNETTE");
                    return Mono.empty();
                });
    }

    /**
     * Le chemin public de téléchargement, par la passerelle.
     *
     * <p>Préfixe {@code /api/v1} compris : c'est l'adresse que le client
     * appelle, et la passerelle le retire avant de transmettre. Donner le
     * chemin interne ({@code /media/download/...}) rendrait une URL qui ne
     * fonctionne qu'en attaquant le port du service — ce que le filtre d'accès
     * refuse.
     *
     * <p>Chemin relatif, et non absolu : construire un hôte ici demanderait de
     * connaître l'adresse publique de la passerelle, qui dépend du
     * déploiement. Un chemin relatif est juste dans tous les environnements.
     */
    private String cheminDeTelechargement(String documentId) {
        return "/api/v1/media/download/" + documentId;
    }

    private DocumentDetailResponse buildDocumentDetailResponse(MediaFile document, String downloadUrl) {
        return DocumentDetailResponse.builder()
            .id(document.getId())
            .userId(document.getUserId())
            .userRole(document.getUserRole())
            .documentTypeCode(document.getDocumentTypeCode())
            .fileName(document.getFileName())
            .originalFileName(document.getOriginalFileName())
            .mimeType(document.getMimeType())
            .fileSize(document.getFileSize())
            .status(document.getStatus())
            .isMandatory(document.getIsMandatory())
            .issueDate(document.getIssueDate())
            .expiryDate(document.getExpiryDate())
            .metadata(document.getMetadata())
            .securityCheckPassed(document.getSecurityCheckPassed())
            .securityFlags(document.getSecurityFlags())
            .qualityScore(document.getQualityScore())
            .isReadable(document.getIsReadable())
            .rejectionReason(document.getRejectionReason())
            .reviewedBy(document.getReviewedBy())
            .reviewedAt(document.getReviewedAt())
            .createdAt(document.getCreatedAt())
            .updatedAt(document.getUpdatedAt())
            .downloadUrl(downloadUrl)
            .build();
    }

    @Override
    public Flux<DocumentListResponse> getUserDocuments(String userId) {
        return mediaFileRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .map(this::buildDocumentListResponse);
    }

    @Override
    public Flux<DocumentListResponse> getUserDocumentsByStatus(String userId, DocumentStatus status) {
        return mediaFileRepository.findByUserIdAndStatus(userId, status)
            .map(this::buildDocumentListResponse);
    }

    private DocumentListResponse buildDocumentListResponse(MediaFile document) {
        boolean isExpired = document.getExpiryDate() != null &&
            document.getExpiryDate().isBefore(LocalDate.now());

        return DocumentListResponse.builder()
            .id(document.getId())
            .documentTypeCode(document.getDocumentTypeCode())
            .fileName(document.getOriginalFileName())
            .fileSize(document.getFileSize())
            .status(document.getStatus())
            .isMandatory(document.getIsMandatory())
            .expiryDate(document.getExpiryDate())
            .isExpired(isExpired)
            .uploadedAt(document.getCreatedAt())
            .build();
    }

    @Override
    public Mono<Void> deleteDocument(String documentId, String userId) {
        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .flatMap(document -> {
                if (!document.getUserId().equals(userId)) {
                    return Mono.error(new UnauthorizedAccessException("Accès non autorisé"));
                }

                // Record delete metric
                documentMetricsService.incrementDeletedCounter();

                // Invalidate cache
                String cacheKey = "document:details:" + documentId + ":" + userId;

                // La transaction a ete RETIREE ici aussi.
                //
                // Elle enfermait un effacement MinIO, qu'aucun rollback Mongo ne
                // defait : l'atomicite etait annoncee et non tenue. Et elle ne
                // pouvait pas demarrer, faute de replica set.
                //
                // L'ORDRE, lui, est la vraie garantie, et il est conserve :
                // MinIO d'abord, la ligne Mongo ensuite. Si la seconde echoue,
                // la fiche pointe vers un objet disparu — visible, et le
                // telechargement echoue franchement. L'ordre inverse laisserait
                // la piece d'identite dans le stockage apres que la personne
                // l'a supprimee, soit un manquement RGPD invisible. Entre une
                // incoherence qui se voit et une donnee qui survit a son
                // effacement, on choisit la premiere.
                return cacheService.delete(cacheKey)
                        .then(storageService.deleteFile(document.getStoragePath()))
                        // La vignette aussi : filigranee mais NON chiffree, donc
                        // la trace la plus lisible de la piece d'identite.
                        .then(Mono.defer(() -> supprimerVignette(document)))
                        .then(logAccess(documentId, userId, "DELETE", null, null, null))
                        .then(mediaFileRepository.deleteById(documentId))
                .onErrorResume(e -> {
                    log.error("Transaction failed for document deletion: {}", documentId, e);
                    return Mono.error(new StorageException("Échec de la suppression du document", e));
                });
            });
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public Mono<Void> approveDocument(String documentId, String adminId) {
        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .flatMap(document -> {
                document.setStatus(DocumentStatus.APPROVED);
                document.setReviewedBy(adminId);
                document.setReviewedAt(LocalDateTime.now());

                // Record approve metric
                documentMetricsService.incrementApprovedCounter();

                // Invalidate cache
                return mediaFileRepository.save(document)
                    .flatMap(saved -> {
                        // Invalidate cache
                        String cacheKey = "document:details:" + documentId + ":" + saved.getUserId();

                        return cacheService.delete(cacheKey)
                            .then(requirementRepository.findByUserIdAndDocumentTypeCode(
                                saved.getUserId(), saved.getDocumentTypeCode())
                                .flatMap(req -> {
                                    req.setCompleted(true);
                                    return requirementRepository.save(req);
                                })
                                .then(logAccess(documentId, adminId, "APPROVE", null, null, null))
                            );
                    })
                    .onErrorResume(e -> {
                        log.error("Transaction failed for document approval: {}", documentId, e);
                        return Mono.error(new StorageException("Échec de l'approbation du document", e));
                    });
            });
    }

    @Override
    public Mono<Void> rejectDocument(String documentId, String adminId, String reason) {
        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .flatMap(document -> {
                document.setStatus(DocumentStatus.REJECTED);
                document.setReviewedBy(adminId);
                document.setReviewedAt(LocalDateTime.now());
                document.setRejectionReason(reason);

                // Record reject metric
                documentMetricsService.incrementRejectedCounter();

                return mediaFileRepository.save(document)
                    .flatMap(saved ->
                        requirementRepository.findByUserIdAndDocumentTypeCode(
                            saved.getUserId(), saved.getDocumentTypeCode())
                            .flatMap(req -> {
                                req.setRejectionCount((req.getRejectionCount() != null ?
                                    req.getRejectionCount() : 0) + 1);
                                return requirementRepository.save(req);
                            })
                            .then(logAccess(documentId, adminId, "REJECT", null, null, null))
                    );
            });
    }

    @Override
    @Deprecated
    public Mono<UserDocumentStatusResponse> getUserDocumentStatus(String userId, UserRole userRole) {
        log.warn("Deprecated method getUserDocumentStatus called - use DocumentRequirementService instead");
        return Mono.empty();
    }

    @Override
    @Deprecated
    public Mono<UserDocumentStatusResponse> getMissingDocuments(String userId, UserRole userRole) {
        log.warn("Deprecated method getMissingDocuments called - use DocumentRequirementService instead");
        return Mono.empty();
    }

    @Override
    public Mono<Long> checkAndUpdateExpiredDocuments() {
        LocalDate today = LocalDate.now();

        return mediaFileRepository.findByExpiryDateBeforeAndStatus(today, DocumentStatus.APPROVED)
            .flatMap(document -> {
                document.setStatus(DocumentStatus.EXPIRED);
                return mediaFileRepository.save(document);
            })
            .count()
            .doOnSuccess(count -> log.info("Updated {} expired documents", count));
    }

    private Mono<Void> logAccess(String mediaId, String userId, String action,
                                 String ipAddress, String userAgent, String requestId) {
        MediaAccessLog accessLog = MediaAccessLog.builder()
            .mediaId(mediaId)
            .userId(userId)
            .action(action)
            .ipAddress(ipAddress)
            .userAgent(userAgent)
            .requestId(requestId)
            .timestamp(LocalDateTime.now())
            .suspicious(false)
            .build();

        return accessLogRepository.save(accessLog)
            .doOnSuccess(savedLog -> log.debug("Access logged - Action: {}, User: {}", action, userId))
            .then();
    }

    @Override
    public Flux<DocumentListResponse> getPendingDocuments() {
        log.info("Fetching all pending documents for admin review");

        return mediaFileRepository.findByStatusOrderByCreatedAtAsc(DocumentStatus.UPLOADED)
            .map(this::mapToDocumentListResponse)
            .doOnComplete(() -> log.info("Pending documents fetched successfully"))
            .doOnError(error -> log.error("Error fetching pending documents: {}", error.getMessage()));
    }

    @Override
    public Flux<DocumentListResponse> getAllDocuments(String status, String userId) {
        log.info("Fetching all documents - status: {}, userId: {}", status, userId);

        Flux<MediaFile> documentsFlux;

        if (status != null && userId != null) {
            // Filtrer par statut ET userId
            DocumentStatus docStatus = DocumentStatus.valueOf(status.toUpperCase());
            documentsFlux = mediaFileRepository.findByUserIdAndStatus(userId, docStatus);
        } else if (status != null) {
            // Filtrer par statut uniquement
            DocumentStatus docStatus = DocumentStatus.valueOf(status.toUpperCase());
            documentsFlux = mediaFileRepository.findByStatus(docStatus);
        } else if (userId != null) {
            // Filtrer par userId uniquement
            documentsFlux = mediaFileRepository.findByUserIdOrderByCreatedAtDesc(userId);
        } else {
            // Tous les documents
            documentsFlux = mediaFileRepository.findAll();
        }

        return documentsFlux
            .map(this::mapToDocumentListResponse)
            .doOnComplete(() -> log.info("All documents fetched successfully"))
            .doOnError(error -> log.error("Error fetching documents: {}", error.getMessage()));
    }

    @Override
    public Mono<java.util.Map<String, Object>> getDocumentStats() {
        log.info("Fetching document statistics");

        return Mono.zip(
            mediaFileRepository.count(),
            mediaFileRepository.findByStatus(DocumentStatus.UPLOADED).count(),
            mediaFileRepository.findByStatus(DocumentStatus.APPROVED).count(),
            mediaFileRepository.findByStatus(DocumentStatus.REJECTED).count(),
            mediaFileRepository.findBySecurityCheckPassedFalse().count()
        ).map(tuple -> {
            java.util.Map<String, Object> stats = new java.util.HashMap<>();
            stats.put("total", tuple.getT1());
            stats.put("pending", tuple.getT2());
            stats.put("approved", tuple.getT3());
            stats.put("rejected", tuple.getT4());
            stats.put("securityIssues", tuple.getT5());
            stats.put("timestamp", java.time.LocalDateTime.now());
            return stats;
        })
        .doOnSuccess(stats -> log.info("Document statistics fetched: {}", stats))
        .doOnError(error -> log.error("Error fetching statistics: {}", error.getMessage()));
    }

    private DocumentListResponse mapToDocumentListResponse(MediaFile mediaFile) {
        return DocumentListResponse.builder()
            .id(mediaFile.getId())
            .userId(mediaFile.getUserId())
            .documentTypeCode(mediaFile.getDocumentTypeCode())
            .fileName(mediaFile.getFileName())
            .fileSize(mediaFile.getFileSize())
            .status(mediaFile.getStatus())
            .isMandatory(mediaFile.getIsMandatory())
            .expiryDate(mediaFile.getExpiryDate())
            .isExpired(mediaFile.getExpiryDate() != null &&
                      mediaFile.getExpiryDate().isBefore(java.time.LocalDate.now()))
            .uploadedAt(mediaFile.getCreatedAt())
            .build();
    }

    @Override
    public Mono<DocumentDetailResponse> getDocumentDetailsAdmin(String documentId) {
        log.info("Admin fetching document details - ID: {}", documentId);

        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .map(document -> DocumentDetailResponse.builder()
                .id(document.getId())
                .userId(document.getUserId())
                .documentTypeCode(document.getDocumentTypeCode())
                .fileName(document.getFileName())
                .fileSize(document.getFileSize())
                .mimeType(document.getMimeType())
                .status(document.getStatus())
                .securityCheckPassed(document.getSecurityCheckPassed())
                .qualityScore(document.getQualityScore())
                .isMandatory(document.getIsMandatory())
                .expiryDate(document.getExpiryDate())
                .createdAt(document.getCreatedAt())
                .reviewedBy(document.getReviewedBy())
                .reviewedAt(document.getReviewedAt())
                .rejectionReason(document.getRejectionReason())
                .build())
            .doOnSuccess(details -> log.info("Document details fetched for admin"))
            .doOnError(error -> log.error("Error fetching document details: {}", error.getMessage()));
    }

    @Override
    public Mono<Flux<DataBuffer>> downloadDocumentAdmin(String documentId) {
        log.info("Admin downloading document - ID: {}", documentId);
        long debut = System.currentTimeMillis();

        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .flatMap(document -> {
                log.info("Document found - Status: {}, Path: {}", document.getStatus(), document.getStoragePath());

                return logAccess(documentId, "ADMIN", "DOWNLOAD", null, null, null)
                    .then(storageService.downloadFile(document.getStoragePath()))
                    // Dechiffrement avec l'identifiant du PROPRIETAIRE, jamais
                    // celui de l'administrateur : la cle est derivee de
                    // (userId du document, code du type) a l'envoi. Passer
                    // l'administrateur deriverait une autre cle et le
                    // dechiffrement echouerait — GCM authentifie, donc l'echec
                    // serait franc et non un contenu faux.
                    .flatMap(octets -> unifiedEncryptionService.decryptDocument(
                            octets, document.getUserId(), document.getDocumentTypeCode()))
                    .map(bytes -> Flux.<DataBuffer>just(
                            DefaultDataBufferFactory.sharedInstance.wrap(bytes)));
            })
            .doOnSuccess(data -> {
                log.info("Document downloaded by admin");
                documentMetricsService.recordDownloadDuration(System.currentTimeMillis() - debut);
            })
            .doOnError(error -> log.error("Error downloading document: {}", error.getMessage()));
    }

    /**
     * Suppression par un administrateur.
     *
     * <p>Elle <b>n'incrementait pas</b> {@code documents_deleted_total}, alors
     * que la suppression par la personne proprietaire le fait. Le compteur
     * sous-declarait donc precisement les suppressions qui meritent le plus
     * d'etre tracees : une piece d'identite effacee par un administrateur
     * n'apparaissait dans aucune metrique.
     */
    @Override
    public Mono<Void> deleteDocumentAdmin(String documentId, String adminId) {
        log.info("Admin {} deleting document {}", adminId, documentId);

        return mediaFileRepository.findById(documentId)
            .switchIfEmpty(Mono.error(new MediaNotFoundException("Document non trouvé")))
            .flatMap(document ->
                storageService.deleteFile(document.getStoragePath())
                    // La vignette aussi : voir supprimerVignette.
                    .then(Mono.defer(() -> supprimerVignette(document)))
                    .then(logAccess(documentId, adminId, "DELETE", null, null, null))
                    .then(mediaFileRepository.deleteById(documentId))
            )
            .doOnSuccess(v -> {
                log.info("Document {} deleted by admin {}", documentId, adminId);
                documentMetricsService.incrementDeletedCounter();
            })
            .doOnError(error -> log.error("Error deleting document: {}", error.getMessage()));
    }
}
