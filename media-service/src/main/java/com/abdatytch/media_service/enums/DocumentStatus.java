package com.abdatytch.media_service.enums;

/**
 * Statut de validation d'un document
 */
public enum DocumentStatus {
    PENDING_UPLOAD("En attente d'upload"),
    UPLOADED("Uploadé - En attente de validation"),
    UNDER_REVIEW("En cours de vérification"),
    APPROVED("Approuvé"),
    REJECTED("Rejeté"),
    EXPIRED("Expiré"),
    FLAGGED_SUSPICIOUS("Signalé suspect - Fraude potentielle"),
    FLAGGED_POOR_QUALITY("Signalé - Qualité insuffisante"),
    FLAGGED_MALWARE("Signalé - Malware détecté");
    
    private final String displayName;
    
    DocumentStatus(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public boolean isValid() {
        return this == APPROVED;
    }
    
    public boolean needsAction() {
        return this == PENDING_UPLOAD || this == REJECTED || this == EXPIRED || 
               this == FLAGGED_POOR_QUALITY;
    }
    
    public boolean isFlagged() {
        return this == FLAGGED_SUSPICIOUS || this == FLAGGED_POOR_QUALITY || this == FLAGGED_MALWARE;
    }
}
