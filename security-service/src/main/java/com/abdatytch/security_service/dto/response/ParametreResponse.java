package com.abdatytch.security_service.dto.response;

import com.abdatytch.security_service.enums.ParametreDeSecurite;
import com.abdatytch.security_service.enums.TypeDeParametre;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Un parametre de securite, tel qu'un administrateur a besoin de le voir.
 *
 * <h2>Pourquoi le defaut figure a cote de la valeur</h2>
 *
 * Sans lui, l'administrateur ne sait pas s'il regarde une decision ou un etat
 * d'origine. « Seuil : 5 » ne dit pas si quelqu'un l'a choisi. Avec les deux, la
 * revue de securite distingue ce qui a ete decide de ce qui n'a jamais ete
 * regarde — et {@link #surcharge} le dit explicitement.
 *
 * <h2>Pourquoi les bornes sont rendues</h2>
 *
 * L'interface d'administration peut alors refuser une valeur avant l'appel, et
 * surtout <b>expliquer</b> la borne. Un refus « valeur hors bornes » sans dire
 * lesquelles oblige a deviner.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Parametre de la politique de securite, avec son defaut et ses bornes")
public class ParametreResponse {

    @Schema(description = "Cle du catalogue", example = "security.max-login-attempts")
    private String cle;

    @Schema(description = "Valeur effective : la surcharge si elle existe, sinon le defaut",
            example = "5")
    private String valeur;

    @Schema(description = "Valeur d'origine, declaree dans le catalogue", example = "5")
    private String valeurParDefaut;

    @Schema(description = "Vrai si la valeur a ete decidee et ne vient pas du defaut")
    private Boolean surcharge;

    @Schema(description = "Nature de la valeur, et donc sa validation")
    private TypeDeParametre type;

    @Schema(description = "Public concerne : UTILISATEUR, ADMINISTRATION ou GLOBALE")
    private ParametreDeSecurite.Portee portee;

    @Schema(description = "Borne basse incluse, si le type en porte une", example = "3")
    private Integer minimum;

    @Schema(description = "Borne haute incluse, si le type en porte une", example = "20")
    private Integer maximum;

    @Schema(description = "Qui a decide cette valeur. Absent si la valeur vient du defaut")
    private String modifiePar;

    @Schema(description = "Quand. Absent si la valeur vient du defaut")
    private Instant modifieLe;

    /** Vue d'un parametre laisse a son defaut : aucun auteur, aucune date. */
    public static ParametreResponse auDefaut(ParametreDeSecurite parametre) {
        return base(parametre)
                .valeur(parametre.getValeurParDefaut())
                .surcharge(false)
                .build();
    }

    /** Vue d'un parametre decide. */
    public static ParametreResponse surcharge(ParametreDeSecurite parametre, String valeur,
                                              String modifiePar, Instant modifieLe) {
        return base(parametre)
                .valeur(valeur)
                .surcharge(true)
                .modifiePar(modifiePar)
                .modifieLe(modifieLe)
                .build();
    }

    private static ParametreResponseBuilder base(ParametreDeSecurite parametre) {
        return ParametreResponse.builder()
                .cle(parametre.getCle())
                .valeurParDefaut(parametre.getValeurParDefaut())
                .type(parametre.getType())
                .portee(parametre.getPortee())
                .minimum(parametre.getMinimum())
                .maximum(parametre.getMaximum());
    }
}
