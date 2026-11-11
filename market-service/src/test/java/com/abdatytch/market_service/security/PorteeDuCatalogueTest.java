package com.abdatytch.market_service.security;

import com.abdatytch.market_service.entities.Produit;
import com.abdatytch.market_service.enums.StatutModeration;
import com.abdatytch.market_service.enums.Visibilite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La decision unitaire de visibilite, cas par cas.
 *
 * <h2>Pourquoi une table de verite et non quelques exemples</h2>
 *
 * Quatre conditions se combinent, et c'est le genre de regle ou l'on teste le
 * cas evident — un particulier ne voit pas un prix de gros — en oubliant celui
 * qui coute : un article APPROUVE dont la BOUTIQUE est suspendue, ou un article
 * desactive par son vendeur mais toujours approuve.
 *
 * <p>Cette classe double {@code RegleDeVisibiliteUniqueTest}, qui verifie que
 * la clause SQL existe en un seul endroit. Celle-ci verifie ce que la regle
 * DECIDE. Les deux sont necessaires : une clause unique et fausse reste fausse
 * partout a la fois.
 */
@DisplayName("PorteeDuCatalogue — qui voit quoi")
class PorteeDuCatalogueTest {

    private static final UUID MA_BOUTIQUE = UUID.randomUUID();
    private static final UUID AUTRE_BOUTIQUE = UUID.randomUUID();

    private static Produit produit(UUID boutique, Visibilite visibilite,
                                   StatutModeration statut, boolean actif, String pays) {
        Produit produit = new Produit();
        produit.setBoutiqueId(boutique);
        produit.setVisibilite(visibilite);
        produit.setStatutModeration(statut);
        produit.setActif(actif);
        produit.setPays(pays);
        return produit;
    }

    private static PorteeDuCatalogue particulier(String pays) {
        return new PorteeDuCatalogue("u1", pays, null, false, false);
    }

    private static PorteeDuCatalogue professionnel(String pays) {
        return new PorteeDuCatalogue("u1", pays, MA_BOUTIQUE, true, false);
    }

    private static PorteeDuCatalogue vendeurNonApprouve() {
        return new PorteeDuCatalogue("u1", "ML", MA_BOUTIQUE, false, false);
    }

    // ==================================================================
    @Nested
    @DisplayName("Un particulier")
    class Particulier {

        @ParameterizedTest(name = "pays {0}, visibilite {1} -> {2}")
        @CsvSource({
                "ML, PUBLIQUE,         true",
                "SN, PUBLIQUE,         false",
                "ML, RESERVEE_AUX_PRO, false",
                "SN, RESERVEE_AUX_PRO, false"})
        @DisplayName("ne voit que le PUBLIC de SON pays")
        void sonPaysEtLePublic(String paysDuProduit, Visibilite visibilite, boolean attendu) {
            var produit = produit(AUTRE_BOUTIQUE, visibilite, StatutModeration.APPROUVE,
                    true, paysDuProduit);
            assertThat(particulier("ML").voit(produit, true))
                    .withFailMessage("""
                            Un particulier de ML face a un article %s du pays %s.
                            Attendu : %s.

                            La regle : son pays ET une visibilite publique. Les deux, \
                            pas l'une ou l'autre.""", visibilite, paysDuProduit, attendu)
                    .isEqualTo(attendu);
        }

        /**
         * LE CAS QU'ON OUBLIE.
         *
         * <p>Un article approuve, actif, public et du bon pays — mais dont la
         * boutique a ete suspendue. Rien dans le produit ne le dit : l'etat
         * vit sur la boutique, et c'est la jointure qui le porte en SQL.
         */
        @Test
        @DisplayName("ne voit rien d'une boutique NON approuvee, meme un article parfait")
        void boutiqueNonApprouvee() {
            var parfait = produit(AUTRE_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.APPROUVE, true, "ML");
            assertThat(particulier("ML").voit(parfait, false))
                    .withFailMessage("""
                            Un article irreprochable d'une boutique suspendue reste \
                            visible.

                            Suspendre une boutique doit retirer ses articles de la \
                            vitrine a l'instant meme, sans toucher aux articles — \
                            sans quoi lever la suspension ne les rendrait pas tels \
                            qu'ils etaient.""")
                    .isFalse();
        }

        @Test
        @DisplayName("ne voit pas un article EN ATTENTE de moderation")
        void enAttente() {
            assertThat(particulier("ML").voit(produit(AUTRE_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.EN_ATTENTE, true, "ML"), true)).isFalse();
        }

        @Test
        @DisplayName("ne voit pas un article retire de la vitrine par son vendeur")
        void inactif() {
            assertThat(particulier("ML").voit(produit(AUTRE_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.APPROUVE, false, "ML"), true)).isFalse();
        }

