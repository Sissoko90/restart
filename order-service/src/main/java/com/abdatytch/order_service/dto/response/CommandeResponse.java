package com.abdatytch.order_service.dto.response;

import com.abdatytch.order_service.enums.StatutCommande;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Une commande, rendue a qui a le droit de la voir.
 *
 * <h2>La commission n'est PAS rendue a l'acheteur</h2>
 *
 * {@code commissionMontant} et {@code montantDuALaBoutique} sont nuls pour un
 * acheteur, renseignes pour la boutique et pour l'administration. Ce que
 * Sankadi prend sur une vente est une affaire entre Sankadi et la boutique :
 * l'acheteur paie {@code montantArticles} et n'a pas a connaitre la
 * repartition.
 *
 * <p>C'est le service qui masque ces deux champs, pas le client. Compter sur le
 * client pour ne pas afficher un champ qu'on lui envoie n'est pas une
 * protection.
 */
public record CommandeResponse(
        UUID id,
        String numero,
        StatutCommande statut,
        UUID boutiqueId,
        String pays,
        long montantArticles,
        Long commissionMontant,
        Long montantDuALaBoutique,
        String devise,
        LocalDateTime aPayerJusquAu,
        LocalDateTime payeeLe,
        LocalDateTime expedieeLe,
        LocalDateTime livreeLe,
        LocalDateTime retourPossibleJusquAu,
        LocalDateTime clotureeLe,
        String motifAnnulation,
        LocalDateTime creeeLe,
        List<LigneCommandeResponse> lignes) {
}
