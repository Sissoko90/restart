package com.abdatytch.auth_service.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reponse portant une simple confirmation.
 *
 * <h2>Pourquoi ce type existe</h2>
 *
 * Les controleurs construisaient des {@code Map.of("message", ...)} au cas par
 * cas. Trois consequences, aucune esthetique :
 *
 * <ul>
 *   <li>la documentation OpenAPI annonçait un objet libre, donc le contrat
 *       n'etait decrit nulle part ;</li>
 *   <li>la cle differait d'un endpoint a l'autre — {@code message}, parfois
 *       accompagnee de {@code identifier} ou {@code duration} — sans qu'aucun
 *       type ne le rappelle ;</li>
 *   <li>la construction de la reponse etait de la logique dans le controleur.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Confirmation d'une operation")
public class MessageResponse {

    @Schema(description = "Message destine a l'appelant")
    private String message;

    /**
     * Drapeau de succes, present uniquement quand le contrat d'origine le
     * portait.
     *
     * <p>{@code @JsonInclude(NON_NULL)} : les reponses qui ne rendaient que
     * {@code message} continuent de ne rendre que {@code message}. Ajouter un
     * champ partout serait additif, donc sans casse — mais un
     * {@code "success": false} apparaissant sur une reponse 200 serait trompeur.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    @Schema(description = "Vrai si l'operation a abouti")
    private Boolean success;

    public static MessageResponse de(String message) {
        return MessageResponse.builder().message(message).build();
    }

    /** Conserve le couple {@code message} + {@code success} du contrat d'origine. */
    public static MessageResponse reussie(String message) {
        return MessageResponse.builder().message(message).success(true).build();
    }
}
