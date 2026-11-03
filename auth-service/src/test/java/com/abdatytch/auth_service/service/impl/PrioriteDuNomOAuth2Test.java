package com.abdatytch.auth_service.service.impl;

import com.abdatytch.auth_service.dto.request.OAuth2CallbackRequest;
import com.abdatytch.auth_service.dto.response.OAuth2UserInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui a le dernier mot sur le nom : le fournisseur, jamais le rappel.
 *
 * <h2>Pourquoi cette suite existe</h2>
 *
 * Elle a ete ecrite apres un mutant <b>survivant</b>. En remplaçant
 *
 * <pre>
 * if (!estRenseigne(userInfo.getFirstName()) &amp;&amp; estRenseigne(request.getPrenomDuRappel()))
 * </pre>
 *
 * par
 *
 * <pre>
 * if (estRenseigne(request.getPrenomDuRappel()))
 * </pre>
 *
 * toute la suite passait encore : aucun test n'exerçait le cas ou le
 * fournisseur <b>et</b> le rappel portent un nom. La regle de priorite etait
 * donc ecrite et non protegee.
 *
 * <h2>Ce que l'inversion permettrait</h2>
 *
 * Le nom du rappel vient d'un formulaire poste par un tiers, pas d'un echange
 * verifie. S'il pouvait ecraser un nom obtenu de Google, il suffirait de forger
 * un rappel pour <b>modifier l'etat civil</b> d'un compte existant. L'identite
 * resterait juste — elle vient du {@code sub} verifie — mais le nom affiche,
 * lui, serait celui de l'attaquant.
 *
 * <h2>Et pourquoi le rappel sert quand meme</h2>
 *
 * Apple n'a aucun point de profil et ne transmet le nom qu'une fois, dans le
 * formulaire de rappel. Sans cette reprise, tous les comptes Apple seraient
 * anonymes. Les deux regles tiennent ensemble : on reprend ce que le
 * fournisseur n'a pas donne, et seulement cela.
 */
@DisplayName("Priorite du nom : le fournisseur avant le rappel")
class PrioriteDuNomOAuth2Test {

    private static OAuth2UserInfo duFournisseur(String prenom, String nom, String complet) {
        return OAuth2UserInfo.builder()
                .providerId("001234.abcdef")
                .firstName(prenom)
                .lastName(nom)
                .name(complet)
                .build();
    }

    private static OAuth2CallbackRequest rappel(String prenom, String nom) {
        return OAuth2CallbackRequest.builder()
                .code("code")
                .provider("apple")
                .state("etat")
                .prenomDuRappel(prenom)
                .nomDuRappel(nom)
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le fournisseur a toujours la priorite")
    class FournisseurPrioritaire {

        /**
         * Le cas que le mutant survivant avait laisse passer.
         */
        @Test
        @DisplayName("un nom du fournisseur n'est JAMAIS ecrase par celui du rappel")
        void nomDuFournisseurNonEcrase() {
            OAuth2UserInfo info = duFournisseur("Awa", "Diallo", "Awa Diallo");

            OAuth2ServiceImpl.completerAvecLeRappel(info, rappel("Attaquant", "Usurpateur"));

            assertThat(info.getFirstName())
                    .withFailMessage("""
                            Le prenom du rappel a ecrase celui du fournisseur.

                            Le nom du rappel vient d'un formulaire poste par un \
                            tiers, pas d'un echange verifie : il suffirait alors \
                            de forger un rappel pour modifier l'etat civil d'un \
                            compte existant.""")
                    .isEqualTo("Awa");
            assertThat(info.getLastName()).isEqualTo("Diallo");
            assertThat(info.getName()).isEqualTo("Awa Diallo");
        }

        @Test
        @DisplayName("la priorite s'applique champ par champ")
        void prioriteParChamp() {
            // Google donne parfois le prenom sans le nom. Le champ manquant se
            // complete, celui qui est present ne bouge pas.
            OAuth2UserInfo info = duFournisseur("Awa", null, null);

            OAuth2ServiceImpl.completerAvecLeRappel(info, rappel("Attaquant", "Diallo"));

            assertThat(info.getFirstName()).isEqualTo("Awa");
            assertThat(info.getLastName()).isEqualTo("Diallo");
            assertThat(info.getName()).isEqualTo("Awa Diallo");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le rappel comble ce que le fournisseur ne donne pas")
    class RappelComble {

        /**
         * Le cas Apple, et la raison d'etre de toute cette reprise.
         *
         * <p>Apple n'a aucun point de profil et ne transmet le nom qu'a la
         * premiere autorisation, dans le formulaire de rappel. Sans cette
         * reprise, <b>tous</b> les comptes crees par Apple seraient anonymes —
         * et le nom serait perdu definitivement, les connexions suivantes ne le
         * renvoyant plus.
         */
        @Test
        @DisplayName("un fournisseur sans nom laisse le rappel renseigner les deux champs")
        void fournisseurSansNom() {
            OAuth2UserInfo info = duFournisseur(null, null, null);

            OAuth2ServiceImpl.completerAvecLeRappel(info, rappel("Awa", "Diallo"));

            assertThat(info.getFirstName()).isEqualTo("Awa");
            assertThat(info.getLastName()).isEqualTo("Diallo");
            assertThat(info.getName())
                    .withFailMessage("le nom complet n'est pas compose : le compte "
                            + "aurait un prenom et un nom mais aucun nom affichable")
                    .isEqualTo("Awa Diallo");
        }

        @Test
        @DisplayName("les espaces autour des valeurs du rappel sont retires")
        void espacesRetires() {
            OAuth2UserInfo info = duFournisseur(null, null, null);

            OAuth2ServiceImpl.completerAvecLeRappel(info, rappel("  Awa ", " Diallo "));

            assertThat(info.getFirstName()).isEqualTo("Awa");
            assertThat(info.getName()).isEqualTo("Awa Diallo");
        }

        @Test
        @DisplayName("un seul champ au rappel suffit, et ne compose pas un nom bancal")
        void unSeulChamp() {
            OAuth2UserInfo info = duFournisseur(null, null, null);

            OAuth2ServiceImpl.completerAvecLeRappel(info, rappel("Awa", null));

            assertThat(info.getName())
                    .withFailMessage("un espace parasite subsiste dans le nom "
                            + "compose quand un seul champ est fourni")
                    .isEqualTo("Awa");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un rappel sans nom ne touche a rien")
    class RappelSansNom {

        /**
         * L'etat de Google, de Facebook, et de toutes les connexions Apple sauf
         * la premiere.
         */
        @ParameterizedTest(name = "prenom = « {0} »")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("un rappel sans nom laisse l'objet inchange")
        void rappelSansNomNeChangeRien(String vide) {
            OAuth2UserInfo info = duFournisseur("Awa", "Diallo", "Awa Diallo");

            OAuth2UserInfo apres = OAuth2ServiceImpl.completerAvecLeRappel(
                    info, rappel(vide, vide));

            assertThat(apres).isSameAs(info);
            assertThat(apres.getFirstName()).isEqualTo("Awa");
            assertThat(apres.getLastName()).isEqualTo("Diallo");
        }

        @Test
        @DisplayName("un fournisseur sans nom et un rappel sans nom restent sans nom")
        void aucunNomNullePart() {
            // Un nom compose a partir de rien donnerait une chaine vide, qui
            // passerait ensuite pour un nom fourni — et empecherait de le
            // completer plus tard.
            OAuth2UserInfo info = duFournisseur(null, null, null);

            OAuth2ServiceImpl.completerAvecLeRappel(info, rappel(null, null));

            assertThat(info.getName()).isNull();
        }
    }
}
