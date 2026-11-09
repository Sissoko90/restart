package com.abdatytch.notification_service.dto.request;

import com.abdatytch.notification_service.constants.PlateformeDAppareil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un appareil qui se declare joignable par push.
 *
 * <h2>Le destinataire n'est PAS dans ce corps, et ne doit jamais l'etre</h2>
 *
 * Il vient du JETON d'authentification. Accepter un identifiant de
 * destinataire ici permettrait d'enregistrer son propre appareil au nom de
 * quelqu'un d'autre — donc de recevoir ses codes de verification et ses
 * alertes de securite. C'est la regle constante de ce depot, et la raison pour
 * laquelle aucun endpoint de la boite de reception ne prend d'identifiant.
 *
 * @param jeton      le jeton d'enregistrement rendu par le SDK FCM sur
 *                   l'appareil. Borne a 4096 caracteres : la longueur reelle
 *                   tourne autour de 160, et Google ne la specifie pas — la
 *                   borne protege la base d'un corps de requete abusif, sans
 *                   risquer de refuser un jeton legitime
 * @param plateforme {@code ANDROID}, {@code IOS} ou {@code WEB}
 */
public record EnregistrementDAppareilRequest(
        @NotBlank @Size(max = 4096) String jeton,
        @NotNull PlateformeDAppareil plateforme) {
}
