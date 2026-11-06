package com.abdatytch.auth_service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**

 * Protection contre les attaques Zip Bomb pour auth-service
 */
@Component
@RefreshScope
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

    public ZipBombCheckResult checkForZipBomb(byte[] data, String filename) {
        if (!enabled) {
            return ZipBombCheckResult.safe();
        }

        if (data == null || data.length == 0) {
            return ZipBombCheckResult.safe();
        }

        if (!isZipFile(data)) {
            return ZipBombCheckResult.safe();
        }

        return analyzeZipContent(data, filename, 0);
    }

    private boolean isZipFile(byte[] data) {
        if (data.length < 4) return false;
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

                if (entryCount > maxEntries) {
                    log.warn(" ZIP BOMB DETECTED: Too many entries ({}) in file: {}", entryCount, filename);
                    return ZipBombCheckResult.zipBomb("Too many entries in archive (" + entryCount + " > " + maxEntries + ")");
                }

                if (!entry.isDirectory()) {
                    int bytesRead;
                    ByteArrayOutputStream entryData = new ByteArrayOutputStream();

                    while ((bytesRead = zis.read(buffer)) != -1) {
                        totalUncompressedSize += bytesRead;

                        long maxBytes = maxUncompressedSizeMb * 1024 * 1024;
                        if (totalUncompressedSize > maxBytes) {
                            log.warn(" ZIP BOMB DETECTED: Uncompressed size exceeds limit for file: {} (>{} MB)",
                                filename, maxUncompressedSizeMb);
                            return ZipBombCheckResult.zipBomb("Uncompressed size exceeds maximum (" + maxUncompressedSizeMb + " MB)");
                        }

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
