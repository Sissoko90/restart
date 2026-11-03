package com.abdatytch.interface_service.enums;

/**
 * Statuts possibles d'un projet
 */
public enum ProjectStatus {
    ACTIVE("Projet actif"),
    SUSPENDED("Projet suspendu"),
    ARCHIVED("Projet archivé"),
    DELETED("Projet supprimé");

    private final String description;

    ProjectStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
