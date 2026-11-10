package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La variante HTML des modeles : chargement, echappement, et texte obligatoire.
 *
 * <h2>Le defaut d'origine, et pourquoi l'echappement est ici</h2>
 *
 * interface-service composait ses deux courriels d'OTP en lisant un gabarit
 * HTML et en y inserant le prenom par un {@code String.replace}. Un prenom est
 * fourni a l'inscription, donc par l'appelant : du balisage y entrait tel quel
 * dans le corps du message.
 *
 * <p>Les clients de messagerie neutralisent les scripts, mais pas un lien ni
 * une mise en forme — c'est-a-dire exactement ce qu'il faut pour faire passer
 * un courriel legitime pour autre chose. L'echappement est donc porte par le
 * RENDU : demander a chaque emetteur d'y penser est la forme la plus sure de
 * l'oublier une fois.
 */
@DisplayName("MoteurDeModeles — la variante HTML")
class MoteurDeModelesHtmlTest {

    /** Un modele du socle qui porte un corps HTML, livre dans modeles-html/. */
    private static final String MODELE_AVEC_HTML = "securite.code-de-verification";

    /** Un modele du socle qui n'en porte pas. */
    private static final String MODELE_SANS_HTML = "securite.nouvelle-connexion";

    private static MoteurDeModeles moteurSurLeFichierSeul() {
        SurchargeDeModeleRepository sansSurcharge = mock(SurchargeDeModeleRepository.class);
        when(sansSurcharge.findAll()).thenReturn(Flux.empty());
        return new MoteurDeModeles(
                new ClassPathResource("modeles-notification.properties"), sansSurcharge);
    }

    // ==================================================================
    @Nested
    @DisplayName("Le chargement depuis modeles-html/")
    class Chargement {

        @Test
        @DisplayName("un fichier de modeles-html peuple la clef corps-html du socle")
        void fichierChargeDansLeSocle() {
            var moteur = moteurSurLeFichierSeul();

            assertThat(moteur.clesDuSocle())
                    .withFailMessage("""
                            La clef %s.corps-html n'est pas dans le socle.

                            Elle doit y etre posee par chargerLesCorpsHtml depuis \
                            modeles-html/%s.html. Sans cela le courriel partirait \
                            en texte brut, et rien ne le signalerait.""",
                            MODELE_AVEC_HTML, MODELE_AVEC_HTML)
                    .contains(MODELE_AVEC_HTML + "." + CleDeModele.CORPS_HTML);
        }

        @Test
        @DisplayName("le corps HTML est rendu avec le reste du contenu")
        void corpsHtmlRendu() {
            var contenu = moteurSurLeFichierSeul().rendre(
                    MODELE_AVEC_HTML, CanalDeNotification.COURRIEL,
                    Map.of("code", "123456", "minutes", "10", "prenom", "Awa"));

            assertThat(contenu).isPresent();
            assertThat(contenu.get().corpsHtml())
                    .isNotNull()
                    .contains("123456")
                    .contains("Awa")
                    .contains("cid:logo");
            // Le texte brut reste present : le message part en
            // multipart/alternative, et un courriel sans variante texte est un
            // signal reconnu des filtres anti-indesirables.
            assertThat(contenu.get().corps()).contains("123456");
        }

        @Test
        @DisplayName("un modele sans corps HTML rend null, et non une chaine vide")
        void sansCorpsHtml() {
            var contenu = moteurSurLeFichierSeul().rendre(
                    MODELE_SANS_HTML, CanalDeNotification.COURRIEL,
                    Map.of("circonstances", "x"));

            assertThat(contenu).isPresent();
            // null et non "" : CanalCourriel decide du multipart sur ce test, et
            // une chaine vide produirait un courriel HTML sans contenu — pire
            // qu'un courriel en texte brut.
            assertThat(contenu.get().corpsHtml()).isNull();
        }

