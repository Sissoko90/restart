package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.exception.StorageException;
import com.abdatytch.media_service.service.EncryptionStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Implémentation du chiffrement AES-256-GCM pour le stockage local
 */
@Service
@Slf4j
public class EncryptionStorageServiceImpl implements EncryptionStorageService {

    @Value("${storage.fallback.path:./fallback-storage}")
    private String fallbackStoragePath;
    
    @Value("${storage.encryption.key:}")
    private String encryptionKeyBase64;
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    private SecretKey secretKey;

    @Override
    public Mono<String> encryptAndSave(String fileName, byte[] data) {
        return Mono.fromCallable(() -> {
            try {
                // Initialiser la clé si nécessaire
                if (secretKey == null) {
                    initializeKey();
                }
                
                // Générer IV aléatoire
                byte[] iv = new byte[GCM_IV_LENGTH];
                SecureRandom random = new SecureRandom();
                random.nextBytes(iv);
                
                // Chiffrer les données
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
                byte[] encryptedData = cipher.doFinal(data);
                
                // Combiner IV + données chiffrées
                byte[] combined = new byte[iv.length + encryptedData.length];
                System.arraycopy(iv, 0, combined, 0, iv.length);
                System.arraycopy(encryptedData, 0, combined, iv.length, encryptedData.length);
                
                // Sauvegarder
                Path filePath = Paths.get(fallbackStoragePath, fileName + ".enc");
                Files.createDirectories(filePath.getParent());
                Files.write(filePath, combined);
                
                log.info("File encrypted and saved: {}", fileName);
                return fileName;
                
            } catch (Exception e) {
                log.error("Failed to encrypt and save file: {}", fileName, e);
                throw new StorageException("Encryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> decryptAndRead(String fileName) {
        return Mono.fromCallable(() -> {
            try {
                if (secretKey == null) {
                    initializeKey();
                }
                
                Path filePath = Paths.get(fallbackStoragePath, fileName + ".enc");
                byte[] combined = Files.readAllBytes(filePath);
                
                // Extraire IV et données chiffrées
                byte[] iv = new byte[GCM_IV_LENGTH];
                byte[] encryptedData = new byte[combined.length - GCM_IV_LENGTH];
                System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
                System.arraycopy(combined, GCM_IV_LENGTH, encryptedData, 0, encryptedData.length);
                
                // Déchiffrer
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
                byte[] decryptedData = cipher.doFinal(encryptedData);
                
                log.info("File decrypted: {}", fileName);
                return decryptedData;
                
            } catch (Exception e) {
                log.error("Failed to decrypt file: {}", fileName, e);
                throw new StorageException("Decryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteEncrypted(String fileName) {
        return Mono.fromRunnable(() -> {
            try {
                Path filePath = Paths.get(fallbackStoragePath, fileName + ".enc");
                Files.deleteIfExists(filePath);
                log.info("Encrypted file deleted: {}", fileName);
            } catch (IOException e) {
                log.error("Failed to delete encrypted file: {}", fileName, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
    
    private void initializeKey() throws Exception {
        if (encryptionKeyBase64 != null && !encryptionKeyBase64.isEmpty()) {
            byte[] decodedKey = Base64.getDecoder().decode(encryptionKeyBase64);
            secretKey = new SecretKeySpec(decodedKey, "AES");
            log.info("Encryption key loaded from configuration");
        } else {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            secretKey = keyGen.generateKey();
            log.warn("Generated temporary encryption key - configure storage.encryption.key in production!");
        }
    }
}
