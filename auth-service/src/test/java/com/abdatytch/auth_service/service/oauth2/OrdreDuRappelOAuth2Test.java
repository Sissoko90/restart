package com.abdatytch.auth_service.service.oauth2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le rappel OAuth2 verifie le state AVANT d'echanger le code.
 *
 * <h2>Pourquoi un test sur la source et non sur le comportement</h2>
 *
 * {@code OAuth2ServiceImpl} a onze dependances et aucun test unitaire. Monter un
 * contexte pour prouver un <b>ordre</b> couterait plus cher que la regle qu'il
 * protege, et le test porterait sur des simulacres plutot que sur le parcours.
 *
 * <p>La question posee ici est de toute façon structurelle : l'appel a
 * {@code consommer} existe-t-il, et precede-t-il l'echange ? C'est exactement ce
 * qu'un test de source peut etablir, et c'est la forme que ce depot emploie
 * deja pour {@code AnnotationsResilienceInertesTest} et
 * {@code AppelsSortantsBornesTest}.
 *
 * <h2>Pourquoi l'ordre compte, et pas seulement la presence</h2>
 *
 * Echanger puis verifier consommerait le code d'autorisation d'un parcours que
 * nous n'avons pas initie — et un code ne sert qu'une fois. L'attaquant
 * obtiendrait l'effet de bord qu'il cherche meme si la requete est ensuite
 * refusee. Verifier d'abord est ce qui rend le refus reellement sans effet.
 */
@DisplayName("Le rappel OAuth2 verifie le state avant d'echanger le code")
class OrdreDuRappelOAuth2Test {

    private static final Path SERVICE = Path.of(
            "src/main/java/com/abdatytch/auth_service/service/impl/OAuth2ServiceImpl.java");

    /**
     * Lit la methode {@code handleOAuth2Callback}, commentaires retires.
     *
     * <p>Les commentaires sont neutralises parce qu'ils citent eux-memes
     * {@code consommer} et {@code echangerLeCode} : sans cela, le test
     * passerait grace a sa propre documentation — le piege exact qui avait
     * fait passer {@code RepartitionDeChargeTest} sur sa javadoc.
     */
    private static String corpsDuRappel() throws IOException {
        String source = Files.readString(SERVICE)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");

        int debut = source.indexOf("public Mono<OAuth2AuthResponse> handleOAuth2Callback");
        assertThat(debut)
                .withFailMessage("handleOAuth2Callback est introuvable : la methode "
                        + "a ete renommee et ce garde-fou ne protege plus rien")
                .isNotNegative();

        int suivant = source.indexOf("public Mono<OAuth2AuthResponse> completeProfile", debut);
        return suivant > debut ? source.substring(debut, suivant) : source.substring(debut);
    }

    @Test
    @DisplayName("consommer(state) est appele, et avant echangerLeCode")
    void stateVerifieAvantLEchange() throws IOException {
        String corps = corpsDuRappel();

        int verification = corps.indexOf(".consommer(");
        int echange = corps.indexOf(".echangerLeCode(");

        assertThat(verification)
                .withFailMessage("""
                        Le rappel OAuth2 n'appelle pas consommer(state).

                        Sans cette verification, rien ne distingue un rappel que \
                        nous avons initie d'un rappel provoque par un tiers. C'est \
                        le login CSRF : la victime se retrouve connectee au compte \
                        de l'attaquant, ou le compte social de l'attaquant se lie \
                        au sien.

                        C'etait l'etat d'origine de ce service : la constante \
                        OAUTH2_STATE_PREFIX et le champ state existaient, et \
                        n'etaient utilises nulle part.""")
                .isNotNegative();

        assertThat(echange)
                .withFailMessage("echangerLeCode est introuvable dans le rappel")
                .isNotNegative();

        assertThat(verification)
                .withFailMessage("""
                        Le code est echange AVANT que le state soit verifie.

                        Un code d'autorisation ne sert qu'une fois : l'echanger \
                        d'abord le consomme pour un parcours que nous n'avons pas \
                        initie, et l'attaquant obtient son effet de bord meme si la \
                        requete est ensuite refusee.""")
                .isLessThan(echange);
    }

    @Test
    @DisplayName("le service ne connait plus aucun fournisseur par son nom")
    void aucunFournisseurNommeDansLeService() throws IOException {
        // Le but de la migration : plus un seul branchement par fournisseur
        // dans le service. Un « if (provider == GOOGLE) » qui reapparait ici
        // ramene le defaut d'origine, ou chaque regle devait etre recopiee a
        // plusieurs endroits et un oubli ne produisait aucune erreur.
        String source = Files.readString(SERVICE)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");

        for (String nomme : new String[]{"OAuthProvider.GOOGLE", "OAuthProvider.FACEBOOK",
                "OAuthProvider.APPLE", "EventType.GOOGLE_LOGIN", "EventType.FACEBOOK_LOGIN",
                "EventType.APPLE_LOGIN"}) {
            assertThat(source)
                    .withFailMessage("""
                            OAuth2ServiceImpl mentionne %s.

                            Le service doit traiter les fournisseurs de façon \
                            uniforme : le choix appartient au registre, et la \
                            correspondance vers l'evenement appartient a \
                            l'enumeration. Un branchement nomme ici est le debut \
                            de la duplication que cette migration a retiree — \
                            elle comptait trois copies du meme ternaire.""", nomme)
                    .doesNotContain(nomme);
        }
    }
}
