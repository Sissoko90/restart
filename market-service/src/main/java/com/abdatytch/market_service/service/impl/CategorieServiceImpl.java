package com.abdatytch.market_service.service.impl;

import com.abdatytch.market_service.dto.response.CategorieResponse;
import com.abdatytch.market_service.entities.Categorie;
import com.abdatytch.market_service.exception.MarketException;
import com.abdatytch.market_service.exception.RefusExplique;
import com.abdatytch.market_service.mapper.MarketMapper;
import com.abdatytch.market_service.repository.CategorieRepository;
import com.abdatytch.market_service.service.ICategorieService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/** L'arbre du catalogue. */
@Service
public class CategorieServiceImpl implements ICategorieService {

    private final CategorieRepository categories;

    public CategorieServiceImpl(CategorieRepository categories) {
        this.categories = categories;
    }

    @Override
    public Flux<CategorieResponse> enfantsDe(UUID parentId) {
        return categories.enfantsDe(parentId).map(MarketMapper::versReponse);
    }

    @Override
    public Mono<CategorieResponse> creer(String nom, UUID parentId, Integer position) {
        Categorie categorie = new Categorie();
        categorie.setNom(nom);
        categorie.setSlug(slug(nom));
        categorie.setParentId(parentId);
        categorie.setPosition(position == null ? 0 : position);
        categorie.setActif(Boolean.TRUE);
        return categories.save(categorie)
                .onErrorMap(DataIntegrityViolationException.class, collision ->
                        RefusExplique.etatIncompatible(
                                "Une categorie porte deja l'adresse « " + slug(nom)
                                        + " ». Le slug est unique sur tout l'arbre, "
                                        + "parce qu'une URL publique ne porte pas le "
                                        + "chemin de ses parents."))
                .map(MarketMapper::versReponse);
    }

    @Override
    public Mono<CategorieResponse> renommer(UUID id, String nom, Integer position,
                                            Boolean actif) {
        return categories.findById(id)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(categorie -> {
                    if (nom != null && !nom.isBlank()) {
                        categorie.setNom(nom);
                        // Le SLUG ne change PAS avec le nom. Une URL publiee,
                        // indexee et partagee ne doit pas mourir parce qu'on a
                        // corrige une faute de frappe dans un libelle.
                    }
                    if (position != null) {
                        categorie.setPosition(position);
                    }
                    if (actif != null) {
                        categorie.setActif(actif);
                    }
                    return categories.save(categorie);
                })
                .map(MarketMapper::versReponse);
    }

    @Override
    public Mono<Void> supprimer(UUID id) {
        return categories.findById(id)
                .switchIfEmpty(Mono.error(MarketException.introuvable()))
                .flatMap(categories::delete)
                .onErrorMap(DataIntegrityViolationException.class, rattachee ->
                        RefusExplique.etatIncompatible(
                                "Cette categorie porte encore des sous-categories ou des "
                                        + "articles. Deplacez-les d'abord : la supprimer "
                                        + "en cascade retirerait du catalogue des articles "
                                        + "que personne n'a decide de retirer."));
    }

    /**
     * Le slug d'un nom.
     *
     * <p>Accents retires par decomposition Unicode plutot que par une table de
     * correspondance : une table oublie toujours une langue, et ce marche sert
     * plusieurs pays.
     */
    static String slug(String nom) {
        String sansAccent = Normalizer.normalize(nom, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        String propre = sansAccent.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return propre.isEmpty() ? "categorie" : propre;
    }
}
