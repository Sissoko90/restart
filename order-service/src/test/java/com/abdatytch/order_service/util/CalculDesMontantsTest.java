package com.abdatytch.order_service.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La decomposition d'une commande, mise a l'epreuve.
 *
 * <p>C'est du calcul d'argent. Une erreur d'un centime par commande est
 * invisible a l'unite et devient une anomalie comptable a l'echelle, qu'on
 * decouvre six mois plus tard sans savoir d'ou elle vient.
 */
@DisplayName("CalculDesMontants — la part de Sankadi et celle de la boutique")
class CalculDesMontantsTest {

    @Nested
    @DisplayName("La decomposition boucle TOUJOURS")
    class ElleBoucle {

        /**
         * La contrainte {@code commande_decomposition_boucle} exige
         * {@code articles = commission + du}. Si ce test tombe, l'insertion est
         * refusee par la base — mais mieux vaut le savoir ici que sur une
         * commande d'un client.
         */
        @ParameterizedTest
        @CsvSource({
                "10000, 5.00",
                "10000, 0.00",
                "10000, 100.00",
                "1, 5.00",
                "1, 99.99",
                "0, 5.00",
                "333, 33.33",
                "99999999, 7.77",
                "7, 50.00"
        })
        @DisplayName("articles = commission + du, quel que soit le montant et le taux")
        void sommeExacte(long articles, String taux) {
            var d = CalculDesMontants.decomposer(articles, new BigDecimal(taux));

            assertThat(d.commission() + d.duALaBoutique())
                    .withFailMessage("""
                            La decomposition ne boucle pas : %d articles, %d de \
                            commission, %d dus.

                            La contrainte commande_decomposition_boucle refusera \
                            l'insertion, et un centime orphelin n'est attribuable \
                            a personne.""".formatted(articles, d.commission(), d.duALaBoutique()))
                    .isEqualTo(articles);
        }
    }

    @Nested
    @DisplayName("L'arrondi va dans le sens de la boutique")
    class Arrondi {

        /**
         * 7 unites a 50 % font 3,5. La commission doit etre 3 et non 4 :
         * l'intermediaire ne gagne jamais sur un arrondi.
         *
         * <p>Si l'arrondi passait a {@code HALF_UP}, ce test tombe. C'est le
         * seul endroit ou ce choix est verifiable, et il est invisible a la
         * lecture du code appelant.
         */
        @Test
        @DisplayName("un demi-centime de commission est laisse a la boutique")
        void demiCentimeALaBoutique() {
            var d = CalculDesMontants.decomposer(7, new BigDecimal("50.00"));

            assertThat(d.commission())
                    .withFailMessage("""
                            7 a 50 %% font 3,5 : la commission doit etre 3, pas %d.

                            Un arrondi en faveur de l'intermediaire donne a Sankadi \
                            un avantage systematique sur chaque commande, et un \
                            vendeur qui recompte le remarque.""".formatted(d.commission()))
                    .isEqualTo(3L);
            assertThat(d.duALaBoutique()).isEqualTo(4L);
        }

        @Test
        @DisplayName("un taux de zero laisse tout a la boutique")
        void tauxNulNePrendRien() {
            var d = CalculDesMontants.decomposer(10000, BigDecimal.ZERO);
            assertThat(d.commission()).isZero();
            assertThat(d.duALaBoutique()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("un taux de cent ne laisse rien, sans jamais passer sous zero")
        void tauxMaximalNeDepassePas() {
            var d = CalculDesMontants.decomposer(10000, new BigDecimal("100.00"));
            assertThat(d.commission()).isEqualTo(10000L);
            assertThat(d.duALaBoutique())
                    .withFailMessage("La somme due passe sous zero : la contrainte "
                            + "commande_montants_positifs refusera l'insertion.")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Les entrees absurdes sont refusees, et non calculees")
    class EntreesRefusees {

        /**
         * Echouer franchement plutot que produire un montant negatif. Une
         * commande a montant negatif passerait la contrainte de bouclage et
         * ferait DEVOIR de l'argent a l'acheteur.
         */
        @Test
        @DisplayName("un montant negatif")
        void montantNegatif() {
            assertThatThrownBy(() -> CalculDesMontants.decomposer(-1, new BigDecimal("5.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un taux au-dela de cent pour cent")
        void tauxAberrant() {
            assertThatThrownBy(() ->
                    CalculDesMontants.decomposer(100, new BigDecimal("101.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un taux negatif")
        void tauxNegatif() {
            assertThatThrownBy(() ->
                    CalculDesMontants.decomposer(100, new BigDecimal("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("un taux absent")
        void tauxAbsent() {
            assertThatThrownBy(() -> CalculDesMontants.decomposer(100, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
