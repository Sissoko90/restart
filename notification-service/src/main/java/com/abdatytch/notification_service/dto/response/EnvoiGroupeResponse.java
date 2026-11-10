package com.abdatytch.notification_service.dto.response;

import java.util.List;

/**
 * Le rapport d'un envoi de groupe.
 *
 * <h2>Les comptes sont DERIVES du detail, jamais tenus a part</h2>
 *
 * {@code demandes}, {@code remis} et {@code sansRemise} sont calcules depuis la
 * liste {@code parDestinataire} par {@link #de(List)}. Un compteur incremente
 * au fil de la boucle, a cote du detail, diverge du detail des la premiere
 * branche oubliee — et c'est le compteur qu'on lit.
 *
 * @param demandes        nombre de destinataires traites, apres dedoublonnage
 * @param remis           destinataires pour lesquels au moins un canal a abouti
 * @param sansRemise      destinataires que rien n'a atteint
 * @param parDestinataire le detail, dans l'ordre de la liste fournie
 */
public record EnvoiGroupeResponse(
        int demandes,
        int remis,
        int sansRemise,
        List<Ligne> parDestinataire) {

    /**
     * Une ligne du rapport.
     *
     * @param destinataireId l'identifiant fourni ; aucune coordonnee n'est
     *                       rendue, une adresse ou un numero etant une donnee
     *                       personnelle qui n'a rien a faire dans un rapport
     *                       d'exploitation
     * @param remis          au moins un canal a abouti
     * @param detail         l'etat par canal, ou le motif du refus global
     */
    public record Ligne(String destinataireId, boolean remis, String detail) {
    }

    public static EnvoiGroupeResponse de(List<Ligne> lignes) {
        int aboutis = (int) lignes.stream().filter(Ligne::remis).count();
        return new EnvoiGroupeResponse(
                lignes.size(), aboutis, lignes.size() - aboutis, lignes);
    }
}
