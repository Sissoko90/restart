package com.abdatytch.market_service.service;

import com.abdatytch.market_service.dto.request.CaracteristiqueRequest;
import com.abdatytch.market_service.dto.request.CreationProduitRequest;
import com.abdatytch.market_service.dto.request.PhotoRequest;
import com.abdatytch.market_service.dto.request.VarianteRequest;
import com.abdatytch.market_service.dto.response.PageResponse;
import com.abdatytch.market_service.dto.response.ProduitResponse;
import com.abdatytch.market_service.security.PorteeDuCatalogue;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Le catalogue, et la gestion des articles par leur vendeur. */
public interface IProduitService {

    /** Le catalogue, filtre par la portee du demandeur. */
    Mono<PageResponse<ProduitResponse>> catalogue(PorteeDuCatalogue portee, UUID categorieId,
                                                  String recherche, int page, int taille);

    /** Une fiche, si la portee la permet. Sinon introuvable. */
    Mono<ProduitResponse> fiche(PorteeDuCatalogue portee, UUID produitId);

    /** La vitrine du vendeur : ses articles, quel que soit leur etat. */
    Mono<PageResponse<ProduitResponse>> mesProduits(String authUserId, int page, int taille);

    Mono<ProduitResponse> creer(String authUserId, CreationProduitRequest demande);

    /** Modifier RENVOIE l'article en moderation. */
    Mono<ProduitResponse> modifier(String authUserId, UUID produitId,
                                   CreationProduitRequest demande);

    Mono<Void> remplacerLesPhotos(String authUserId, UUID produitId, List<PhotoRequest> photos);

    Mono<Void> remplacerLesVariantes(String authUserId, UUID produitId,
                                     List<VarianteRequest> variantes);

    Mono<Void> remplacerLesCaracteristiques(String authUserId, UUID produitId,
                                            List<CaracteristiqueRequest> caracteristiques);

    /** Retire ou remet l'article en vitrine, sans le supprimer. */
    Mono<Void> changerLActivation(String authUserId, UUID produitId, boolean actif);

    Mono<Void> supprimer(String authUserId, UUID produitId);
}
