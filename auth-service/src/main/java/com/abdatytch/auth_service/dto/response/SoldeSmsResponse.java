package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.service.sms.IAdministrationSms.SoldeParPays;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Solde SMS par pays, et le verdict qui va avec.
 *
 * <h2>Pourquoi le verdict est ici et pas dans le controleur</h2>
 *
 * {@code AdminSmsController} assemblait un {@code Map.of("soldes", ...,
 * "critique", auMoinsUnSoldeCritique(soldes))} et portait la methode privee qui
 * calculait le verdict. Deux problemes : le contrat de la reponse n'etait decrit
 * nulle part — la documentation OpenAPI annonçait un objet libre — et la regle
 * « au moins un pays a court » vivait dans un controleur, hors de portee de tout
 * autre appelant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Solde SMS par pays")
public class SoldeSmsResponse {

    @Schema(description = "Solde restant pour chaque pays du compte")
    private List<SoldeParPays> soldes;

    /**
     * Vrai des qu'<b>un seul</b> pays est a court.
     *
     * <p>Un credit epuise ne produit aucune erreur HTTP chez le fournisseur :
     * les envois repondent 200 avec un code de refus. Ce drapeau est le seul
     * signal lisible avant que les utilisateurs ne cessent de recevoir leur
     * code.
     */
    @Schema(description = "Vrai si au moins un pays est a court de credit")
    private boolean critique;

    public static SoldeSmsResponse de(List<SoldeParPays> soldes) {
        return SoldeSmsResponse.builder()
                .soldes(soldes)
                .critique(soldes.stream().anyMatch(SoldeParPays::estCritique))
                .build();
    }
}
