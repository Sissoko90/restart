package com.abdatytch.security_service.dto.request;

import com.abdatytch.security_service.constant.SecurityConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Ouverture d'une fenetre de maintenance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Coupure annoncee, globale ou limitee a un service")
public class OuvertureDeMaintenanceRequest {

    /**
     * Nom du service, ou {@code *} pour toute la plateforme.
     *
     * <p>Le nom tel que Consul le connait — {@code auth-service} — parce que
     * c'est celui que le gateway utilise pour router. Un nom libre obligerait a
     * une table de correspondance, donc a un endroit de plus ou se tromper.
     */
    @NotBlank(message = SecurityConstants.SERVICE_REQUIS)
    @Size(max = 60)
    @Schema(description = "Nom Consul du service, ou « * » pour toute la plateforme",
            example = "auth-service", requiredMode = Schema.RequiredMode.REQUIRED)
    private String service;

    /**
     * Message affiche a l'appelant. Obligatoire.
     *
     * <p>Une maintenance sans message rend un refus que personne ne sait
     * interpreter : le client ne distingue pas une coupure voulue d'une panne,
     * et appelle le support pour le dire.
     */
    @NotBlank(message = SecurityConstants.MESSAGE_MAINTENANCE_REQUIS)
    @Size(max = 500)
    @Schema(description = "Ce que lira l'appelant pendant la coupure",
            example = "Mise a jour en cours, retour prevu a 14h00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(description = "Debut. Absent = immediat. Dans le futur = annonce sans couper")
    private Instant debuteLe;

    @Schema(description = "Fin annoncee. Absente = coupure sans terme, signalee comme telle")
    private Instant finitLe;

    /**
     * Le message ne peut pas contenir le separateur du cache.
     *
     * <p>Le cache serialise {@code message|echeance} en plat, parce que le
     * gateway le lit sur le chemin de chaque requete et qu'un desserialisateur
     * JSON y ajouterait du travail pour deux champs. Un message contenant une
     * barre verticale serait donc decoupe au mauvais endroit, et l'appelant
     * lirait un message tronque avec une echeance absurde.
     *
     * <p>Verifie ici plutot que corrige en silence : alterer le message d'un
     * administrateur sans le lui dire est pire que le refuser.
     */
    @AssertTrue(message = SecurityConstants.MESSAGE_MAINTENANCE_SEPARATEUR_INTERDIT)
    public boolean isMessageSansSeparateur() {
        return message == null || !message.contains(SecurityConstants.SEPARATEUR_MAINTENANCE);
    }

    /**
     * La fin ne peut pas preceder le debut.
     *
     * <p>Une fenetre deja terminee a l'ouverture ne couperait rien, et l'appel
     * rendrait pourtant un succes : l'administrateur croirait la maintenance
     * active.
     */
    @AssertTrue(message = SecurityConstants.FENETRE_DEJA_TERMINEE)
    public boolean isFenetreCoherente() {
        if (debuteLe == null || finitLe == null) {
            return true;
        }
        return finitLe.isAfter(debuteLe);
    }
}
