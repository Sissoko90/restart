package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.exception.StorageException;
import com.abdatytch.media_service.service.StreamingUploadService;
import com.abdatytch.media_service.utils.FileNameSanitizer;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Implémentation du streaming upload avec backpressure
 * Évite de charger tout le fichier en mémoire
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StreamingUploadServiceImpl implements StreamingUploadService {

    private final MinioClient minioClient;
    
    @Value("${minio.bucket-name}")
    private String bucketName;
    
    private static final int BUFFER_SIZE = 8192; // 8KB chunks

    @Override
    public Mono<String> uploadFileStreaming(String fileName, Flux<DataBuffer> fileData, String contentType) {
        String sanitizedFileName = FileNameSanitizer.sanitize(fileName);
        
        return Mono.fromCallable(() -> {
            PipedOutputStream outputStream = new PipedOutputStream();
            PipedInputStream inputStream = new PipedInputStream(outputStream, BUFFER_SIZE * 10);
            
            // Stream asynchrone vers MinIO
            fileData
                .doOnNext(dataBuffer -> {
                    try {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        outputStream.write(bytes);
                        DataBufferUtils.release(dataBuffer);
                    } catch (Exception e) {
                        log.error("Error writing chunk", e);
                        throw new RuntimeException(e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        outputStream.close();
                        log.debug("Stream writing completed");
                    } catch (Exception e) {
                        log.error("Error closing output stream", e);
                    }
                })
                .doOnError(e -> {
                    try {
                        outputStream.close();
                    } catch (Exception ex) {
                        log.error("Error closing output stream on error", ex);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
            
            // Upload vers MinIO en streaming
            try {
                minioClient.putObject(
                    PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(sanitizedFileName)
                        .stream(inputStream, -1, 10485760) // 10MB part size
                        .contentType(contentType)
                        .build()
                );
                
                log.info("File streamed to MinIO: {}", sanitizedFileName);
                return sanitizedFileName;
                
            } catch (Exception e) {
                log.error("Failed to stream file to MinIO: {}", sanitizedFileName, e);
                throw new StorageException("Failed to stream upload file", e);
            } finally {
                try {
                    inputStream.close();
                } catch (Exception e) {
                    log.error("Error closing input stream", e);
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Long> calculateStreamSize(Flux<DataBuffer> dataBuffers) {
        AtomicLong totalSize = new AtomicLong(0);
        
        return dataBuffers
            .doOnNext(buffer -> {
                totalSize.addAndGet(buffer.readableByteCount());
                DataBufferUtils.release(buffer);
            })
            .then(Mono.just(totalSize.get()));
    }
}
