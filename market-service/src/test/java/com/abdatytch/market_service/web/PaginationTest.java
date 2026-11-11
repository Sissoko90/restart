package com.abdatytch.market_service.web;

import com.abdatytch.market_service.exception.RefusExplique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Les bornes de pagination.
 *
 * <p>Un plafond qui TRONQUE en silence est pire que pas de plafond : l'appelant
 * croit avoir tout recu, et pagine sur un ensemble qu'il ne connait pas.
 */
@DisplayName("Pagination")
class PaginationTest {

    private final Pagination pagination = new Pagination(100, 20);

    @Test
    @DisplayName("sans parametre : la premiere page, a la taille par defaut")
    void valeursParDefaut() {
        assertThat(pagination.page(null)).isZero();
        assertThat(pagination.taille(null)).isEqualTo(20);
    }

    @Test
    @DisplayName("une page negative est refusee, avec un motif")
    void pageNegative() {
        assertThatThrownBy(() -> pagination.page(-1))
                .isInstanceOf(RefusExplique.class)
                .hasMessageContaining("part de 0");
    }

    @Test
    @DisplayName("une taille nulle ou negative est refusee")
    void tailleInvalide() {
        assertThatThrownBy(() -> pagination.taille(0)).isInstanceOf(RefusExplique.class);
        assertThatThrownBy(() -> pagination.taille(-5)).isInstanceOf(RefusExplique.class);
    }

    @Test
    @DisplayName("au-dela du plafond : REFUS et non troncature")
    void plafondRefuse() {
        assertThatThrownBy(() -> pagination.taille(10_000))
                .withFailMessage("""
                        Une taille de page demesuree est acceptee ou tronquee.

                        Tronquer en silence ferait croire a l'appelant qu'il a tout \
                        recu. Le plafond doit REFUSER, et dire sa valeur.""")
                .isInstanceOf(RefusExplique.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("le plafond lui-meme est accepte")
    void plafondInclusif() {
        assertThat(pagination.taille(100)).isEqualTo(100);
    }
}