        /**
         * Un marqueur inconnu reste EN PLACE, il ne disparait pas.
         *
         * <p>Cela compte pour un corps HTML plus que pour un texte : une
         * feuille de style en ligne contient des accolades, et un gabarit peut
         * en porter. Les effacer casserait la mise en page du courriel.
         *
         * <p>Premiere version de ce test : elle rendait le gabarit livre et
         * verifiait l'absence de « null ». Elle echouait — sur le mot « nulle
         * part » d'un commentaire du gabarit — et surtout elle ne mesurait pas
         * ce qu'elle annoncait. Un gabarit d'essai dit la chose exactement.
         */
        @Test
        @DisplayName("un marqueur inconnu reste en place, accolades comprises")
        void marqueurInconnuPreserve() {
            SurchargeDeModeleRepository surcharges = mock(SurchargeDeModeleRepository.class);
            when(surcharges.findAll()).thenReturn(Flux.just(
                    com.abdatytch.notification_service.entities.SurchargeDeModele.de(
                            "essai.accolades.corps", "texte", "test"),
                    com.abdatytch.notification_service.entities.SurchargeDeModele.de(
                            "essai.accolades." + CleDeModele.CORPS_HTML,
                            "<style>p { color: red; }</style><p>{connue}</p><p>{inconnue}</p>",
                            "test")));
            var moteur = new MoteurDeModeles(
                    new ClassPathResource("modeles-notification.properties"), surcharges);
            moteur.rechargerMaintenant().block();

            String html = moteur.rendre("essai.accolades", CanalDeNotification.COURRIEL,
                    Map.of("connue", "remplacee")).orElseThrow().corpsHtml();

            assertThat(html)
                    .withFailMessage("""
                            Le substituteur a touche a ce qu'il ne connait pas.

                            Rendu : %s

                            Une accolade de feuille de style et un marqueur sans \
                            valeur doivent survivre tels quels : effacer la \
                            premiere casse la mise en page, et remplacer le \
                            second par « null » rend un courriel qu'on ne \
                            diagnostique pas.""", html)
                    .contains("p { color: red; }")
                    .contains("<p>remplacee</p>")
                    .contains("<p>{inconnue}</p>");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'echappement des variables")
    class Echappement {

        /**
         * Le corps HTML du modele d'essai, rendu avec la variable donnee.
         *
         * <p>Une SURCHARGE et non le fichier livre : le test controle alors le
         * gabarit exact, donc ce qui est echappe et ce qui ne l'est pas, sans
         * dependre d'une mise en page qui peut changer.
         */
        private String rendu(String valeurDeLaVariable) {
            SurchargeDeModeleRepository surcharges = mock(SurchargeDeModeleRepository.class);
            when(surcharges.findAll()).thenReturn(Flux.just(
                    com.abdatytch.notification_service.entities.SurchargeDeModele.de(
                            "essai.html.corps", "texte {valeur}", "test"),
                    com.abdatytch.notification_service.entities.SurchargeDeModele.de(
                            "essai.html." + CleDeModele.CORPS_HTML,
                            "<p>{valeur}</p>", "test")));

            var moteur = new MoteurDeModeles(
                    new ClassPathResource("modeles-notification.properties"), surcharges);
            moteur.rechargerMaintenant().block();

            return moteur.rendre("essai.html", CanalDeNotification.COURRIEL,
                    Map.of("valeur", valeurDeLaVariable)).orElseThrow().corpsHtml();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "'<b>',       '&lt;b&gt;'",
                "'\"x\"',       '&quot;x&quot;'",
                "'a & b',     'a &amp; b'",
                "\"l'ami\",     'l&#39;ami'"})
        @DisplayName("les cinq caracteres de balisage sont traduits")
        void caracteresTraduits(String entree, String attendu) {
            assertThat(rendu(entree)).contains(attendu);
        }

        @Test
        @DisplayName("une balise fournie en variable ne devient pas du balisage")
        void baliseNeutralisee() {
            String html = rendu("<a href=\"https://exemple.invalid\">cliquez</a>");

            assertThat(html)
                    .withFailMessage("""
                            Un lien fourni en variable est devenu un vrai lien \
                            dans le corps du courriel.

                            C'est le defaut exact d'interface-service : le prenom, \
                            fourni a l'inscription, etait insere par un replace \
                            sans echappement. Un courriel legitime devient alors \
                            un support d'hameconnage.""")
                    .doesNotContain("<a href=")
                    .contains("&lt;a href=&quot;");
        }

        @Test
        @DisplayName("l'esperluette est traduite AVANT les autres caracteres")
        void esperlutteEnPremier() {
            // Traduire & apres < produirait &amp;lt; au lieu de &lt; : le
            // destinataire lirait « &lt;b&gt; » au lieu de « <b> ».
            assertThat(rendu("<b>"))
                    .contains("&lt;b&gt;")
                    .doesNotContain("&amp;lt;");
        }

        @Test
        @DisplayName("le texte brut du MEME modele n'est PAS echappe")
        void texteBrutNonEchappe() {
            SurchargeDeModeleRepository surcharges = mock(SurchargeDeModeleRepository.class);
            when(surcharges.findAll()).thenReturn(Flux.just(
                    com.abdatytch.notification_service.entities.SurchargeDeModele.de(
                            "essai.texte.corps", "valeur : {valeur}", "test")));
            var moteur = new MoteurDeModeles(
                    new ClassPathResource("modeles-notification.properties"), surcharges);
            moteur.rechargerMaintenant().block();

            // COURRIEL et non SMS : le SMS n'a volontairement AUCUN repli sur
            // le corps generique, donc il aurait fallu declarer
            // essai.texte.sms.corps. La premiere version de ce test l'ignorait
            // et echouait sur un Optional vide — en accusant l'echappement.
            String texte = moteur.rendre("essai.texte", CanalDeNotification.COURRIEL,
                    Map.of("valeur", "a & b")).orElseThrow().corps();

            assertThat(texte)
                    .withFailMessage("""
                            Le texte brut a ete echappe : la personne lirait \
                            « a &amp; b » dans son SMS.

                            L'echappement ne concerne QUE la partie corps-html.""")
                    .isEqualTo("valeur : a & b");
        }
    }
}
