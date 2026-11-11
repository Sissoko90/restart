package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.request.DecisionRequest;
import com.abdatytch.market_service.dto.response.AvisResponse;
import com.abdatytch.market_service.dto.response.BoutiqueResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * La moderation : rien n'est publie sans decision.
 *
 * <p>Trois files, un seul mecanisme. Les boutiques parce qu'un dossier
 * administratif se verifie, les produits parce qu'ils portent un prix et des
 * photos sous le nom de la plateforme, les avis parce qu'ils sont du texte
 * libre publie sous le nom d'une boutique qui ne l'a pas ecrit.
 */
public interface IModerationService {

    Flux<BoutiqueResponse> boutiquesAModerer(int page, int taille);

    Mono<BoutiqueResponse> deciderBoutique(String moderateurId, UUID boutiqueId,
                                           DecisionRequest decision);

    Flux<ProduitResponse> produitsAModerer(int page, int taille);

    Mono<ProduitResponse> deciderProduit(String moderateurId, UUID produitId,
                                         DecisionRequest decision);

    Flux<AvisResponse> avisAModerer(int page, int taille);

    Mono<AvisResponse> deciderAvis(String moderateurId, UUID avisId,
                                   DecisionRequest decision);
}
