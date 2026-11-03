package com.abdatytch.auth_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Jeton d'appareil de confiance, remis <b>une seule fois</b>.
 *
 * <h2>Pourquoi le message est aussi explicite</h2>
 *
 * Le jeton n'est stocké que haché : il ne pourra plus être relu, par personne,
 * y compris par un administrateur. Un client qui ne le conserve pas à cet
 * instant devra repasser le second facteur et accorder une nouvelle confiance —
 * et la précédente restera dans sa liste jusqu'à son échéance, sans qu'il sache
 * à quoi elle correspond.
 *
 * <p>C'est la même précaution que {@code TotpEnrolementResponse}, qui annonce
 * « ce secret ne sera plus affiché ».
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfianceAccordeeResponse {

    /** Identifiant de l'appareil, suffisant pour le révoquer. */
    private String id;

    /**
     * Le jeton, en clair, pour la seule et unique fois.
     *
     * <p>À présenter dans l'en-tête {@code X-Device-Token} aux connexions
     * suivantes.
     */
    private String jeton;

    private String nom;

    private LocalDateTime expireLe;

    private String message;
}
