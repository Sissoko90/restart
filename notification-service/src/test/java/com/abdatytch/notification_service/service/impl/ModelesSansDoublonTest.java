package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le fichier de modeles ne porte aucune cle en double, et couvre ce qu'il doit.
 *
 * <h2>Pourquoi un doublon est dangereux ici</h2>
 *
 * {@code java.util.Properties} accepte une cle dupliquee et garde la
 * <b>derniere</b>, sans avertissement. Deux declarations d'un meme modele ne
 * produisent donc aucune erreur : l'une des deux disparait, en silence.
 *
 * <p>Ce n'est pas une crainte theorique. En portant les modeles d'auth-service
 * dans ce fichier, j'ai ajoute {@code securite.code-de-verification} qui y
 * existait DEJA — et le texte ajoute etait moins bon : celui d'origine dit
 * « Ne le communiquez a personne », celui d'auth-service ne le dit pas. Le
 * doublon aurait donc retire un avertissement de securite, et rien ne l'aurait
 * signale.
 *
 * <p>C'est la comparaison des deux versions qui l'a montre, pas la relecture du
 * fichier.
 */
@DisplayName("Le fichier de modèles de notification")
class ModelesSansDoublonTest {

    private static final String FICHIER = "modeles-notification.properties";

    /**
     * Lit les cles en gardant les DOUBLONS.
     *
     * <p>{@code Properties.load} les ecrase — c'est precisement le defaut a
     * detecter — donc le fichier est lu ligne par ligne.
     */
    private static List<String> clesAvecDoublons() throws IOException {
        List<String> cles = new ArrayList<>();
        try (InputStream flux = new ClassPathResource(FICHIER).getInputStream()) {
            new String(flux.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(ligne -> !ligne.isEmpty() && !ligne.startsWith("#"))
                    .filter(ligne -> ligne.contains("="))
                    .forEach(ligne -> cles.add(ligne.substring(0, ligne.indexOf('='))));
        }
        return cles;
    }

    @Nested
    @DisplayName("Aucune clé en double")
    class SansDoublon {

        @Test
        @DisplayName("aucune clé n'est déclarée deux fois")
        void aucuneCleEnDouble() throws IOException {
            List<String> cles = clesAvecDoublons();

            // Le garde-fou se garde lui-meme : un fichier vide le rendrait vert.
            assertThat(cles)
                    .withFailMessage("Aucune cle lue dans %s : ce garde-fou ne "
                            + "verifie plus rien", FICHIER)
                    .isNotEmpty();

            Set<String> vues = new HashSet<>();
            List<String> doublons = cles.stream()
                    .filter(cle -> !vues.add(cle))
                    .distinct()
                    .toList();

            assertThat(doublons)
                    .withFailMessage("""
                            Ces cles sont declarees DEUX FOIS dans %s : %s

                            java.util.Properties garde la DERNIERE, sans \
                            avertissement : la premiere disparait en silence. \
                            C'est ainsi qu'un avertissement de securite a failli \
                            etre retire de securite.code-de-verification.""",
                            FICHIER, doublons)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Les modèles d'auth-service ont leur contrepartie")
    class ContrepartieDesModelesAuth {

        /**
         * Les cinq {@code TemplateType} d'auth-service, et le modele qui les
         * remplace ici.
         *
         * <p>La carte est ecrite a la main, et c'est volontaire : lire l'enum
         * d'auth-service par reflexion demanderait une dependance de ce module
         * vers celui-la — exactement ce que le decoupage en services interdit.
         *
         * <p>Noter que le nommage d'auth-service MELANGE le canal et l'objet :
         * {@code SMS_VERIFICATION} et {@code EMAIL_VERIFICATION} sont le meme
         * message sur deux canaux, et deux types obligent a tenir deux textes
         * d'accord. Ici l'objet est nomme une fois, le canal n'est qu'une
         * variante — ce que la colonne de droite montre par ses doublons.
         */
        private static final Map<String, String> CONTREPARTIE = Map.of(
                "SMS_VERIFICATION", "securite.code-de-verification",
                "EMAIL_VERIFICATION", "securite.code-de-verification",
                "SMS_PASSWORD_RESET", "securite.reinitialisation-mot-de-passe",
                "EMAIL_PASSWORD_RESET", "securite.reinitialisation-mot-de-passe",
                "EMAIL_WELCOME", "compte.bienvenue");

        /**
         * Le prealable au retrait du moteur d'auth-service.
         *
         * <p>Tant qu'un de ces textes n'existe que la-bas, cesser de le composer
         * chez l'appelant produirait « modele introuvable » : un refus visible
         * dans le compte rendu, mais un code de verification que personne ne
         * recoit.
         */
        @Test
        @DisplayName("chaque TemplateType d'auth-service a un modèle ici")
        void chaqueTypeAUnModele() throws IOException {
            Set<String> cles = new HashSet<>(clesAvecDoublons());
            List<String> manquants = new ArrayList<>();

            for (var entree : CONTREPARTIE.entrySet()) {
                String modele = entree.getValue();
                // Le corps est le minimum : un modele sans corps ne rend rien.
                // Le sujet ne sert qu'au courriel, et une variante par canal est
                // optionnelle — le repli commun suffit.
                if (!cles.contains(modele + ".corps")) {
                    manquants.add(entree.getKey() + " → " + modele + ".corps");
                }
            }

            assertThat(manquants)
                    .withFailMessage("""
                            Ces modeles d'auth-service n'ont AUCUNE contrepartie \
                            ici : %s

                            Retirer le moteur d'auth-service avant de les porter \
                            produirait « modele introuvable » — un refus visible, \
                            mais un code de verification que personne ne \
                            recoit.""", manquants)
                    .isEmpty();
        }

        /**
         * Le SMS a bien une variante courte la ou il en existait une.
         *
         * <p>Un SMS tient en 160 caracteres ; le corps commun, ecrit pour le
         * courriel, les depasse largement. Sans variante, le message partirait
         * tronque — ou compterait pour plusieurs SMS, donc plusieurs credits.
         */
        @Test
        @DisplayName("les modèles qui existaient en SMS ont leur variante courte")
        void varianteSmsPresente() throws IOException {
            Set<String> cles = new HashSet<>(clesAvecDoublons());
            String canal = CanalDeNotification.SMS.name().toLowerCase();

            for (String modele : List.of("securite.code-de-verification",
                    "securite.reinitialisation-mot-de-passe")) {
                assertThat(cles)
                        .withFailMessage("""
                                %s n'a pas de variante %s, alors qu'auth-service \
                                en avait une. Le corps commun est ecrit pour le \
                                courriel : il depasse 160 caracteres, donc le SMS \
                                partirait tronque ou couterait plusieurs \
                                credits.""", modele, canal)
                        .contains(modele + "." + canal + ".corps");
            }
        }
    }
}
