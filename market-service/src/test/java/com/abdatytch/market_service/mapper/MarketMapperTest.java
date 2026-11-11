package com.abdatytch.market_service.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le libelle d'auteur d'un avis.
 *
 * <p>Il ne doit JAMAIS etre l'identifiant du compte : celui-ci relierait chaque
 * avis a son auteur sur une page publique.
 */
@DisplayName("MarketMapper — le libelle d'auteur")
class MarketMapperTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("sans identifiant : « anonyme »")
    void sansIdentifiant(String identifiant) {
        assertThat(MarketMapper.libelleDAuteur(identifiant)).isEqualTo("anonyme");
    }

    @Test
    @DisplayName("ne contient jamais l'identifiant entier")
    void jamaisLIdentifiant() {
        String identifiant = "11111111-1111-1111-1111-111111111111";
        assertThat(MarketMapper.libelleDAuteur(identifiant))
                .withFailMessage("""
                        Le libelle contient l'identifiant du compte.

                        Il relierait chaque avis a son auteur, sur une page publique.""")
                .doesNotContain(identifiant)
                .hasSizeLessThan(8);
    }

    /**
     * Tronque aux DEUX extremites, et c'est le point.
     *
     * <p>Des UUID engendres en sequence partagent souvent leurs premiers
     * caracteres : ne garder que le debut donnerait le meme libelle a des
     * auteurs differents, et deux avis de personnes distinctes passeraient
     * pour une campagne.
     */
    @Test
    @DisplayName("deux identifiants au meme PREFIXE donnent des libelles differents")
    void prefixeCommun() {
        String a = "11111111-1111-1111-1111-11111111aaaa";
        String b = "11111111-1111-1111-1111-11111111bbbb";
        assertThat(MarketMapper.libelleDAuteur(a))
                .withFailMessage("""
                        Deux auteurs differents partagent le meme libelle.

                        Les UUID engendres en sequence partagent leurs premiers \
                        caracteres : tronquer au debut seul les confond.""")
                .isNotEqualTo(MarketMapper.libelleDAuteur(b));
    }

    @Test
    @DisplayName("le meme auteur donne toujours le meme libelle")
    void stable() {
        String identifiant = "22222222-2222-2222-2222-222222222222";
        assertThat(MarketMapper.libelleDAuteur(identifiant))
                .isEqualTo(MarketMapper.libelleDAuteur(identifiant));
    }

    @Test
    @DisplayName("un identifiant tres court est rendu tel quel")
    void identifiantCourt() {
        assertThat(MarketMapper.libelleDAuteur("abc")).isEqualTo("abc");
    }
}
