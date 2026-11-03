package com.abdatytch.user_service.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Neutralisation des métacaractères de {@code LIKE}.
 *
 * <p>Le paramètre lié protège de l'injection SQL ; il ne protège pas de
 * l'injection de <b>motif</b>. {@code %} et {@code _} sont interprétés par
 * {@code ILIKE} après la liaison, donc une recherche sur {@code %} rendrait la
 * page d'administration entière et {@code _} correspondrait à n'importe quel
 * caractère.
 */
@DisplayName("Motif de recherche — neutralisation des métacaractères")
class MotifDeRechercheTest {

    @Test
    @DisplayName("un terme ordinaire est simplement encadré")
    void termeOrdinaire() {
        assertThat(UserServiceReactiveImpl.motifDeRecherche("bamako"))
                .isEqualTo("%bamako%");
    }

    @Test
    @DisplayName("le pourcent est échappé : il ne doit pas tout lister")
    void pourcentNeutralise() {
        assertThat(UserServiceReactiveImpl.motifDeRecherche("%"))
                .withFailMessage("une recherche sur « %% » rendrait tous les comptes")
                .isEqualTo("%\\%%");
    }

    @Test
    @DisplayName("le souligné est échappé : il ne doit pas remplacer un caractère")
    void souligneNeutralise() {
        assertThat(UserServiceReactiveImpl.motifDeRecherche("a_c"))
                .isEqualTo("%a\\_c%");
    }

    @Test
    @DisplayName("l'antislash est traité en premier, sinon il échappe nos propres échappements")
    void antislashDabord() {
        // Si « % » etait neutralise avant « \ », l'antislash ajoute serait
        // lui-meme double ensuite, et « \% » redeviendrait « \\% » — soit un
        // antislash litteral suivi d'un pourcent actif.
        assertThat(UserServiceReactiveImpl.motifDeRecherche("a\\%b"))
                .isEqualTo("%a\\\\\\%b%");
    }

    @Test
    @DisplayName("une recherche vide ne devient pas « tout lister »")
    void rechercheVide() {
        assertThat(UserServiceReactiveImpl.motifDeRecherche("")).isEmpty();
        assertThat(UserServiceReactiveImpl.motifDeRecherche("   ")).isEmpty();
        assertThat(UserServiceReactiveImpl.motifDeRecherche(null)).isEmpty();
    }
}
