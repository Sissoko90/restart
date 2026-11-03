package com.abdatytch.security_service.dto.response;

import com.abdatytch.security_service.entities.RegleDIp;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Une regle de blocage, avec son motif, son auteur et son echeance. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Regle de blocage d'adresse, et la trace de la decision")
public class RegleDIpResponse {

    @Schema(description = "Identifiant, pour lever la regle")
    private String id;

    /**
     * Adresse sous sa forme canonique.
     *
     * <p>C'est la forme reellement enregistree, et donc la cle que
     * l'applicateur interroge — pas celle qui a ete saisie. Rendre la saisie
     * laisserait croire qu'une regle porte sur {@code 041.223.010.005} alors
     * qu'elle porte sur {@code 41.223.10.5}.
     */
    @Schema(description = "Adresse normalisee, telle qu'enregistree",
            example = "203.0.113.7")
    private String adresse;

    @Schema(description = "Pourquoi cette adresse est bloquee")
    private String motif;

    @Schema(description = "Precision laissee par l'auteur")
    private String commentaire;

    @Schema(description = "Fin du blocage. Absente pour une regle permanente")
    private Instant expireLe;

    @Schema(description = "Qui a pose la regle, ou le nom du filtre pour une detection")
    private String posePar;

    private Instant creeLe;

    /**
     * Etat a l'instant de la lecture.
     *
     * <p>Calcule et non stocke : une regle cesse de bloquer au passage de son
     * echeance, sans que personne ecrive quoi que ce soit. Une colonne
     * « active » demanderait une tache qui la met a jour, donc un second
     * mecanisme susceptible de divergence.
     */
    @Schema(description = "Vrai si la regle bloque effectivement maintenant")
    private Boolean active;

    @Schema(description = "Levee de la regle. Absente si elle court toujours")
    private Instant leveeLe;

    @Schema(description = "Qui a leve la regle")
    private String leveePar;

    public static RegleDIpResponse de(RegleDIp regle, Instant maintenant) {
        return RegleDIpResponse.builder()
                .id(regle.getId())
                .adresse(regle.getAdresse())
                .motif(regle.getMotif() != null ? regle.getMotif().name() : null)
                .commentaire(regle.getCommentaire())
                .expireLe(regle.getExpireLe())
                .posePar(regle.getPosePar())
                .creeLe(regle.getCreeLe())
                .active(regle.estActive(maintenant))
                .leveeLe(regle.getLeveeLe())
                .leveePar(regle.getLeveePar())
                .build();
    }
}
