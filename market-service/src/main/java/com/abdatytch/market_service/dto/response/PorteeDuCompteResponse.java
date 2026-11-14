package com.abdatytch.market_service.dto.response;

import java.util.UUID;

/**
 * Ce que market-service sait d'un compte, pour un autre service.
 *
 * <h2>Pourquoi cet endpoint existe</h2>
 *
 * order-service doit pouvoir servir une boutique : « les commandes recues par
 * MA boutique ». Il ne sait pas quelle boutique est la sienne — cette
 * information vit ici, avec le dossier de validation.
 *
 * <h2>Ce qu'il NE sert pas</h2>
 *
 * Il ne sert pas a reserver du stock. Une reservation ne transmet qu'un
 * identifiant de compte, et market-service resout la portee lui-meme : laisser
 * un appelant lire la portee puis l'annoncer ailleurs rouvrirait la porte qu'on
 * vient de fermer.
 *
 * @param boutiqueId     nul si le compte n'a pas de boutique, ce qui est le
 *                       cas le plus courant
 * @param professionnelle vrai seulement si la boutique est APPROUVEE. Un
 *                        dossier en attente n'est pas une boutique
 *                        professionnelle, et c'est la regle que market-service
 *                        detient
 */
public record PorteeDuCompteResponse(
        UUID boutiqueId,
        boolean professionnelle,
        String pays) {
}
