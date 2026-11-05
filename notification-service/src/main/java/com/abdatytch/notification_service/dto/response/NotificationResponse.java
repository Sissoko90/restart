package com.abdatytch.notification_service.dto.response;

import com.abdatytch.notification_service.entities.Notification;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une notification telle que le client la lit.
 *
 * <h2>Ce que ce DTO ne contient PAS, et pourquoi</h2>
 *
 * Pas de {@code destinataireId}. Le client n'apprend rien en se faisant dire son
 * propre identifiant, et le laisser sortir serait une donnee personnelle de plus
 * dans chaque reponse et dans chaque journal d'acces.
 *
 * <p>Pas de {@code serviceEmetteur} ni de {@code demandeId}. Ce sont des details
 * d'architecture interne : les publier renseignerait un appelant sur le
 * decoupage des services et sur les identifiants qui y circulent. Ils restent en
 * base, pour l'audit.
 *
 * <p>Les variables non rendues ne sortent pas non plus : le client recoit le
 * texte, pas le gabarit et ses parametres.
 */
public record NotificationResponse(
        UUID id,
        String modele,
        String sujet,
        String corps,
        String urgence,
        boolean lue,
        LocalDateTime creeeLe
) {

    /**
     * Construit la reponse a partir de l'entite et du contenu rendu.
     *
     * <p>{@code sujet} et {@code corps} viennent du moteur de modeles : la base
     * conserve le gabarit et ses variables, pas le texte, pour que la langue et
     * la mise en page ne soient pas figees au jour de l'envoi.
     */
    public static NotificationResponse de(Notification notification, String sujet, String corps) {
        return new NotificationResponse(
                notification.getId(),
                notification.getModele(),
                sujet,
                corps,
                notification.getUrgence(),
                notification.getLueLe() != null,
                notification.getCreeeLe());
    }
}
