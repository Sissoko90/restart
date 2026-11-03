package com.abdatytch.media_service.service;

import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service de streaming upload pour éviter OutOfMemoryError
 */
public interface StreamingUploadService {
    
    /**
     * Upload un fichier en streaming avec backpressure
     * @param fileName Nom du fichier
     * @param fileData Flux de données
     * @param contentType Type MIME
     * @return Chemin du fichier uploadé
     */
    Mono<String> uploadFileStreaming(String fileName, Flux<DataBuffer> fileData, String contentType);
    
    /**
     * Calcule la taille d'un flux sans le charger en mémoire
     */
    Mono<Long> calculateStreamSize(Flux<DataBuffer> dataBuffers);
}
