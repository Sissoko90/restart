package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.AppareilDeConfiance;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un appareil de confiance, tel que son propriétaire a besoin de le voir.
 *
 * <h2>Ce qui n'y figure PAS</h2>
 *
 * Le jeton, ni son empreinte. Le jeton dispense du second facteur : le rendre
 * dans une liste le ferait voyager dans une réponse HTTP, dans l'historique du
 * client et dans ses journaux — pour répondre à une question qui ne le
 * demandait pas.
 *
 * <p>L'empreinte non plus : elle ne sert à rien à l'appelant, et la publier
 * permettrait de vérifier qu'un jeton deviné correspond.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppareilDeConfianceResponse {

    /** Identifiant, suffisant pour révoquer. */
    private String id;

    private String nom;

    // Circonstances FIGÉES à l'instant de la confiance : c'est ce qui permet de
    // repérer une confiance accordée par quelqu'un d'autre. Les rafraîchir
    // effacerait l'indice.
    private String navigateur;
    private String systeme;
    private String typeAppareil;
    private String ipAccordee;
    private String paysAccorde;

    private LocalDateTime accordeLe;

    /** Dernier usage : distingue un appareil utilisé d'un appareil oublié. */
    private LocalDateTime utiliseLe;

    private LocalDateTime expireLe;

    /**
     * La confiance s'applique-t-elle encore ?
     *
     * <p>Calculé à la lecture : un appareil cesse d'être de confiance au passage
     * de son échéance, sans que personne écrive quoi que ce soit.
     */
    private Boolean actif;

    public static AppareilDeConfianceResponse de(AppareilDeConfiance appareil,
                                                 LocalDateTime maintenant) {
        return AppareilDeConfianceResponse.builder()
                .id(appareil.getId())
                .nom(appareil.getNom())
                .navigateur(appareil.getNavigateur())
                .systeme(appareil.getSysteme())
                .typeAppareil(appareil.getTypeAppareil())
                .ipAccordee(appareil.getIpAccordee())
                .paysAccorde(appareil.getPaysAccorde())
                .accordeLe(appareil.getAccordeLe())
                .utiliseLe(appareil.getUtiliseLe())
                .expireLe(appareil.getExpireLe())
                .actif(appareil.estDeConfiance(maintenant))
                .build();
    }
}
