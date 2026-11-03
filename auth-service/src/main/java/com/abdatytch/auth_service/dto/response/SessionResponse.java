package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.RefreshToken;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Une session ouverte, telle qu'on peut la montrer a son proprietaire.
 *
 * <h2>Ce qui n'y figure PAS, et pourquoi</h2>
 *
 * Le jeton de rafraichissement lui-meme. C'est un justificatif complet : qui
 * l'obtient renouvelle l'acces au compte jusqu'a son echeance. Le rendre dans
 * une liste de sessions le ferait voyager dans une reponse HTTP, dans
 * l'historique du client et dans ses journaux — alors que la personne demandait
 * seulement a voir ou elle est connectee.
 *
 * <p>L'identifiant expose est celui de la ligne, pas le jeton. Il suffit a
 * fermer la session et ne permet pas de s'en servir.
 *
 * <h2>L'adresse IP est conservee, et c'est un choix</h2>
 *
 * C'est une donnee personnelle, et c'est aussi le seul element qui permette a
 * quelqu'un de reconnaitre une session qui n'est pas la sienne. Sans elle, une
 * liste de sessions n'aide pas a reperer l'intrus qu'elle sert justement a
 * reperer. Elle n'est montree qu'au proprietaire du compte.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionResponse {

    /** Identifiant de la session, suffisant pour la fermer. */
    private String id;

    private String ipAddress;

    private String userAgent;

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    /**
     * Vrai si cette session est celle qui a servi a faire la demande.
     *
     * <p>Sans ce marqueur, la personne ne sait pas laquelle des lignes est la
     * sienne et risque de fermer sa propre session en croyant fermer celle d'un
     * intrus — puis de recommencer, sans jamais fermer la bonne.
     */
    private Boolean courante;

    public static SessionResponse de(RefreshToken session, boolean estCourante) {
        return SessionResponse.builder()
                .id(session.getId())
                .ipAddress(session.getIpAddress())
                .userAgent(session.getUserAgent())
                .createdAt(session.getCreatedAt())
                .expiresAt(session.getExpiresAt())
                .courante(estCourante)
                .build();
    }
}
