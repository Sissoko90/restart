package com.abdatytch.media_service.service.impl;

import com.abdatytch.media_service.exception.StorageException;
import com.abdatytch.media_service.service.UnifiedEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Service unifié de chiffrement AES-256-GCM
 * Fusionne EncryptionServiceImpl et EncryptionStorageServiceImpl
 */
@Service
@Slf4j
public class UnifiedEncryptionServiceImpl implements UnifiedEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final int AES_KEY_SIZE = 256;

    // Aucun défaut : une clé maître par défaut chiffrerait tous les documents
    // sous une valeur publiquement connue.
    @Value("${encryption.master-key}")
    private String masterKey;

    @Value("${encryption.enabled:true}")
    private boolean encryptionEnabled;

    @Value("${storage.fallback.path:./fallback-storage}")
    private String fallbackStoragePath;
    
    @Value("${storage.encryption.key:}")
    private String fallbackEncryptionKeyBase64;
    
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKey fallbackSecretKey;

    @Override
    public Mono<byte[]> encryptDocument(byte[] content, String userId, String documentId) {
        if (!encryptionEnabled) {
            log.debug("Encryption disabled, returning plain content");
            return Mono.just(content);
        }

        return Mono.fromCallable(() -> {
            try {
                SecretKey secretKey = deriveDocumentKey(userId, documentId);
                
                byte[] iv = new byte[GCM_IV_LENGTH];
                secureRandom.nextBytes(iv);
                
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);
                
                byte[] encryptedData = cipher.doFinal(content);
                
                ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encryptedData.length);
                byteBuffer.put(iv);
                byteBuffer.put(encryptedData);
                
                log.debug("Document encrypted successfully for userId: {}, documentId: {}", userId, documentId);
                return byteBuffer.array();
            } catch (Exception e) {
                log.error("Encryption failed for userId: {}, documentId: {}", userId, documentId, e);
                throw new StorageException("Document encryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> decryptDocument(byte[] encryptedContent, String userId, String documentId) {
        if (!encryptionEnabled) {
            log.debug("Encryption disabled, returning content as-is");
            return Mono.just(encryptedContent);
        }

        return Mono.fromCallable(() -> {
            try {
                SecretKey secretKey = deriveDocumentKey(userId, documentId);
                
                ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedContent);
                
                byte[] iv = new byte[GCM_IV_LENGTH];
                byteBuffer.get(iv);
                
                byte[] encryptedData = new byte[byteBuffer.remaining()];
                byteBuffer.get(encryptedData);
                
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);
                
                byte[] decryptedData = cipher.doFinal(encryptedData);
                
                log.debug("Document decrypted successfully for userId: {}, documentId: {}", userId, documentId);
                return decryptedData;
            } catch (Exception e) {
                log.error("Decryption failed for userId: {}, documentId: {}", userId, documentId, e);
                throw new StorageException("Document decryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> encryptAndSaveToFallback(String fileName, byte[] data) {
        return Mono.fromCallable(() -> {
            try {
                if (fallbackSecretKey == null) {
                    initializeFallbackKey();
                }
                
                byte[] iv = new byte[GCM_IV_LENGTH];
                secureRandom.nextBytes(iv);
                
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.ENCRYPT_MODE, fallbackSecretKey, parameterSpec);
                
                byte[] encryptedData = cipher.doFinal(data);
                
                ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encryptedData.length);
                byteBuffer.put(iv);
                byteBuffer.put(encryptedData);
                
                Path storagePath = Paths.get(fallbackStoragePath);
                if (!Files.exists(storagePath)) {
                    Files.createDirectories(storagePath);
                }
                
                String encryptedFileName = fileName + ".enc";
                Path filePath = storagePath.resolve(encryptedFileName);
                Files.write(filePath, byteBuffer.array());
                
                log.info("File encrypted and saved to fallback storage: {}", encryptedFileName);
                return filePath.toString();
            } catch (Exception e) {
                log.error("Failed to encrypt and save file: {}", fileName, e);
                throw new StorageException("Fallback encryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<byte[]> loadAndDecryptFromFallback(String encryptedPath) {
        return Mono.fromCallable(() -> {
            try {
                if (fallbackSecretKey == null) {
                    initializeFallbackKey();
                }
                
                Path filePath = Paths.get(encryptedPath);
                byte[] encryptedContent = Files.readAllBytes(filePath);
                
                ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedContent);
                
                byte[] iv = new byte[GCM_IV_LENGTH];
                byteBuffer.get(iv);
                
                byte[] encryptedData = new byte[byteBuffer.remaining()];
                byteBuffer.get(encryptedData);
                
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                cipher.init(Cipher.DECRYPT_MODE, fallbackSecretKey, parameterSpec);
                
                byte[] decryptedData = cipher.doFinal(encryptedData);
                
                log.debug("File decrypted from fallback storage: {}", encryptedPath);
                return decryptedData;
            } catch (Exception e) {
                log.error("Failed to load and decrypt file: {}", encryptedPath, e);
                throw new StorageException("Fallback decryption failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteFromFallback(String encryptedPath) {
        return Mono.fromRunnable(() -> {
            try {
                Path filePath = Paths.get(encryptedPath);
                Files.deleteIfExists(filePath);
                log.info("Deleted encrypted file from fallback storage: {}", encryptedPath);
            } catch (IOException e) {
                log.error("Failed to delete file: {}", encryptedPath, e);
                throw new StorageException("Fallback file deletion failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<String> generateFileHash(byte[] content) {
        return Mono.fromCallable(() -> {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(content);
                return Base64.getEncoder().encodeToString(hash);
            } catch (Exception e) {
                log.error("Failed to generate file hash", e);
                throw new StorageException("Hash generation failed", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Dérive la clé propre à un document, par HMAC-SHA256 sur des champs de
     * longueur explicite.
     *
     * <h3>Ce que corrige cette version</h3>
     *
     * La dérivation précédente concaténait les champs avec un séparateur :
     *
     * <pre>{@code String keyMaterial = userId + ":" + documentId + ":" + masterKey;}</pre>
     *
     * Cette écriture est <b>ambiguë</b> : le couple {@code ("alice", "photo:recto")}
     * et le couple {@code ("alice:photo", "recto")} produisent exactement la même
     * chaîne, donc la <b>même clé</b>. Le document de l'un devenait déchiffrable
     * sous l'identité de l'autre — précisément la séparation que cette dérivation
     * est censée garantir. Les identifiants étant des UUID en pratique, le cas ne
     * se présentait pas ; mais faire reposer une propriété de sécurité sur le fait
     * qu'une donnée « ne contient pas de deux-points » est une hypothèse qui
     * finit toujours par tomber.
     *
     * <p>Préfixer chaque champ de sa longueur rend l'encodage injectif : deux
     * couples distincts ne peuvent plus produire le même matériel.
     *
     * <p>La clé maîtresse sert désormais de <b>clé</b> HMAC et non de simple
     * complément haché : sa valeur ne transite plus dans un message dont une
     * partie est fournie par l'appelant.
     *
     * <p><b>Conséquence sur les données existantes.</b> Les clés dérivées changent :
     * tout document chiffré avec la version précédente devient illisible. Sur une
     * base déjà alimentée, il faudrait déchiffrer avec l'ancienne dérivation puis
     * rechiffrer avec celle-ci avant de déployer.
     */
    private SecretKey deriveDocumentKey(String userId, String documentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(masterKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        byte[] keyBytes = mac.doFinal(lengthPrefixed(userId, documentId));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** Encodage injectif : chaque champ est précédé de sa longueur sur 4 octets. */
    private byte[] lengthPrefixed(String... fields) {
        int total = 0;
        byte[][] encoded = new byte[fields.length][];
        for (int i = 0; i < fields.length; i++) {
            encoded[i] = (fields[i] == null ? "" : fields[i]).getBytes(StandardCharsets.UTF_8);
            total += Integer.BYTES + encoded[i].length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (byte[] field : encoded) {
            buffer.putInt(field.length);
            buffer.put(field);
        }
        return buffer.array();
    }

    private void initializeFallbackKey() throws Exception {
        if (fallbackEncryptionKeyBase64 != null && !fallbackEncryptionKeyBase64.isEmpty()) {
            byte[] keyBytes = Base64.getDecoder().decode(fallbackEncryptionKeyBase64);
            fallbackSecretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("Fallback encryption key loaded from configuration");
        } else {
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            fallbackSecretKey = keyGen.generateKey();
            log.warn("Generated temporary fallback encryption key - configure storage.encryption.key in production!");
        }
    }
}
