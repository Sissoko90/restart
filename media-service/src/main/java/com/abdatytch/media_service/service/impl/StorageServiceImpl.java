package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.exception.StorageException;
import com.abdatytch.media_service.service.DocumentMetricsService;
import com.abdatytch.media_service.service.UnifiedEncryptionService;
import com.abdatytch.media_service.service.StorageService;
import com.abdatytch.media_service.utils.FileNameSanitizer;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import io.minio.http.Method;
import io.minio.GetPresignedObjectUrlArgs;
import reactor.core.scheduler.Schedulers;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Implémentation du service de stockage avec MinIO
 * Opérations I/O bloquantes exécutées sur boundedElastic scheduler
 * Avec Circuit Breaker, Retry et Fallback pour résilience
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final MinioClient minioClient;
    private final UnifiedEncryptionService unifiedEncryptionService;

    /**
     * Compte les echecs d'operation sur le stockage objet.
     *
     * <p>{@code MinioHealthIndicator} dit si MinIO <b>repond</b> ; il ne dit
     * rien des ecritures qui echouent alors que le service repond. Le comptage
     * est pose ici, et non dans le pipeline d'envoi, pour couvrir
     * <b>toutes</b> les ecritures — y compris celles de l'effacement RGPD et
     * des vignettes.
     *
     * <p>{@code doOnError} observe sans consommer : le signal d'erreur continue
     * sa route a l'identique. Et il se declenche <b>avant</b> un eventuel repli
     * de disjoncteur, donc la panne MinIO reste comptee meme le jour ou les
     * annotations Resilience4j deviendront actives.
     */
    private final DocumentMetricsService documentMetricsService;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${storage.fallback.path:./fallback-storage}")
    private String fallbackStoragePath;

    @PostConstruct
    public void init() {
        createBucketIfNotExists().subscribe();
    }

    private Mono<Void> createBucketIfNotExists() {
        return Mono.fromCallable(() -> {
            boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucketName).build()
            );
            
            if (!exists) {
                minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(bucketName).build()
                );
                log.info("Bucket créé: {}", bucketName);
            } else {
                log.info("Bucket existe déjà: {}", bucketName);
            }
            return null;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

    @Override
    @CircuitBreaker(name = "minio-storage", fallbackMethod = "uploadFileFallback")
    @Retry(name = "minio-storage")
    public Mono<String> uploadFile(String fileName, Flux<DataBuffer> fileData, 
                                    String contentType, long fileSize) {
        String sanitizedFileName = FileNameSanitizer.sanitize(fileName);
        
        return DataBufferUtils.join(fileData)
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                
                return Mono.fromCallable(() -> {
                    try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
                        minioClient.putObject(
                            PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(sanitizedFileName)
                                .stream(inputStream, fileSize, -1)
                                .contentType(contentType)
                                .build()
                        );
                        
                        log.info("Fichier uploadé sur MinIO: {}", sanitizedFileName);
                        return sanitizedFileName;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
            })
            .doOnError(e -> documentMetricsService.incrementStorageFailureCounter("DEPOT"))
            .onErrorMap(e -> new StorageException("Échec de l'upload du fichier: " + fileName, e));
    }
    
    private Mono<String> uploadFileFallback(String fileName, Flux<DataBuffer> fileData, 
                                             String contentType, long fileSize, Throwable t) {
        log.warn("MinIO indisponible, utilisation du stockage local chiffré pour: {}", fileName);
        
        return DataBufferUtils.join(fileData)
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                
                //  Utiliser le stockage chiffré
                return unifiedEncryptionService.encryptAndSaveToFallback(fileName, bytes)
                    .doOnSuccess(path -> log.info("Fichier chiffré et sauvegardé en fallback: {}", path));
            });
    }

    @Override
    @CircuitBreaker(name = "minio-storage", fallbackMethod = "uploadFileFromBytesFallback")
    @Retry(name = "minio-storage")
    public Mono<String> uploadFileFromBytes(String path, byte[] content, String contentType) {
        String sanitizedPath = FileNameSanitizer.sanitize(path);
        
        return Mono.fromCallable(() -> {
            log.debug("Uploading file from bytes to MinIO: {} ({} bytes)", sanitizedPath, content.length);

            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(content)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(bucketName)
                                .object(sanitizedPath)
                                .stream(inputStream, content.length, -1)
                                .contentType(contentType)
                                .build()
                );

                log.info("File uploaded successfully from bytes: {}", sanitizedPath);
                return sanitizedPath;

            } catch (Exception e) {
                log.error("Failed to upload file from bytes to MinIO: {}", path, e);
                throw new StorageException("Failed to upload file from bytes", e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> documentMetricsService.incrementStorageFailureCounter("DEPOT"));
    }
    
    private Mono<String> uploadFileFromBytesFallback(String path, byte[] content, 
                                                       String contentType, Throwable t) {
        log.warn("MinIO indisponible, utilisation du stockage local chiffré pour: {}", path);
        
        //  Utiliser le stockage chiffré
        return unifiedEncryptionService.encryptAndSaveToFallback(path, content)
            .doOnSuccess(savedPath -> log.info("File encrypted and saved to fallback: {}", savedPath));
    }

    @Override
    @CircuitBreaker(name = "minio-storage", fallbackMethod = "downloadFileFallback")
    @Retry(name = "minio-storage")
    public Mono<byte[]> downloadFile(String fileName) {
        return Mono.fromCallable(() -> {
            try (InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fileName)
                        .build())) {
                
                byte[] bytes = stream.readAllBytes();
                log.info("Fichier téléchargé depuis MinIO: {}", fileName);
                return bytes;
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> documentMetricsService.incrementStorageFailureCounter("LECTURE"))
        .onErrorMap(e -> new StorageException("Échec du téléchargement du fichier: " + fileName, e));
    }
    
    private Mono<byte[]> downloadFileFallback(String fileName, Throwable t) {
        log.warn("MinIO indisponible, tentative de récupération depuis stockage local: {}", fileName);
        return Mono.fromCallable(() -> {
            Path fallbackPath = Paths.get(fallbackStoragePath, fileName);
            if (Files.exists(fallbackPath)) {
                byte[] bytes = Files.readAllBytes(fallbackPath);
                log.info("Fichier récupéré depuis stockage local: {}", fileName);
                return bytes;
            } else {
                log.error("Fichier introuvable en local: {}", fileName);
                throw new StorageException("Fichier introuvable: " + fileName);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @CircuitBreaker(name = "minio-storage", fallbackMethod = "deleteFileFallback")
    @Retry(name = "minio-storage")
    public Mono<Void> deleteFile(String fileName) {
        return Mono.fromRunnable(() -> {
            try {
                minioClient.removeObject(
                    RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fileName)
                        .build()
                );
                log.info("Fichier supprimé de MinIO: {}", fileName);
            } catch (Exception e) {
                throw new StorageException("Échec de la suppression du fichier: " + fileName, e);
            }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(e -> documentMetricsService.incrementStorageFailureCounter("SUPPRESSION"))
        .then();
    }
    
    private Mono<Void> deleteFileFallback(String fileName, Throwable t) {
        log.warn("MinIO indisponible, suppression du fichier local: {}", fileName);
        return Mono.fromRunnable(() -> {
            try {
                Path fallbackPath = Paths.get(fallbackStoragePath, fileName);
                if (Files.exists(fallbackPath)) {
                    Files.delete(fallbackPath);
                    log.info("Fichier local supprimé: {}", fileName);
                }
            } catch (Exception e) {
                log.error("Erreur suppression fichier local: {}", fileName, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Boolean> fileExists(String fileName) {
        return Mono.fromCallable(() -> {
            try {
                minioClient.statObject(
                    StatObjectArgs.builder()
                        .bucket(bucketName)
                        .object(fileName)
                        .build()
                );
                return true;
            } catch (Exception e) {
                return false;
            }
        })
        .subscribeOn(Schedulers.boundedElastic());
    }


    /** Plafond de validite d'un lien signe. MinIO accepte sept jours ; pas nous. */
    private static final Duration DUREE_MAXIMALE_DU_LIEN = Duration.ofMinutes(15);

    /**
     * {@inheritDoc}
     *
     * <h3>La duree est BORNEE ici, pas chez l'appelant</h3>
     *
     * Un appelant distrait demanderait sept jours — le maximum que MinIO
     * accepte — et l'URL circulerait une semaine. Le plafond vit donc du cote
     * qui signe, parce que c'est lui qui porte la consequence.
     *
     * <h3>{@code boundedElastic}, a verifier plutot qu'a supposer</h3>
     *
     * La signature est un calcul HMAC local, donc en principe non bloquante.
     * « En principe » ne suffit pas sur le chemin d'une requete : le SDK peut
     * relire une configuration ou resoudre une region. L'isoler coute un saut
     * de fil et retire la question.
     */
    @Override
    public Mono<String> genererLienSigne(String chemin, Duration duree) {
        if (chemin == null || chemin.isBlank()) {
            return Mono.empty();
        }
        Duration effective = (duree == null || duree.compareTo(DUREE_MAXIMALE_DU_LIEN) > 0)
                ? DUREE_MAXIMALE_DU_LIEN : duree;

        return Mono.fromCallable(() -> minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(bucketName)
                                .object(chemin)
                                .expiry((int) effective.toSeconds(), TimeUnit.SECONDS)
                                .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(panne -> {
                    // Vide, et non une erreur : un lien non signable fait
                    // retomber l'appelant sur le telechargement ordinaire. Une
                    // erreur ferait disparaitre la photo au lieu de la servir
                    // plus lentement.
                    log.warn("Lien signe impossible ({}) : l'appelant retombera "
                            + "sur le telechargement ordinaire",
                            panne.getClass().getSimpleName());
                    return Mono.empty();
                });
    }
}