        /**
         * Sans pays connu, un particulier ne voit RIEN.
         *
         * <p>C'est la degradation voulue quand user-service est injoignable :
         * il voit moins, jamais plus. Supposer un pays par defaut montrerait a
         * quelqu'un un catalogue qui ne le concerne pas.
         */
        @Test
        @DisplayName("sans pays connu, ne voit rien")
        void sansPays() {
            assertThat(particulier(null).voit(produit(AUTRE_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.APPROUVE, true, "ML"), true)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un professionnel")
    class Professionnel {

        @ParameterizedTest(name = "pays {0}, visibilite {1}")
        @CsvSource({
                "ML, PUBLIQUE", "SN, PUBLIQUE",
                "ML, RESERVEE_AUX_PRO", "SN, RESERVEE_AUX_PRO"})
        @DisplayName("voit tous les pays et les deux visibilites")
        void toutCeQuiEstPublie(String pays, Visibilite visibilite) {
            assertThat(professionnel("ML").voit(produit(AUTRE_BOUTIQUE, visibilite,
                    StatutModeration.APPROUVE, true, pays), true)).isTrue();
        }

        @Test
        @DisplayName("ne voit pas l'article NON APPROUVE d'un confrere")
        void pasLeNonApprouveDesAutres() {
            assertThat(professionnel("ML").voit(produit(AUTRE_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.EN_ATTENTE, true, "ML"), true)).isFalse();
        }

        @Test
        @DisplayName("ne voit rien d'une boutique suspendue, fut-elle professionnelle")
        void pasLesBoutiquesSuspendues() {
            assertThat(professionnel("ML").voit(produit(AUTRE_BOUTIQUE,
                    Visibilite.RESERVEE_AUX_PRO, StatutModeration.APPROUVE, true, "SN"),
                    false)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un vendeur face a SES articles")
    class SesPropresArticles {

        /**
         * La deuxieme branche de la clause, et le coeur du cycle de validation.
         *
         * <p>Un vendeur dont la boutique est encore EN ATTENTE doit voir ses
         * articles pour les corriger. Sans cela, il depose un dossier et ne
         * revoit plus rien jusqu'a l'approbation.
         */
        @Test
        @DisplayName("les voit meme si la boutique n'est pas encore approuvee")
        void sesArticlesAvantApprobation() {
            assertThat(vendeurNonApprouve().voit(produit(MA_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.EN_ATTENTE, false, "SN"), false))
                    .withFailMessage("""
                            Un vendeur ne voit pas ses propres articles.

                            Il ne peut alors ni les corriger, ni comprendre pourquoi \
                            ils ont ete refuses.""")
                    .isTrue();
        }

        @Test
        @DisplayName("ne voit pas pour autant ceux des autres")
        void pasCeuxDesAutres() {
            assertThat(vendeurNonApprouve().voit(produit(AUTRE_BOUTIQUE,
                    Visibilite.RESERVEE_AUX_PRO, StatutModeration.APPROUVE, true, "ML"),
                    true)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un administrateur")
    class Administrateur {

        @Test
        @DisplayName("voit tout, y compris ce qui n'est pas publie")
        void voitTout() {
            var portee = PorteeDuCatalogue.administrateur("admin");
            assertThat(portee.voit(produit(AUTRE_BOUTIQUE, Visibilite.RESERVEE_AUX_PRO,
                    StatutModeration.EN_ATTENTE, false, "SN"), false)).isTrue();
        }

        @Test
        @DisplayName("n'a ni pays ni boutique : sa portee n'en depend pas")
        void sansPaysNiBoutique() {
            var portee = PorteeDuCatalogue.administrateur("admin");
            assertThat(portee.pays()).isNull();
            assertThat(portee.boutiqueId()).isNull();
            assertThat(portee.professionnel()).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les cas limites")
    class CasLimites {

        @Test
        @DisplayName("un produit nul n'est visible de personne, pas meme d'un admin")
        void produitNul() {
            assertThat(PorteeDuCatalogue.administrateur("admin").voit(null, true)).isFalse();
        }

        /**
         * Une portee vide est celle d'un contexte de securite absent.
         *
         * <p>Elle ne doit rien montrer. L'inverse — une portee par defaut qui
         * montre le public — ferait d'une panne d'authentification une
         * ouverture.
         */
        @Test
        @DisplayName("une portee vide ne voit rien")
        void porteeVide() {
            var vide = new PorteeDuCatalogue(null, null, null, false, false);
            assertThat(vide.voit(produit(AUTRE_BOUTIQUE, Visibilite.PUBLIQUE,
                    StatutModeration.APPROUVE, true, "ML"), true)).isFalse();
        }
    }
}
