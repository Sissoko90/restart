package com.abdatytch.auth_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * L'état des codes de secours d'un compte, en une seule réponse.
 *
 * <h2>Pourquoi le compte et la liste voyagent ensemble</h2>
 *
 * Un second endpoint qui ne rendrait que le nombre restant serait la même
 * information, déduite deux fois, par deux chemins à maintenir d'accord. Le
 * nombre est ici déjà dans la réponse qui porte la liste.
 *
 * <h2>Ce qui n'y figure jamais</h2>
 *
 * Aucun code, aucune empreinte. Seuls les quatre premiers caractères de chacun,
 * pour que la personne reconnaisse ses codes sur son papier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Etat des codes de secours. Les codes eux-memes ne sont "
        + "jamais rendus : ils sont haches en base.")
public class EtatDesCodesResponse {

    @Schema(description = "Nombre de codes encore utilisables", example = "7")
    private long restants;

    /**
     * Total des codes connus du compte, lots invalidés compris.
     *
     * <p>Utile pour distinguer « il m'en reste 7 sur 10 » de « il m'en reste 7
     * sur 30, j'ai régénéré deux fois ».
     */
    @Schema(description = "Total connu, lots invalides compris", example = "10")
    private long total;

    /**
     * Avertissement quand il ne reste rien.
     *
     * <p>Absent le reste du temps : un message permanent se lit comme un
     * ornement, et cesse d'être lu au moment où il compte.
     */
    @Schema(description = "Avertissement quand aucun code n'est utilisable")
    private String message;

    @Schema(description = "Les codes, utilisables ou non, sans leur valeur")
    private List<CodeDeSecoursResponse> codes;
}
