package com.abdatytch.media_service.service;

import reactor.core.publisher.Mono;

/**
 * Service de chiffrement pour le stockage local fallback
 */
public interface EncryptionStorageService {
    
    /**
     * Chiffre et sauvegarde un fichier localement
     */
    Mono<String> encryptAndSave(String fileName, byte[] data);
    
    /**
     * Déchiffre et lit un fichier local
     */
    Mono<byte[]> decryptAndRead(String fileName);
    
    /**
     * Supprime un fichier chiffré
     */
    Mono<Void> deleteEncrypted(String fileName);
}
