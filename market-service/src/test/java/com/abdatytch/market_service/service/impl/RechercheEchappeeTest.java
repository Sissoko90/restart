package com.abdatytch.market_service.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le motif {@code LIKE} d'une recherche.
 *
 * <h2>Le defaut pince</h2>
 *
 * Le motif etait {@code "%" + terme + "%"}, sans echappement. Mesure sur un
 * PostgreSQL reel, sur quatre articles dont « Solde -50% » et « T_shirt » :
 *
 * <pre>
 * terme = %   non echappe -> 4 resultats sur 4   echappe -> 1 (Solde -50%)
 * terme = _   non echappe -> 4 resultats sur 4   echappe -> 1 (T_shirt)
 * </pre>
 *
 * <p>Ce n'est pas une faille — la clause de visibilite s'applique quand meme,
 * et la taille de page est plafonnee. C'est un defaut de COMPORTEMENT : un
 * article nomme « Solde -50% » ne se trouve pas par son nom, et une frappe de
 * travers ramene tout.
 */
@DisplayName("Le motif de recherche")
class RechercheEchappeeTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("une recherche vide ne produit AUCUN motif")
    void rechercheVide(String recherche) {
        assertThat(ProduitServiceImpl.motifDeRecherche(recherche))
                .withFailMessage("""
                        Une recherche vide produit un motif.

                        L'appelant choisit entre deux requetes selon que le motif est \
                        nul ou non. Un motif « %% » ferait passer tout le catalogue \
                        par le chemin de la recherche, avec son ILIKE inutile.""")
                .isNull();
    }

    @ParameterizedTest(name = "« {0} » -> « {1} »")
    @CsvSource(delimiter = '|', value = {
            "bazin        | %bazin%",
            "  bazin      | %bazin%",
            "Solde -50%   | %Solde -50\\%%",
            "T_shirt      | %T\\_shirt%",
            "100%_coton   | %100\\%\\_coton%"})
    @DisplayName("les jokers sont echappes, le reste est intact")
    void jokersEchappes(String terme, String attendu) {
        assertThat(ProduitServiceImpl.motifDeRecherche(terme)).isEqualTo(attendu);
    }

    /**
     * L'ANTISLASH D'ABORD.
     *
     * <p>Si l'antislash etait echappe apres les jokers, les antislashs que
     * ceux-ci introduisent le seraient a leur tour : le terme {@code %}
     * deviendrait un antislash litteral suivi d'un joker — l'inverse du but.
     *
     * <p>Ce test tombe sur l'inversion de l'ordre, et c'est sa raison d'etre.
     */
    @Test
    @DisplayName("un antislash litteral est echappe AVANT les jokers")
    void antislashDAbord() {
        assertThat(ProduitServiceImpl.motifDeRecherche("a\\b"))
                .withFailMessage("""
                        L'antislash n'est pas echappe en premier.

                        Les antislashs introduits par l'echappement des jokers \
                        seraient echappes a leur tour, et « %% » deviendrait un \
                        antislash litteral suivi d'un joker.""")
                .isEqualTo("%a\\\\b%");

        // Le cas qui revele l'inversion : un antislash ET un joker.
        assertThat(ProduitServiceImpl.motifDeRecherche("\\%"))
                .isEqualTo("%\\\\\\%%");
    }

    @Test
    @DisplayName("un terme ordinaire reste cherchable")
    void termeOrdinaire() {
        assertThat(ProduitServiceImpl.motifDeRecherche("Bazin riche"))
                .isEqualTo("%Bazin riche%")
                .doesNotContain("\\");
    }
}
