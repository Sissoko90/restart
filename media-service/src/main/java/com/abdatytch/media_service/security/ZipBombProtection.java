package com.abdatytch.media_service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**

 * Protection contre les attaques Zip Bomb
 *
 * Une Zip Bomb est un fichier compressé malveillant qui, une fois décompressé,
 * peut atteindre des tailles énormes (ex: 42.zip = 42KB compressé  4.5 PB décompressé)
 *
 * Cette classe détecte les Zip Bombs en analysant:
 * 1. Le ratio de compression (taille décompressée / taille compressée)
 * 2. Le nombre de fichiers imbriqués (nested archives)
 * 3. La taille totale décompressée
 */
@Component
@Slf4j
public class ZipBombProtection {

    @Value("${security.zipbomb.max-compression-ratio:100}")
    private int maxCompressionRatio;

    @Value("${security.zipbomb.max-uncompressed-size-mb:500}")
    private long maxUncompressedSizeMb;

    @Value("${security.zipbomb.max-entries:1000}")
    private int maxEntries;

    @Value("${security.zipbomb.max-nested-depth:3}")
    private int maxNestedDepth;

    @Value("${security.zipbomb.enabled:true}")
    private boolean enabled;

    private static final String[] ZIP_SIGNATURES = {
        "application/zip",
        "application/x-zip-compressed",
        "application/x-zip",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
        "application/x-rar-compressed",
        "application/x-7z-compressed"};

    public boolean isCompressedFile(String contentType) {
        if (contentType == null) return false;
        for (String sig : ZIP_SIGNATURES) {
            if (contentType.toLowerCase().contains(sig)) {
                return true;
            }
        }
        return false;
    }

    public boolean isCompressedByExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".gz") || lower.endsWith(".gzip") ||
               lower.endsWith(".tar") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
               lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
    }

    public ZipBombCheckResult checkForZipBomb(byte[] data, String filename) {
        if (!enabled) {
            return ZipBombCheckResult.safe();
        }

        if (data == null || data.length == 0) {
            return ZipBombCheckResult.safe();
        }

        // Vérifier si c'est un fichier ZIP
        if (!isZipFile(data)) {
            return ZipBombCheckResult.safe();
        }

        return analyzeZipContent(data, filename, 0);
    }

    public ZipBombCheckResult checkForZipBomb(InputStream inputStream, long compressedSize, String filename) {
        if (!enabled) {
            return ZipBombCheckResult.safe();
        }

        try {
            byte[] data = inputStream.readAllBytes();
            return checkForZipBomb(data, filename);
        } catch (IOException e) {
            log.error(" Error reading input stream for zip bomb check: {}", e.getMessage());
            return ZipBombCheckResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private boolean isZipFile(byte[] data) {
        if (data.length < 4) return false;
        // ZIP magic number: PK (0x50 0x4B)
        return data[0] == 0x50 && data[1] == 0x4B;
    }

    private ZipBombCheckResult analyzeZipContent(byte[] data, String filename, int depth) {
        if (depth > maxNestedDepth) {
            log.warn(" ZIP BOMB DETECTED: Max nested depth exceeded for file: {}", filename);
            return ZipBombCheckResult.zipBomb("Maximum nested archive depth exceeded (" + maxNestedDepth + ")");
        }

        long compressedSize = data.length;
        long totalUncompressedSize = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;

                // Vérifier le nombre d'entrées
                if (entryCount > maxEntries) {
                    log.warn(" ZIP BOMB DETECTED: Too many entries ({}) in file: {}", entryCount, filename);
                    return ZipBombCheckResult.zipBomb("Too many entries in archive (" + entryCount + " > " + maxEntries + ")");
                }

                if (!entry.isDirectory()) {
                    // Calculer la taille décompressée en lisant le contenu
                    long entrySize = 0;
                    int bytesRead;
                    ByteArrayOutputStream entryData = new ByteArrayOutputStream();

                    while ((bytesRead = zis.read(buffer)) != -1) {
                        entrySize += bytesRead;
                        totalUncompressedSize += bytesRead;

                        // Vérification en temps réel de la taille
                        long maxBytes = maxUncompressedSizeMb * 1024 * 1024;
                        if (totalUncompressedSize > maxBytes) {
                            log.warn(" ZIP BOMB DETECTED: Uncompressed size exceeds limit for file: {} (>{} MB)",
                                filename, maxUncompressedSizeMb);
                            return ZipBombCheckResult.zipBomb("Uncompressed size exceeds maximum (" + maxUncompressedSizeMb + " MB)");
                        }

                        // Vérification du ratio de compression en temps réel
                        if (compressedSize > 0) {
                            double ratio = (double) totalUncompressedSize / compressedSize;
                            if (ratio > maxCompressionRatio) {
                                log.warn(" ZIP BOMB DETECTED: Compression ratio {} exceeds limit {} for file: {}",
                                    String.format("%.2f", ratio), maxCompressionRatio, filename);
                                return ZipBombCheckResult.zipBomb("Compression ratio too high (" +
                                    String.format("%.2f", ratio) + " > " + maxCompressionRatio + ")");
                            }
                        }

                        entryData.write(buffer, 0, bytesRead);
                    }

                    // Vérifier si l'entrée est elle-même un fichier ZIP (nested)
                    byte[] entryBytes = entryData.toByteArray();
                    if (isZipFile(entryBytes)) {
                        ZipBombCheckResult nestedResult = analyzeZipContent(entryBytes, entry.getName(), depth + 1);
                        if (!nestedResult.isSafe()) {
                            return nestedResult;
                        }
                    }
                }

                zis.closeEntry();
            }

            // Vérification finale du ratio
            if (compressedSize > 0 && totalUncompressedSize > 0) {
                double finalRatio = (double) totalUncompressedSize / compressedSize;
                log.debug(" ZIP analysis for {}: compressed={} bytes, uncompressed={} bytes, ratio={}, entries={}",
                    filename, compressedSize, totalUncompressedSize, String.format("%.2f", finalRatio), entryCount);
            }

            return ZipBombCheckResult.safe();

        } catch (IOException e) {
            log.error(" Error analyzing ZIP file {}: {}", filename, e.getMessage());
            return ZipBombCheckResult.error("Failed to analyze archive: " + e.getMessage());
        }
    }

    public record ZipBombCheckResult(boolean isSafe, boolean isZipBomb, String reason) {
        public static ZipBombCheckResult safe() {
            return new ZipBombCheckResult(true, false, null);
        }

        public static ZipBombCheckResult zipBomb(String reason) {
            return new ZipBombCheckResult(false, true, reason);
        }

        public static ZipBombCheckResult error(String reason) {
            return new ZipBombCheckResult(false, false, reason);
        }
    }
}
