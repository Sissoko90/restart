package com.abdatytch.quotas_service.enums;

/**
 * Ressource soumise à quota.
 *
 * <p>Deux natures différentes :
 * <ul>
 *   <li><b>cumulative</b> ({@link #PROJECTS}, {@link #COLLABORATORS}, {@link #STORAGE_MB}) :
 *       la consommation monte et descend, elle doit être libérée à la suppression ;</li>
 *   <li><b>par fenêtre</b> ({@link #API_CALLS}) : la consommation est remise à zéro
 *       à chaque nouvelle heure.</li>
 * </ul>
 */
public enum QuotaResource {

    PROJECTS(false),
    COLLABORATORS(false),
    STORAGE_MB(false),
    API_CALLS(true);

    private final boolean windowed;

    QuotaResource(boolean windowed) {
        this.windowed = windowed;
    }

    /**
     * @return true si la consommation est remise à zéro à chaque fenêtre horaire
     */
    public boolean isWindowed() {
        return windowed;
    }
}
