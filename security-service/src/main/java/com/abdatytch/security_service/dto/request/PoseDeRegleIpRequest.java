package com.abdatytch.security_service.dto.request;

import com.abdatytch.security_service.constant.SecurityConstants;
import com.abdatytch.security_service.enums.MotifDeRegleIp;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Pose d'une regle de blocage sur une adresse.
 *
 * <h2>Ce que remplace cette surface</h2>
 *
 * {@code IpBlacklistWebFilter} ecrivait directement dans Redis : une adresse,
 * une duree de vie d'un jour figee dans le code, et rien d'autre. Ni motif, ni
 * auteur, ni moyen de consulter la liste, ni moyen de lever un blocage pose par
 * erreur.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Adresse a bloquer, avec le motif et l'echeance de la decision")
public class PoseDeRegleIpRequest {

    /**
     * Adresse IP litterale, IPv4 ou IPv6.
     *
     * <p>Un <b>prefixe CIDR est refuse</b>, et ce n'est pas une limitation
     * arbitraire : l'applicateur repond par une correspondance de cle exacte
     * dans Redis. Une regle de prefixe serait enregistree, publiee, et ne
     * correspondrait a aucune requete. Voir
     * {@link SecurityConstants#CIDR_NON_APPLICABLE}.
     *
     * <p>La forme est libre : {@code 041.223.010.005} et {@code 41.223.10.5}
     * designent la meme machine et sont normalisees vers la meme cle.
     */
    @NotBlank(message = SecurityConstants.ADRESSE_REQUISE)
    @Size(max = 64)
    @Schema(description = "Adresse IPv4 ou IPv6 litterale. Pas de prefixe CIDR",
            example = "203.0.113.7", requiredMode = Schema.RequiredMode.REQUIRED)
    private String adresse;

    @NotNull(message = SecurityConstants.MOTIF_REQUIS)
    @Schema(description = "Pourquoi cette adresse est bloquee",
            example = "DECISION_ADMINISTRATIVE", requiredMode = Schema.RequiredMode.REQUIRED)
    private MotifDeRegleIp motif;

    /**
     * Precision libre, facultative.
     *
     * <p>Complete le motif enumere sans le remplacer : le motif sert aux
     * metriques et aux decisions, ce champ sert a la personne qui lira la regle
     * dans six mois.
     */
    @Size(max = 500)
    @Schema(description = "Precision pour qui relira la regle plus tard",
            example = "Signale par l'hebergeur, dossier AB-1294")
    private String commentaire;

    /**
     * Echeance, ou absente pour une regle permanente.
     *
     * <p>Une regle permanente n'est acceptee que pour un motif qui n'est pas une
     * detection automatique : voir {@link #isEcheanceCoherenteAvecLeMotif()}.
     */
    @Schema(description = "Fin du blocage. Absente = permanent, "
            + "ce qui n'est admis que pour une decision humaine")
    private Instant expireLe;

    /**
     * Une detection automatique doit porter une echeance.
     *
     * <p>Une detection peut se tromper : derriere le NAT d'un operateur, une
     * seule personne malveillante fait bloquer tout un quartier. Un faux positif
     * permanent enferme ces adresses pour toujours, et les victimes ne peuvent
     * meme plus se connecter pour le signaler.
     *
     * <p>Une decision humaine, elle, peut etre permanente : quelqu'un a regarde
     * et a tranche. C'est la distinction que porte
     * {@link MotifDeRegleIp#estUneDetectionAutomatique()}, et c'est la seule
     * raison pour laquelle ce drapeau existe sur l'enumeration.
     */
    @AssertTrue(message = SecurityConstants.PERMANENT_INTERDIT_POUR_DETECTION)
    public boolean isEcheanceCoherenteAvecLeMotif() {
        if (motif == null) {
            // Le motif absent est deja refuse par @NotNull. Rendre faux ici
            // ajouterait un second message sur la meme faute.
            return true;
        }
        return expireLe != null || !motif.estUneDetectionAutomatique();
    }
}
