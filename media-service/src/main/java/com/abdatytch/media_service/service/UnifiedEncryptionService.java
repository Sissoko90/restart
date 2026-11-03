package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service unifié de chiffrement AES-256-GCM
 * Remplace EncryptionService et EncryptionStorageService
 */
public interface UnifiedEncryptionService {
    
    /**
     * Chiffre le contenu d'un document en mémoire
     * Utilise une clé dérivée par document (userId:documentId:masterKey)
     */
    Mono<byte[]> encryptDocument(byte[] content, String userId, String documentId);
    
    /**
     * Déchiffre le contenu d'un document
     */
    Mono<byte[]> decryptDocument(byte[] encryptedContent, String userId, String documentId);
    
    /**
     * Chiffre et sauvegarde un fichier dans le stockage local (fallback)
     * Utilise une clé unique pour le fallback storage
     */
    Mono<String> encryptAndSaveToFallback(String fileName, byte[] data);
    
    /**
     * Charge et déchiffre un fichier depuis le stockage local
     */
    Mono<byte[]> loadAndDecryptFromFallback(String encryptedPath);
    
    /**
     * Supprime un fichier chiffré du stockage local
     */
    Mono<Void> deleteFromFallback(String encryptedPath);
    
    /**
     * Génère un hash SHA-256 du fichier
     */
    Mono<String> generateFileHash(byte[] content);
}
