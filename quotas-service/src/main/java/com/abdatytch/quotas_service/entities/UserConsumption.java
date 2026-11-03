package com.abdatytch.quotas_service.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Consommation courante d'un utilisateur, toutes ressources confondues.
 *
 * <p>Les compteurs cumulatifs (projets, collaborateurs, stockage) montent et
 * descendent. Le compteur d'appels API est associé à une fenêtre horaire
 * ({@link #apiCallsWindowStart}) : dès que l'heure change, il repart de zéro.
 */
@Table("user_consumption")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserConsumption {

    @PrimaryKey("user_id")
    private String userId;

    @Column("projects_count")
    private long projectsCount;

    @Column("collaborators_count")
    private long collaboratorsCount;

    @Column("storage_mb_used")
    private long storageMbUsed;

    @Column("api_calls_count")
    private long apiCallsCount;

    /** Début de la fenêtre horaire courante pour le compteur d'appels API. */
    @Column("api_calls_window_start")
    private Instant apiCallsWindowStart;

    private Instant updatedAt;

    public static UserConsumption emptyFor(String userId) {
        return UserConsumption.builder()
                .userId(userId)
                .projectsCount(0)
                .collaboratorsCount(0)
                .storageMbUsed(0)
                .apiCallsCount(0)
                .apiCallsWindowStart(currentWindow())
                .updatedAt(Instant.now())
                .build();
    }

    /** Début de l'heure courante, utilisé comme identifiant de fenêtre. */
    public static Instant currentWindow() {
        return Instant.now().truncatedTo(ChronoUnit.HOURS);
    }

    /**
     * Remet le compteur d'appels API à zéro si la fenêtre horaire a changé.
     * À appeler avant toute lecture ou écriture de {@link #apiCallsCount}.
     */
    public void rollApiWindowIfNeeded() {
        Instant window = currentWindow();
        if (apiCallsWindowStart == null || !apiCallsWindowStart.equals(window)) {
            apiCallsWindowStart = window;
            apiCallsCount = 0;
        }
    }
}
