package com.abdatytch.auth_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Critère de recherche des journaux d'authentification par adresse IP.
 *
 * <h2>Une adresse IP est une donnée personnelle</h2>
 *
 * Ce n'est pas une interprétation extensive : {@code AccountErasureService}
 * efface explicitement {@code ipAddress} lors d'un effacement RGPD, au même
 * titre que le numéro de téléphone et l'agent utilisateur — « ce sont des
 * données personnelles à part entière », dit sa javadoc.
 *
 * <p>Mettre cette adresse dans le chemin de l'URL
 * ({@code GET /by-ip/{ipAddress}}) la versait donc dans les journaux d'accès du
 * Gateway, des proxys et des outils de supervision : exactement les copies que
 * l'effacement ne sait pas atteindre. Le service passait son temps à effacer
 * d'un côté ce qu'il publiait de l'autre.
 *
 * <p>Le critère passe par le corps, d'où un {@code POST}. Même raisonnement que
 * pour {@link AuditPhoneSearchRequest}.
 */
@Data
public class AuditIpSearchRequest {

    /**
     * L'adresse recherchée, v4 ou v6.
     *
     * <p>Aucun motif n'est imposé : le champ est comparé par égalité à une
     * valeur déjà enregistrée, et une adresse mal formée ne rend simplement
     * aucun résultat. Imposer un motif ici ferait surtout échouer des
     * recherches légitimes sur des formes v6 compressées.
     */
    @NotBlank(message = "L'adresse IP est obligatoire")
    private String ipAddress;
}
