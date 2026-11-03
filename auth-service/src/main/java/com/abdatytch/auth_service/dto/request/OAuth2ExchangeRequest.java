package com.abdatytch.auth_service.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Echange du code de remise contre les jetons.
 *
 * <h2>Pourquoi un corps et non un parametre de requete</h2>
 *
 * C'est tout l'objet du changement. Le code remplace des jetons qui voyageaient
 * dans la chaine de requete d'une redirection ; le passer a son tour dans une
 * chaine de requete le mettrait dans l'historique, dans les journaux d'acces et
 * dans le {@code Referer} — et le code vaut les jetons qu'il designe, le temps
 * de sa validite.
 *
 * <p>Un corps de requete ne va dans aucun des trois.
 *
 * <h2>A ne pas confondre avec {@link OAuth2CallbackRequest}</h2>
 *
 * Celui-la porte le code d'autorisation emis par <b>le fournisseur</b> — Google,
 * Facebook, Apple — a echanger chez lui. Celui-ci porte un code emis par
 * <b>nous</b>, a echanger chez nous. Deux codes, deux emetteurs, deux
 * destinataires : les reunir dans un seul DTO obligerait a un champ nul sur deux
 * chemins differents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Code de remise a usage unique, recu dans la redirection")
public class OAuth2ExchangeRequest {

    /**
     * Le code, tel qu'il figurait dans la redirection.
     *
     * <p>La borne de taille n'est pas decorative : sans elle, un corps de
     * plusieurs megaoctets serait lu, puis concatene en cle Redis. 64 caracteres
     * suffisent — le code fait 43 caracteres, soit 32 octets en Base64 URL sans
     * remplissage.
     */
    @NotBlank
    @Size(max = 64)
    @Schema(description = "Code a usage unique, valable quelques secondes",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
}
