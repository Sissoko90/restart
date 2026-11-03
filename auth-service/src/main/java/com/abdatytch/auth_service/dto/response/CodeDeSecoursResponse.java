package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.CodeDeSecours;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * L'état d'un code de secours, tel que son propriétaire a besoin de le voir.
 *
 * <h2>Ce qui n'y figure PAS</h2>
 *
 * Le code, ni son empreinte. Un code de secours remplace le second facteur :
 * le rendre dans une liste reviendrait à le publier.
 *
 * <h2>Pourquoi les quatre premiers caractères y figurent, eux</h2>
 *
 * Sans eux, « code consommé le 3 mars depuis 41.223.10.5 » n'apprend rien
 * d'exploitable à la personne : elle ne sait pas <b>lequel</b> de ses dix codes
 * a servi, donc elle ne peut ni le barrer sur son papier ni juger si l'usage lui
 * correspond.
 *
 * <p>Quatre caractères sur dix laissent un espace encore trop grand pour être
 * devinné, et BCrypt protège le reste.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Etat d'un code de secours. Le code lui-meme n'est jamais rendu.")
public class CodeDeSecoursResponse {

    /**
     * Quatre premiers caractères, suivis de points de suspension.
     *
     * <p>Pour que la personne reconnaisse le code sur son papier.
     */
    @Schema(description = "Debut du code, pour le reconnaitre", example = "A3F9...")
    private String debut;

    @Schema(description = "Vrai si ce code peut encore servir")
    private Boolean utilisable;

    /**
     * Quand il a servi.
     *
     * <p>Absent s'il n'a pas servi. Un usage que la personne ne reconnaît pas
     * est le signal le plus clair qu'elle puisse recevoir d'une intrusion.
     */
    @Schema(description = "Quand ce code a servi. Absent s'il n'a pas servi.")
    private LocalDateTime consommeLe;

    @Schema(description = "D'ou il a servi, pour que la personne reconnaisse l'usage")
    private String consommeIp;

    /**
     * Vrai si le code a été remplacé par une régénération.
     *
     * <p>Distinct de « consommé » : un code invalidé n'a jamais servi. Les
     * confondre ferait soupçonner une intrusion inexistante à qui a simplement
     * demandé de nouveaux codes.
     */
    @Schema(description = "Vrai si ce code a ete REMPLACE par une regeneration. "
            + "Distinct de « a servi ».")
    private Boolean remplace;

    public static CodeDeSecoursResponse de(CodeDeSecours code) {
        return CodeDeSecoursResponse.builder()
                .debut(code.getDebut() + "...")
                .utilisable(code.estUtilisable())
                .consommeLe(code.getConsommeLe())
                .consommeIp(code.getConsommeIp())
                .remplace(code.getInvalideLe() != null)
                .build();
    }
}
