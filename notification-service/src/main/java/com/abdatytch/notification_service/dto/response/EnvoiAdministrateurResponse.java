package com.abdatytch.notification_service.dto.response;

import com.abdatytch.commons.notification.CompteRenduDeRemise;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qui est REELLEMENT arrive a un envoi declenche par un exploitant.
 *
 * <h2>Le compte rendu par canal, et pas seulement un « ok »</h2>
 *
 * Une reponse qui dirait seulement {@code 200} laisserait croire que le message
 * est parti. Il peut tres bien avoir ete REFUSE sur tous les canaux — pas de
 * consentement, pas d'adresse connue, canal desactive — sans qu'aucune erreur
 * ne se produise.
 *
 * <p>Ce depot a deja paye cette confusion : sept endpoints d'OTP annoncaient
 * « code envoye » alors que le canal n'avait rien tente. L'exploitant doit voir
 * ce que chaque canal a fait, et le motif quand il n'a rien fait.
 *
 * @param notificationId identifiant de la demande, pour retrouver la trace
 * @param remis          au moins un canal a abouti
 * @param parCanal       l'etat de chaque canal essaye, avec son motif
 */
public record EnvoiAdministrateurResponse(
        UUID notificationId,
        boolean remis,
        Map<String, String> parCanal) {

    /**
     * Construit la reponse depuis le compte rendu de l'orchestrateur.
     *
     * <p>{@code remis} est CALCULE depuis les etats, et non recopie d'un
     * drapeau : {@code CompteRenduDeRemise.rendu} dit que la demande a ete
     * traitee, ce qui n'est pas la meme chose que « quelqu'un l'a recue ».
     */
    public static EnvoiAdministrateurResponse de(CompteRenduDeRemise rendu) {
        List<CompteRenduDeRemise.RemiseParCanal> remises = rendu.remises();
        boolean aAbouti = remises.stream()
                .anyMatch(r -> r.etat() == CompteRenduDeRemise.Etat.REMIS);

        Map<String, String> detail = remises.stream().collect(
                java.util.stream.Collectors.toMap(
                        r -> r.canal().name(),
                        r -> r.motif() == null || r.motif().isBlank()
                                ? r.etat().name()
                                : r.etat().name() + " : " + r.motif(),
                        // Deux remises sur le MEME canal ne devraient pas
                        // arriver ; si cela arrive, garder les deux motifs
                        // plutot que de lever ou d'en perdre un en silence.
                        (a, b) -> a + " | " + b,
                        java.util.LinkedHashMap::new));

        return new EnvoiAdministrateurResponse(rendu.demandeId(), aAbouti, detail);
    }
}
