package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.User;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Etat de verrouillage d'un compte.
 *
 * <h2>Deux defauts corriges par ce type</h2>
 *
 * <ol>
 *   <li><b>deux lectures pour une question.</b> Le controleur appelait
 *       {@code isAccountLocked} puis, si le compte n'etait pas verrouille,
 *       {@code getRemainingAttempts} — chacun lisant le compte en base. Deux
 *       allers-retours la ou l'entite porte les deux reponses ;</li>
 *   <li><b>une reponse de forme variable.</b> {@code remainingAttempts}
 *       n'etait present que pour un compte non verrouille. Un client devait donc
 *       traiter son absence, et un client ecrit en lisant une reponse de compte
 *       verrouille ne le voyait jamais.</li>
 * </ol>
 *
 * <p>Les noms de champs d'origine sont conserves ({@code identifier},
 * {@code isLocked}, {@code remainingAttempts}) : renommer n'aurait rien apporte
 * et aurait casse les appelants.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Etat de verrouillage d'un compte")
public class EtatVerrouillageResponse {

    @Schema(description = "Identifiant interroge, tel que fourni")
    private String identifier;

    /**
     * Nom JSON force a {@code isLocked}.
     *
     * <p>Sans {@code @JsonProperty}, Lombok genere {@code isLocked()} pour un
     * champ {@code boolean isLocked}, et Jackson en deduit le nom
     * <b>{@code locked}</b> : le champ etait silencieusement renomme, alors que
     * la raison d'etre de ce type est de conserver le contrat d'origine. Attrape
     * par {@code AdminAccountControllerTest} — « No value at JSON path
     * $.isLocked ».
     */
    @com.fasterxml.jackson.annotation.JsonProperty("isLocked")
    @Schema(description = "Vrai si un verrou court encore")
    private boolean isLocked;

    /**
     * Toujours present, y compris pour un compte verrouille.
     *
     * <p>Vaut le seuil complet pour un identifiant inconnu : l'endpoint ne doit
     * pas reveler qu'un compte n'existe pas.
     */
    @Schema(description = "Tentatives restantes avant verrouillage")
    private int remainingAttempts;

    /**
     * Echeance du verrou, nulle s'il n'y en a pas.
     *
     * <p>Information nouvelle : elle permet a une interface d'administration de
     * dire quand le compte se deverrouillera de lui-meme, au lieu de laisser
     * l'administrateur deverrouiller a l'aveugle.
     */
    @Schema(description = "Moment ou le verrou expire de lui-meme")
    private LocalDateTime lockedUntil;

    /**
     * Derive l'etat d'<b>une seule</b> lecture du compte.
     *
     * @param verrouille resultat de la regle de verrouillage, qui tient compte
     *        de l'echeance — un verrou echu ne verrouille plus
     */
    public static EtatVerrouillageResponse de(
            String identifier, User user, boolean verrouille, int tentativesRestantes) {
        return EtatVerrouillageResponse.builder()
                .identifier(identifier)
                .isLocked(verrouille)
                .remainingAttempts(tentativesRestantes)
                .lockedUntil(verrouille && user != null ? user.getLockedUntil() : null)
                .build();
    }
}
