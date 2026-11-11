package com.abdatytch.market_service.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le slug d'une categorie, qui devient une URL publique.
 *
 * <p>Les accents sont retires par decomposition Unicode plutot que par une
 * table de correspondance : une table oublie toujours une langue, et ce marche
 * sert plusieurs pays.
 */
@DisplayName("Le slug d'une categorie")
class CategorieSlugTest {

    @ParameterizedTest(name = "« {0} » -> « {1} »")
    @CsvSource({
            "Electronique,                 electronique",
            "Électroménager,               electromenager",
            "Mode & Accessoires,           mode-accessoires",
            "  Maison   et   Jardin  ,     maison-et-jardin",
            "Téléphonie / Tablettes,       telephonie-tablettes",
            "Épicerie,                     epicerie"})
    @DisplayName("minuscules, sans accent, separe par des traits")
    void slugLisible(String nom, String attendu) {
        assertThat(CategorieServiceImpl.slug(nom)).isEqualTo(attendu);
    }

    @Test
    @DisplayName("ne commence ni ne finit par un trait")
    void sansTraitAuxExtremites() {
        assertThat(CategorieServiceImpl.slug("!!! Promo !!!"))
                .isEqualTo("promo");
    }

    /**
     * Un nom entierement non latin ne doit pas produire un slug VIDE.
     *
     * <p>Un slug vide ferait une URL qui ne designe rien, et la contrainte
     * d'unicite refuserait la deuxieme categorie dans ce cas.
     */
    @Test
    @DisplayName("un nom sans caractere latin donne un slug de repli, jamais vide")
    void jamaisVide() {
        assertThat(CategorieServiceImpl.slug("أجهزة"))
                .withFailMessage("""
                        Le slug est vide.

                        Une URL qui ne designe rien, et la contrainte d'unicite \
                        refuserait la categorie suivante dans le meme cas.""")
                .isEqualTo("categorie");
    }
}
