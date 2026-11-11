package com.abdatytch.media_service.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class FileNameSanitizer {

    private static final int MAX_FILENAME_LENGTH = 255;

    public static String sanitize(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String sanitized = fileName
            .replaceAll("\\.\\./", "")
            .replaceAll("\\.\\\\", "")
            .replaceAll("[^a-zA-Z0-9._-]", "_")
            .replaceAll("_{2,}", "_");

        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            String extension = "";
            int dotIndex = sanitized.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = sanitized.substring(dotIndex);
                sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH - extension.length()) + extension;
            } else {
                sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH);
            }
        }

        if (!sanitized.equals(fileName)) {
            log.debug("Filename sanitized: {} -> {}", fileName, sanitized);
        }

        return sanitized;
    }
}
