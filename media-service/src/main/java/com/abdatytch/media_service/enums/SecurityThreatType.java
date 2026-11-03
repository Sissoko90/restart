package com.abdatytch.media_service.enums;

/**
 * Types de menaces de sécurité détectées
 */
public enum SecurityThreatType {
    SCRIPT_INJECTION("Injection de script dans métadonnées"),
    XSS_ATTEMPT("Tentative XSS"),
    SQL_INJECTION("Tentative SQL Injection"),
    PATH_TRAVERSAL("Tentative Path Traversal"),
    MALICIOUS_METADATA("Métadonnées malveillantes"),
    EXECUTABLE_CONTENT("Contenu exécutable détecté"),
    SUSPICIOUS_MIME_TYPE("Type MIME suspect"),
    FILE_SIZE_ANOMALY("Taille de fichier anormale"),
    DUPLICATE_HASH("Hash dupliqué suspect"),
    KNOWN_MALWARE_SIGNATURE("Signature malware connue");
    
    private final String description;
    
    SecurityThreatType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
