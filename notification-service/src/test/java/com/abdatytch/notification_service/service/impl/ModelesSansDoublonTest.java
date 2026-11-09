package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
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
    @DisplayName("Les alertes de sécurité disent toutes la même conduite à tenir")
    class ConduiteATenirIdentique {

        /**
         * Les quatre alertes de securite emises par auth-service.
         *
         * <p>Elles partagent le meme paragraphe final — « Si vous reconnaissez
         * cette operation… » — recopie a l'identique dans chacune.
         */
        private static final List<String> ALERTES = List.of(
                "securite.mot-de-passe-modifie",
                "securite.adresse-modifiee",
                "securite.numero-modifie",
                "securite.nouvelle-connexion");

        /**
         * La duplication est assumee ; la derive ne l'est pas.
         *
         * <h4>Pourquoi le texte est recopie plutot que factorise</h4>
         *
         * Le moteur substitue en UN SEUL passage et ne re-parcourt jamais une
         * valeur — c'est ce qui ferme la porte a l'injection depuis un gabarit
         * surcharge en base. Un marqueur {@code {conduite}} resolu depuis un
         * autre modele est donc impossible par construction, et le faire passer
         * en variable remettrait ce texte chez l'emetteur, c'est-a-dire
         * exactement la ou cette passe le retire.
         *
         * <h4>Ce que ce test empeche</h4>
         *
         * Qu'une correction soit appliquee a trois alertes sur quatre. La
         * quatrieme continuerait a dire autre chose, et personne ne lirait les
         * quatre cote a cote pour s'en apercevoir : une alerte de securite n'est
         * relue que le jour ou elle sert.
         */
        @Test
        @DisplayName("les quatre corps portent exactement la même conduite à tenir")
        void memeConduiteATenir() throws IOException {
            var proprietes = new Properties();
            try (InputStream flux = new ClassPathResource(FICHIER).getInputStream()) {
                proprietes.load(new InputStreamReader(flux, StandardCharsets.UTF_8));
            }

            // Le fragment est deduit du PREMIER modele, et non recopie dans ce
            // test : une troisieme copie du texte, ici, serait a maintenir
            // d'accord avec les quatre autres. Le test compare les modeles entre
            // eux, il ne les compare pas a lui-meme.
            String reference = proprietes.getProperty(ALERTES.get(0) + ".corps");
            assertThat(reference)
                    .withFailMessage("%s.corps est absent : ce garde-fou ne "
                            + "verifie plus rien", ALERTES.get(0))
                    .isNotNull();

            int debut = reference.indexOf("\nSi vous reconnaissez");
            assertThat(debut)
                    .withFailMessage("""
                            Le paragraphe de conduite a tenir est introuvable dans \
                            %s.corps.

                            Soit il a ete reformule, soit il a disparu. Dans les \
                            deux cas ce test ne compare plus rien, et il faut \
                            mettre a jour le marqueur qu'il cherche.""",
                            ALERTES.get(0))
                    .isGreaterThanOrEqualTo(0);

            String conduite = reference.substring(debut);

            for (String alerte : ALERTES) {
                String corps = proprietes.getProperty(alerte + ".corps");

                assertThat(corps)
                        .withFailMessage("%s.corps est absent", alerte)
                        .isNotNull();
                assertThat(corps)
                        .withFailMessage("""
                                %s.corps ne finit pas par la meme conduite a \
                                tenir que les autres alertes.

                                Une correction a donc ete appliquee a certaines \
                                alertes et pas a celle-ci. Personne ne lit les \
                                quatre textes cote a cote : une alerte de \
                                securite n'est relue que le jour ou elle sert.""",
                                alerte)
                        .endsWith(conduite);
            }
        }

        /**
         * Et chacune porte bien le marqueur des circonstances.
         *
         * <p>Sans {@code {circonstances}}, l'alerte part sans dire quand ni d'ou
         * — or c'est precisement ce qui permet a la personne de repondre « non,
         * a cette heure-la je dormais ».
         */
        @Test
        @DisplayName("chaque corps place les circonstances")
        void circonstancesPresentes() throws IOException {
            var proprietes = new Properties();
            try (InputStream flux = new ClassPathResource(FICHIER).getInputStream()) {
                proprietes.load(new InputStreamReader(flux, StandardCharsets.UTF_8));
            }

            for (String alerte : ALERTES) {
                assertThat(proprietes.getProperty(alerte + ".corps"))
                        .withFailMessage("%s.corps ne place pas {circonstances} : "
                                + "l'alerte partirait sans dire quand ni d'ou", alerte)
                        .contains("{circonstances}");
            }
        }
    }

    @Nested
    @DisplayName("Tout modèle demandé par auth-service existe ici")
    class CeQuAuthServiceDemande {

        /**
         * Les six modeles qu'auth-service nomme, et le canal le plus contraint
         * par lequel il les demande.
         *
         * <h4>Pourquoi la liste est ecrite a la main</h4>
         *
         * Lire {@code AppConstants} d'auth-service par reflexion demanderait une
         * dependance de ce module vers celui-la — exactement ce que le decoupage
         * en services interdit.
         *
         * <h4>Ce que cette liste remplace</h4>
         *
         * Elle enumerait les cinq {@code TemplateType} d'auth-service, dont
         * l'enum n'existe plus : son moteur de modeles a ete retire dans cette
         * passe. Le test etait le « prealable au retrait », et ce prealable est
         * consomme.
         *
         * <p>Son remplacant verifie l'inverse, et c'est plus utile : non plus
         * « les anciens types ont une contrepartie », mais « tout ce que
         * l'emetteur demande existe ». Noter que les cinq anciens types se
         * ramenaient a TROIS modeles, parce que {@code SMS_VERIFICATION} et
         * {@code EMAIL_VERIFICATION} nommaient le meme message sur deux canaux.
         */
        private static final Map<String, CanalDeNotification> DEMANDES = Map.ofEntries(
                // Codes, demandes sur les deux canaux.
                Map.entry("securite.code-de-verification", CanalDeNotification.SMS),
                Map.entry("securite.reinitialisation-mot-de-passe", CanalDeNotification.SMS),
                // Alertes de securite. Le repli entre canaux depend de
                // l'evenement, donc ces trois-la sont demandees par SMS comme
                // par courriel.
                Map.entry("securite.mot-de-passe-modifie", CanalDeNotification.SMS),
                Map.entry("securite.adresse-modifiee", CanalDeNotification.SMS),
                Map.entry("securite.numero-modifie", CanalDeNotification.SMS),
                // Jamais par SMS : une connexion est frequente, et un SMS par
                // connexion couterait un credit pour un canal que la personne
                // n'a pas choisi.
                Map.entry("securite.nouvelle-connexion", CanalDeNotification.COURRIEL));

        @Test
        @DisplayName("chaque modèle demandé a un corps")
        void chaqueModeleAUnCorps() throws IOException {
            Set<String> cles = new HashSet<>(clesAvecDoublons());
            List<String> manquants = new ArrayList<>();

            for (String modele : DEMANDES.keySet()) {
                // Le corps est le minimum : un modele sans corps ne rend rien.
                if (!cles.contains(modele + ".corps")) {
                    manquants.add(modele + ".corps");
                }
            }

            assertThat(manquants)
                    .withFailMessage("""
                            auth-service demande ces modeles, et ils n'existent \
                            pas ici : %s

                            Consequence : /internal/message repond \
                            « modele introuvable » — un refus visible dans les \
                            journaux, mais un code de verification ou une alerte \
                            de securite que personne ne recoit.""", manquants)
                    .isEmpty();
        }

        /**
         * Les modeles demandes PAR SMS ont leur variante courte.
         *
         * <h4>Ce controle est devenu obligatoire, et il etait facultatif</h4>
         *
         * Le moteur repliait jusqu'ici {@code <modele>.sms.corps} sur
         * {@code <modele>.corps} : une variante manquante faisait partir le
         * corps du courriel par SMS, tronque ou facture en plusieurs credits.
         *
         * <p>Il ne replie plus pour le SMS — voir
         * {@code MoteurDeModeles.CANAUX_SANS_REPLI}. Une variante manquante
         * refuse donc l'envoi, ce qui est plus sain mais rend ce controle
         * indispensable : sans lui, une alerte de securite cesserait
         * silencieusement de partir par SMS.
         */
        @Test
        @DisplayName("tout modèle demandé par SMS a sa variante courte")
        void varianteSmsPresente() throws IOException {
            Set<String> cles = new HashSet<>(clesAvecDoublons());
            String canal = CanalDeNotification.SMS.name().toLowerCase();

            List<String> sansVariante = DEMANDES.entrySet().stream()
                    .filter(entree -> entree.getValue() == CanalDeNotification.SMS)
                    .map(Map.Entry::getKey)
                    .filter(modele -> !cles.contains(modele + "." + canal + ".corps"))
                    .toList();

            assertThat(sansVariante)
                    .withFailMessage("""
                            Ces modeles sont demandes par SMS et n'ont pas de \
                            variante courte : %s

                            Le moteur ne replie PAS le SMS sur le corps commun, \
                            donc l'envoi serait refuse — une alerte de securite \
                            cesserait de partir par SMS, en silence.""",
                            sansVariante)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Ce que les textes de sécurité contiennent, et ce qu'ils ne contiennent pas")
    class ContenuDesAlertes {

        /**
         * Ces assertions viennent d'{@code AlerteDeSecuriteServiceImplTest}.
         *
         * <p>Elles y portaient sur le corps compose par auth-service. Le corps
         * est compose ici depuis cette passe, donc le controle suit le texte :
         * le laisser la-bas aurait verifie un texte que ce module peut changer
         * sans que ce test s'en apercoive.
         */
        private static Properties chargees() throws IOException {
            var proprietes = new Properties();
            try (InputStream flux = new ClassPathResource(FICHIER).getInputStream()) {
                proprietes.load(new InputStreamReader(flux, StandardCharsets.UTF_8));
            }
            return proprietes;
        }

        private static String corps(String modele) throws IOException {
            String texte = chargees().getProperty(modele + ".corps");
            assertThat(texte)
                    .withFailMessage("%s.corps est absent : ce controle ne "
                            + "verifie plus rien", modele)
                    .isNotNull();
            return texte;
        }

        /**
         * L'alerte d'adresse dit qu'elle part a l'ANCIENNE adresse.
         *
         * <p>Sans cette precision, la personne lit « votre adresse a ete
         * modifiee » sur une adresse qui, de son point de vue, est toujours la
         * bonne — et ne comprend pas qu'elle vient de perdre l'acces a son
         * compte.
         */
        @Test
        @DisplayName("l'alerte d'adresse dit qu'elle part à l'ancienne adresse")
        void adresseNommeLAncienneAdresse() throws IOException {
            assertThat(corps("securite.adresse-modifiee"))
                    .containsIgnoringCase("ancienne adresse");
        }

        /**
         * L'alerte de numero nomme la CONSEQUENCE, qui n'est pas evidente.
         *
         * <p>« Votre numero a change » ne dit pas ce qui compte. Le numero est
         * le canal de la reinitialisation du mot de passe : qui le remplace
         * prend le controle du compte entier sans connaitre le mot de passe.
         */
        @Test
        @DisplayName("l'alerte de numéro nomme la perte de la récupération par SMS")
        void numeroNommeLaConsequence() throws IOException {
            assertThat(corps("securite.numero-modifie"))
                    .containsIgnoringCase("ancien numero")
                    .containsIgnoringCase("recuperation");
        }

        /**
         * AUCUN lien d'action, aucun code, dans aucun texte de securite.
         *
         * <p>Un courriel d'alerte est lu par qui a acces a la boite — et c'est
         * precisement ce dont on n'est pas sur a l'instant ou on l'envoie. Un
         * lien « ce n'etait pas moi, reinitialiser ici » offrirait a l'attaquant
         * un chemin de reprise en main.
         *
         * <p>Le controle porte sur TOUTES les cles {@code securite.*} — sujets
         * et variantes SMS comprises — et non sur les quatre corps d'alerte. Un
         * lien dans un SMS est aussi cliquable, et un sujet s'affiche sur un
         * ecran verrouille.
         */
        @Test
        @DisplayName("aucun texte de sécurité ne porte de lien ni de code")
        void aucunLienNiCode() throws IOException {
            Properties proprietes = chargees();
            List<String> fautifs = new ArrayList<>();

            for (String cle : proprietes.stringPropertyNames()) {
                if (!cle.startsWith("securite.")) {
                    continue;
                }
                String valeur = proprietes.getProperty(cle);
                if (valeur.contains("http://") || valeur.contains("https://")) {
                    fautifs.add(cle + " (lien)");
                }
                // Six chiffres consecutifs : la forme d'un code a usage unique.
                // Les modeles de code portent le MARQUEUR {code}, jamais une
                // valeur — un litteral a six chiffres dans un gabarit serait
                // soit un exemple laisse la, soit une fuite.
                if (valeur.matches("(?s).*\\b\\d{6}\\b.*")) {
                    fautifs.add(cle + " (six chiffres)");
                }
            }

            // Le garde-fou se garde lui-meme : sans cle lue, il serait vert.
            assertThat(proprietes.stringPropertyNames())
                    .withFailMessage("aucune cle securite.* lue : ce controle ne "
                            + "verifie plus rien")
                    .anyMatch(cle -> cle.startsWith("securite."));

            assertThat(fautifs)
                    .withFailMessage("""
                            Ces textes de securite portent un lien ou ce qui \
                            ressemble a un code : %s

                            Un courriel d'alerte est lu par qui a acces a la \
                            boite, et c'est precisement ce dont on n'est pas sur \
                            au moment de l'envoyer.""", fautifs)
                    .isEmpty();
        }
    }
}
