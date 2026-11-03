package com.abdatytch.auth_service.dto.response;

import com.abdatytch.auth_service.entities.OAuthAccount;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Un compte social lie, tel qu'on peut le montrer a son proprietaire.
 *
 * <h2>Ce qui n'y figure PAS</h2>
 *
 * Les jetons du fournisseur. {@code oauth_accounts} porte un
 * {@code access_token} et un {@code refresh_token} qui sont des justificatifs
 * valables <b>chez Google ou chez Apple</b> : les rendre ici les ferait voyager
 * dans une reponse HTTP, dans l'historique du client et dans ses journaux, pour
 * repondre a une question qui ne les demandait pas.
 *
 * <p>Le {@code providerUserId} non plus. C'est l'identifiant stable du compte
 * chez le fournisseur, et il ne sert a rien a l'appelant : le fournisseur
 * suffit a designer la liaison a delier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CompteSocialResponse {

    /** Nom du fournisseur, tel qu'il est attendu dans les URL : google, apple... */
    private String provider;

    /**
     * Adresse connue chez le fournisseur.
     *
     * <p>Pour Apple, c'est souvent une adresse en
     * {@code @privaterelay.appleid.com} : la personne a choisi « Masquer mon
     * adresse ». La montrer telle quelle est correct — c'est bien l'adresse par
     * laquelle elle est joignable.
     */
    private String email;

    private String name;

    private String pictureUrl;

    private LocalDateTime lieLe;

    public static CompteSocialResponse de(OAuthAccount compte) {
        return CompteSocialResponse.builder()
                .provider(compte.getProvider() != null
                        ? compte.getProvider().getProviderName()
                        : null)
                .email(compte.getEmail())
                .name(compte.getName())
                .pictureUrl(compte.getPictureUrl())
                .lieLe(compte.getCreatedAt())
                .build();
    }
}
