package com.abdatytch.market_service.web;

import com.abdatytch.market_service.constants.CheminsDuMarche;
import com.abdatytch.market_service.dto.response.CategorieResponse;
import com.abdatytch.market_service.service.ICategorieService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * L'arbre du catalogue, en lecture.
 *
 * <p>Niveau par niveau et non l'arbre entier : un catalogue complet se mesure
 * en milliers de noeuds, et l'ecran n'en montre qu'un etage a la fois.
 */
@RestController
@RequestMapping(CheminsDuMarche.CATEGORIES)
@Tag(name = "Categories", description = "L'arbre du catalogue")
public class CategorieController {

    private final ICategorieService categories;

    public CategorieController(ICategorieService categories) {
        this.categories = categories;
    }

    @GetMapping
    @Operation(summary = "Les categories d'un niveau",
            description = "Sans parametre : les racines.")
    public Flux<CategorieResponse> enfants(@RequestParam(required = false) UUID parent) {
        return categories.enfantsDe(parent);
    }
}
