package com.abdatytch.notification_service.dto.request;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Un envoi declenche par un exploitant, vers UNE personne.
 *
 * <h2>Pourquoi le modele est obligatoire et le texte absent</h2>
 *
 * Ce contrat ne porte <b>aucun sujet ni corps libre</b>, et c'est la decision
 * centrale.
 *
 * <p>Accepter du texte brut ici creerait un second chemin par lequel un
 * message part — a cote du moteur de modeles — avec ses propres consequences :
 * pas de variante par canal (un SMS n'est pas un courriel), pas de traduction
 * possible, pas de relecture, et aucun moyen de corriger a posteriori une
 * formulation deja envoyee a mille personnes. Ce depot vient de supprimer un
 * second moteur de modeles pour exactement cette raison.
 *
 * <p>Un texte nouveau se declare donc dans
 * {@code modeles-notification.properties}, ou se pose a chaud par
 * {@code PUT /admin/modeles}. Les variables, elles, passent ici.
 *
 * @param destinataireId identifiant du compte, tel que le connait auth-service
 * @param canaux         canaux a essayer, dans l'ordre
 * @param modele         clef de modele, qui doit exister pour chaque canal demande
 * @param variables      substitutions du gabarit ; jamais de donnee sensible
 * @param urgence        decide du repli entre canaux, pas du consentement
 */
public record EnvoiAdministrateurRequest(
        @NotBlank @Size(max = 100) String destinataireId,
        @NotEmpty List<CanalDeNotification> canaux,
        @NotBlank @Size(max = 200) String modele,
        Map<String, String> variables,
        DemandeDeNotification.Urgence urgence) {

    /**
     * Les variables, jamais {@code null}.
     *
     * <p>{@code substituer} du moteur parcourt cette carte : un {@code null}
     * ferait une {@code NullPointerException} au lieu d'un gabarit sans
     * substitution.
     */
    public Map<String, String> variablesOuVide() {
        return variables == null ? Map.of() : variables;
    }

    /**
     * L'urgence, {@code NORMALE} a defaut.
     *
     * <p>Le repli sans surprise : {@code INFORMATION} n'essaierait aucun autre
     * canal, et {@code CRITIQUE} les essaierait tous. Une urgence absente ne
     * doit decider ni de l'un ni de l'autre.
     */
    public DemandeDeNotification.Urgence urgenceOuNormale() {
        return urgence == null ? DemandeDeNotification.Urgence.NORMALE : urgence;
    }
}
