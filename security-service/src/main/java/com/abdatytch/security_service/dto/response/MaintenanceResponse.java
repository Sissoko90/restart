package com.abdatytch.security_service.dto.response;

import com.abdatytch.security_service.entities.FenetreDeMaintenance;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Une fenetre de maintenance, telle qu'un administrateur a besoin de la voir.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Fenetre de maintenance, et son etat a l'instant de la lecture")
public class MaintenanceResponse {

    @Schema(description = "Identifiant, pour lever la fenetre")
    private String id;

    @Schema(description = "Service coupe, ou « * » pour toute la plateforme")
    private String service;

    @Schema(description = "Message lu par l'appelant pendant la coupure")
    private String message;

    private Instant debuteLe;

    @Schema(description = "Fin annoncee. Absente = coupure sans terme")
    private Instant finitLe;

    @Schema(description = "Qui a coupe")
    private String activeePar;

    /**
     * Etat a l'instant de la lecture.
     *
     * <p>Calcule et non stocke : une fenetre devient active au passage de son
     * heure de debut, sans que personne ecrive quoi que ce soit. Une colonne
     * « active » demanderait une tache qui la met a jour, donc un second
     * mecanisme susceptible de divergence — le defaut que ce service existe pour
     * eviter.
     */
    @Schema(description = "Vrai si la fenetre coupe effectivement maintenant")
    private Boolean active;

    @Schema(description = "Vrai si la fenetre est annoncee mais pas encore commencee")
    private Boolean aVenir;

    @Schema(description = "Levee anticipee. Absent si la fenetre court toujours")
    private Instant leveeLe;

    public static MaintenanceResponse de(FenetreDeMaintenance fenetre, Instant maintenant) {
        boolean aVenir = fenetre.getLeveeLe() == null
                && fenetre.getDebuteLe() != null
                && fenetre.getDebuteLe().isAfter(maintenant);

        return MaintenanceResponse.builder()
                .id(fenetre.getId())
                .service(fenetre.getService())
                .message(fenetre.getMessage())
                .debuteLe(fenetre.getDebuteLe())
                .finitLe(fenetre.getFinitLe())
                .activeePar(fenetre.getActiveePar())
                .active(fenetre.estActive(maintenant))
                .aVenir(aVenir)
                .leveeLe(fenetre.getLeveeLe())
                .build();
    }
}
