package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;

/**
 * Interface pour le service de stockage (MinIO/S3)
 */
public interface StorageService {
    
    Mono<String> uploadFile(String fileName, Flux<DataBuffer> fileData, String contentType, long fileSize);
    
    Mono<String> uploadFileFromBytes(String path, byte[] content, String contentType);
    
    Mono<byte[]> downloadFile(String fileName);
    
    Mono<Void> deleteFile(String fileName);
    
    Mono<Boolean> fileExists(String fileName);
    
    // `generatePresignedUrl` a ete RETIRE.
    //
    // Il fabriquait une URL pointant DIRECTEMENT sur MinIO, donc contournant la
    // passerelle — seule porte de cette plateforme —, le controle de propriete,
    // le controle de statut et le dechiffrement. Son unique appelant lui passait
    // 3600 en croyant des secondes, alors que le parametre etait en MINUTES :
    // 60 heures de validite pour un lien rendant des octets chiffres, donc
    // illisibles.
    //
    // Un risque sans benefice. `getDocumentDetails` rend desormais le chemin
    // public de telechargement, qui passe par la passerelle et par
    // `downloadDocument`.
    //
    // Si un acces direct au stockage redevient necessaire un jour, il faudra le
    // reintroduire avec une duree en SECONDES, un nom de parametre qui le dit,
    // et une decision explicite sur les trois controles qu'il contourne.
}
