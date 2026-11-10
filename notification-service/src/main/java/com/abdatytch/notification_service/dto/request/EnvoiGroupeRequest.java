package com.abdatytch.notification_service.dto.request;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.commons.notification.DemandeDeNotification;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Un envoi declenche par un exploitant, vers PLUSIEURS personnes.
 *
 * <h2>Une liste d'identifiants, et non un critere</h2>
 *
 * Ce contrat ne porte pas « tous les comptes actifs » ni « les comptes du pays
 * X ». La selection se fait ailleurs — par l'administration de user-service,
 * qui detient les profils — et le resultat arrive ici sous forme de liste
 * explicite.
 *
 * <p>La raison est operationnelle : un critere evalue ICI ferait de ce service
 * un lecteur du profil des comptes, alors qu'il ne doit savoir que « ou joindre
 * cette personne ». Et un critere large est irrattrapable — on ne sait pas, en
 * relisant la requete, a combien de personnes elle est partie.
 *
 * <p>Une liste se relit, se compte avant d'envoyer, et se plafonne.
 *
 * @param destinataires identifiants des comptes ; les doublons sont retires
 * @param canaux        canaux a essayer, dans l'ordre
 * @param modele        clef de modele, qui doit exister pour chaque canal demande
 * @param variables     substitutions communes a tous les destinataires
 * @param urgence       decide du repli entre canaux, pas du consentement
 */
public record EnvoiGroupeRequest(
        @NotEmpty List<@NotBlank @Size(max = 100) String> destinataires,
        @NotEmpty List<CanalDeNotification> canaux,
        @NotBlank @Size(max = 200) String modele,
        Map<String, String> variables,
        DemandeDeNotification.Urgence urgence) {

    /**
     * Les destinataires, sans doublon et dans l'ordre recu.
     *
     * <p>Sans ce dedoublonnage, le meme compte recevrait deux fois le message
     * — ou, plus exactement, la seconde demande serait rejetee comme
     * « doublon » par l'orchestrateur et compterait comme un rejet dans le
     * compte rendu. Le rapport annoncerait alors des echecs la ou il n'y a
     * qu'une liste mal collee.
     *
     * <p>{@link LinkedHashSet} et non un tri : l'ordre de la liste fournie est
     * celui que l'exploitant relit.
     */
    public List<String> destinatairesUniques() {
        return List.copyOf(new LinkedHashSet<>(destinataires));
    }

    public Map<String, String> variablesOuVide() {
        return variables == null ? Map.of() : variables;
    }

    public DemandeDeNotification.Urgence urgenceOuNormale() {
        return urgence == null ? DemandeDeNotification.Urgence.NORMALE : urgence;
    }
}
