package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import com.abdatytch.notification_service.entities.SurchargeDeModele;
import com.abdatytch.notification_service.repositories.SurchargeDeModeleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Les deux couches du moteur : le fichier, puis la base.
 *
 * <h2>La question que ce test tranche</h2>
 *
 * Quand une surcharge et une variante de canal du fichier sont toutes deux
 * candidates, laquelle gagne ? Deux ordres etaient possibles, et ils ne donnent
 * pas le meme message :
 *
 * <ul>
 *   <li><b>specificite d'abord</b> : pour chaque cle candidate — celle du canal,
 *       puis celle du repli — on consulte la surcharge avant le socle ;</li>
 *   <li><b>couche d'abord</b> : toutes les surcharges, puis tout le socle.</li>
 * </ul>
 *
 * <p>Le fichier porte {@code securite.code-de-verification.sms.corps}, une
 * variante COURTE ecrite pour tenir en 160 caracteres. Corriger le seul texte
 * generique — ecrit pour le courriel, donc long — ferait, dans le second ordre,
 * partir ce texte long par SMS : tronque, ou facture en plusieurs credits. Sans
 * que rien ne le signale.
 *
 * <p>Le premier ordre est donc retenu, et ce test le fixe.
 */
@DisplayName("Le moteur de modèles et ses surcharges")
class MoteurDeModelesSurchargeTest {

    /** Les cles reelles du fichier, lues pour ecrire ce test. */
    private static final String MODELE = "securite.code-de-verification";
    private static final String CLE_GENERIQUE = MODELE + ".corps";
    private static final String CLE_SMS = MODELE + ".sms.corps";

    private static final String AUTEUR = "11111111-1111-1111-1111-111111111111";

    private final SurchargeDeModeleRepository depot = mock(SurchargeDeModeleRepository.class);

    /**
     * Un moteur charge sur le vrai fichier, avec les surcharges donnees.
     *
     * <p>Le vrai fichier et non un bouchon : c'est son contenu — la coexistence
     * d'un texte generique et d'une variante SMS — qui rend la question de
     * l'ordre observable. Un socle invente aurait pu etre ecrit pour donner
     * raison a l'implementation.
     */
    private MoteurDeModeles moteur(SurchargeDeModele... surcharges) {
        when(depot.findAll()).thenReturn(Flux.just(surcharges));
        var moteur = new MoteurDeModeles(
                new ClassPathResource("modeles-notification.properties"), depot);
        moteur.rechargerPeriodiquement();
        return moteur;
    }

    private static SurchargeDeModele surcharge(String cle, String valeur) {
        return SurchargeDeModele.de(cle, valeur, AUTEUR);
    }

    /**
     * Le corps resolu pour un canal, SANS substitution.
     *
     * <p>Variables vides, volontairement : {@code substituer} rend alors le
     * gabarit tel quel, et ce qui revient est donc exactement la valeur choisie
     * par la resolution. Les assertions peuvent la comparer directement a
     * {@code valeurDuSocle}.
     *
     * <p>Premiere version de ce test : des variables etaient fournies, et les
     * assertions comparaient un texte SUBSTITUE au gabarit BRUT du socle. Les
     * six tests echouaient alors que le moteur resolvait juste — le defaut
     * etait dans la mesure, pas dans la chose mesuree. La substitution a son
     * propre test plus bas.
     */
    private static String corps(MoteurDeModeles moteur, CanalDeNotification canal) {
        return moteur.rendre(MODELE, canal, Map.of())
                .orElseThrow(() -> new AssertionError(
                        "aucun contenu rendu pour " + canal + " : le modele "
                                + MODELE + " a disparu du socle"))
                .corps();
    }

    @Nested
    @DisplayName("Une table vide")
    class TableVide {

        /**
         * La propriete que revendique la conception : zero surcharge, zero
         * changement.
         *
         * <p>C'est ce qui a permis de ne PAS ecrire de migration recopiant les
         * modeles existants — une migration qui, mal faite, aurait laisse des
         * notifications sans texte.
         */
        @Test
        @DisplayName("se comporte exactement comme le fichier seul")
        void commeLeFichierSeul() {
            var sansSurcharge = moteur();

            assertThat(sansSurcharge.surchargesEnVigueur()).isEmpty();
            assertThat(corps(sansSurcharge, CanalDeNotification.SMS))
                    .isEqualTo(sansSurcharge.valeurDuSocle(CLE_SMS));
            assertThat(corps(sansSurcharge, CanalDeNotification.COURRIEL))
                    .isEqualTo(sansSurcharge.valeurDuSocle(CLE_GENERIQUE));
        }

        @Test
        @DisplayName("le socle porte bien les deux clés dont ce test a besoin")
        void leSocleEstBienCeQuOnCroit() {
            var sansSurcharge = moteur();

            // Le garde-fou se garde lui-meme. Si l'une de ces cles disparaissait
            // du fichier, les tests d'ordre ci-dessous deviendraient vacuement
            // verts : ils compareraient deux replis identiques.
            assertThat(sansSurcharge.valeurDuSocle(CLE_SMS))
                    .withFailMessage("%s a disparu du fichier : les tests "
                            + "d'ordre de resolution ne verifient plus rien", CLE_SMS)
                    .isNotNull();
            assertThat(sansSurcharge.valeurDuSocle(CLE_GENERIQUE))
                    .withFailMessage("%s a disparu du fichier", CLE_GENERIQUE)
                    .isNotNull();
            assertThat(sansSurcharge.valeurDuSocle(CLE_SMS))
                    .withFailMessage("la variante SMS et le texte generique sont "
                            + "devenus identiques : ce test ne distingue plus "
                            + "les deux ordres de resolution")
                    .isNotEqualTo(sansSurcharge.valeurDuSocle(CLE_GENERIQUE));
        }
    }

    @Nested
    @DisplayName("L'ordre de résolution")
    class OrdreDeResolution {

        /**
         * LE test de cette passe.
         *
         * <p>Une surcharge du texte generique ne doit PAS passer devant la
         * variante SMS du fichier. L'ordre inverse enverrait un texte de
         * courriel par SMS.
         */
        @Test
        @DisplayName("une surcharge générique ne remplace pas la variante SMS du fichier")
        void surchargeGeneriqueNeTouchePasLaVarianteSms() {
            var avecGenerique = moteur(surcharge(CLE_GENERIQUE,
                    "Texte long, ecrit pour le courriel : {code}"));

            assertThat(corps(avecGenerique, CanalDeNotification.SMS))
                    .withFailMessage("""
                            La surcharge generique est passee devant la variante \
                            SMS du fichier.

                            Consequence reelle : un texte ecrit pour le courriel \
                            part par SMS, donc tronque a 160 caracteres ou \
                            facture en plusieurs credits — et rien ne le \
                            signale.

                            La regle est : pour chaque cle candidate, surcharge \
                            puis socle. Pas : toutes les surcharges, puis tout \
                            le socle.""")
                    .isEqualTo(avecGenerique.valeurDuSocle(CLE_SMS));
        }

        /**
         * Et reciproquement : la surcharge generique s'applique bien la ou le
         * fichier n'a pas de variante.
         *
         * <p>Sans cette seconde assertion, une implementation qui ignorerait
         * purement et simplement les surcharges generiques passerait le test
         * precedent.
         */
        @Test
        @DisplayName("une surcharge générique s'applique aux canaux sans variante")
        void surchargeGeneriqueSAppliqueAuxAutresCanaux() {
            String nouveau = "Texte corrige a chaud : {code}";
            var avecGenerique = moteur(surcharge(CLE_GENERIQUE, nouveau));

            assertThat(corps(avecGenerique, CanalDeNotification.COURRIEL))
                    .withFailMessage("la surcharge generique n'a aucun effet : "
                            + "la couche de base est inerte")
                    .isEqualTo(nouveau);
        }

        @Test
        @DisplayName("une surcharge de la clé du canal remplace bien la variante du fichier")
        void surchargeDuCanalGagne() {
            String court = "Code {code}, {minutes} min.";
            var avecSms = moteur(surcharge(CLE_SMS, court));

            assertThat(corps(avecSms, CanalDeNotification.SMS)).isEqualTo(court);
            // Le courriel, lui, n'est pas touche : une surcharge de canal ne
            // deborde pas sur les autres.
            assertThat(corps(avecSms, CanalDeNotification.COURRIEL))
                    .isEqualTo(avecSms.valeurDuSocle(CLE_GENERIQUE));
        }

        /**
         * Une cle absente du fichier devient rendable.
         *
         * <p>C'est ce qui permet d'ajouter une variante de canal sans livraison
         * — par exemple donner au SMS un texte court pour un modele qui n'en
         * avait pas.
         */
        @Test
        @DisplayName("une surcharge peut créer une variante que le fichier n'a pas")
        void surchargePeutCreerUneVariante() {
            String corpsSms = "Bienvenue.";
            var moteur = moteur(surcharge("compte.bienvenue.sms.corps", corpsSms));

            assertThat(moteur.valeurDuSocle("compte.bienvenue.sms.corps"))
                    .withFailMessage("le fichier porte desormais cette cle : "
                            + "ce test ne mesure plus une CREATION")
                    .isNull();

            var rendu = moteur.rendre("compte.bienvenue", CanalDeNotification.SMS,
                    Map.of("firstName", "Ada"));
            assertThat(rendu).isPresent();
            assertThat(rendu.get().corps()).isEqualTo(corpsSms);
        }
    }

    @Nested
    @DisplayName("Le SMS ne replie pas sur le texte commun")
    class SmsSansRepli {

        /**
         * Un garde-fou de DEPENSE, et la correction d'une affirmation fausse.
         *
         * <p>Le fichier portait, sur {@code compte.bienvenue}, le commentaire
         * « un canal sans modele produit un refus modele introuvable, mieux
         * qu'un SMS de bienvenue qui coute un credit ». C'etait faux : le moteur
         * repliait sur le corps commun, donc le SMS partait — avec un texte
         * ecrit pour le courriel, tronque par l'operateur ou facture en
         * plusieurs messages.
         */
        @Test
        @DisplayName("un modèle sans variante SMS refuse au lieu d'envoyer le texte du courriel")
        void sansVarianteSmsLeModeleEstIntrouvable() {
            var moteur = moteur();

            // Le prealable : ce modele a bien un corps commun, et pas de
            // variante SMS. Sans ces deux faits, le test serait vacuement vert.
            assertThat(moteur.valeurDuSocle("compte.bienvenue.corps"))
                    .withFailMessage("compte.bienvenue.corps a disparu : ce test "
                            + "ne mesure plus un repli")
                    .isNotNull();
            assertThat(moteur.valeurDuSocle("compte.bienvenue.sms.corps"))
                    .withFailMessage("compte.bienvenue a desormais une variante "
                            + "SMS : ce test ne mesure plus l'absence de repli")
                    .isNull();

            assertThat(moteur.rendre("compte.bienvenue", CanalDeNotification.SMS,
                    Map.of("firstName", "Ada")))
                    .withFailMessage("""
                            Le SMS a replie sur le corps commun.

                            Ce corps est ecrit pour le courriel : il depasse 160 \
                            caracteres, donc le message partirait tronque ou \
                            facture en plusieurs credits — et rien ne le \
                            signalerait.""")
                    .isEmpty();
        }

        /**
         * Et les autres canaux replient toujours.
         *
         * <p>Sans cette seconde assertion, une implementation qui aurait
         * supprime le repli pour TOUS les canaux passerait le test precedent —
         * et tout modele sans variante par canal cesserait d'etre rendu.
         */
        @Test
        @DisplayName("le courriel, lui, replie bien sur le texte commun")
        void leCourrielReplie() {
            var moteur = moteur();

            assertThat(moteur.valeurDuSocle("compte.bienvenue.courriel.corps"))
                    .withFailMessage("compte.bienvenue a desormais une variante "
                            + "courriel : ce test ne mesure plus un repli")
                    .isNull();

            // Variables VIDES : la substitution est alors un passage a vide, et
            // ce qui revient est exactement la valeur choisie par la
            // resolution. Avec {firstName} fourni, ce test comparait un texte
            // substitue au gabarit brut — la meme erreur de mesure que celle
            // deja corrigee dans `corps(...)` plus haut, refaite ici.
            var rendu = moteur.rendre("compte.bienvenue", CanalDeNotification.COURRIEL,
                    Map.of());

            assertThat(rendu)
                    .withFailMessage("le courriel ne replie plus : tout modele "
                            + "sans variante par canal cesserait d'etre rendu")
                    .isPresent();
            assertThat(rendu.get().corps())
                    .isEqualTo(moteur.valeurDuSocle("compte.bienvenue.corps"));
        }

        /*
         * Et qu'une surcharge debloque le SMS est deja prouve par
         * `surchargePeutCreerUneVariante` ci-dessus, qui pose precisement
         * compte.bienvenue.sms.corps et verifie que le rendu aboutit. C'est ce
         * qui rend ce refus acceptable plutot que bloquant : si l'on veut
         * vraiment ce message par SMS, PUT /admin/modeles suffit — sans
         * livraison.
         *
         * Pas de second test identique ici : deux tests qui mesurent la meme
         * chose finissent par ne plus etre modifies ensemble.
         */
    }

    @Nested
    @DisplayName("Le rechargement")
    class Rechargement {

        /**
         * Une panne de base ne vide PAS les surcharges.
         *
         * <p>Les remplacer par du vide ferait revenir en silence aux textes du
         * fichier, donc annulerait une correction d'urgence au moment ou la base
         * hoquette, puis la retablirait au passage suivant. Un texte qui oscille
         * est pire qu'un texte perime.
         */
        @Test
        @DisplayName("une panne de base conserve les surcharges précédentes")
        void panneConserveLInstantane() {
            String corrige = "Texte corrige : {code}";
            when(depot.findAll())
                    .thenReturn(Flux.just(surcharge(CLE_GENERIQUE, corrige)))
                    .thenReturn(Flux.error(new IllegalStateException("base injoignable")));

            var moteur = new MoteurDeModeles(
                    new ClassPathResource("modeles-notification.properties"), depot);

            moteur.rechargerPeriodiquement();
            assertThat(corps(moteur, CanalDeNotification.COURRIEL)).isEqualTo(corrige);

            // Second passage : la base tombe.
            moteur.rechargerPeriodiquement();

            assertThat(corps(moteur, CanalDeNotification.COURRIEL))
                    .withFailMessage("""
                            La panne a vide les surcharges : le texte du fichier \
                            est revenu.

                            Une correction d'urgence serait donc annulee par un \
                            hoquet de base, puis retablie au rechargement \
                            suivant.""")
                    .isEqualTo(corrige);
        }

        /**
         * L'instantane est remplace D'UN BLOC.
         *
         * <p>Une surcharge retiree de la table doit disparaitre de l'instantane,
         * et non y rester parce qu'on aurait fusionne au lieu de remplacer. Sans
         * cela, {@code DELETE /admin/modeles} repondrait « retiree » sans rien
         * changer au rendu jusqu'au redemarrage.
         */
        @Test
        @DisplayName("une surcharge retirée de la table disparaît du rendu")
        void retraitEffectif() {
            when(depot.findAll())
                    .thenReturn(Flux.just(surcharge(CLE_GENERIQUE, "provisoire")))
                    .thenReturn(Flux.empty());

            var moteur = new MoteurDeModeles(
                    new ClassPathResource("modeles-notification.properties"), depot);

            moteur.rechargerPeriodiquement();
            assertThat(corps(moteur, CanalDeNotification.COURRIEL)).isEqualTo("provisoire");

            moteur.rechargerPeriodiquement();

            assertThat(moteur.surchargesEnVigueur())
                    .withFailMessage("l'instantane a ete fusionne au lieu d'etre "
                            + "remplace : une surcharge retiree reste en vigueur")
                    .isEmpty();
            assertThat(corps(moteur, CanalDeNotification.COURRIEL))
                    .isEqualTo(moteur.valeurDuSocle(CLE_GENERIQUE));
        }

        /**
         * Avant tout rechargement, le socle sert seul.
         *
         * <p>C'est la degradation voulue au demarrage : le service rend des
         * notifications sans attendre la base. L'inverse ferait dependre le
         * demarrage d'une dependance, ce que la politique du depot refuse.
         */
        @Test
        @DisplayName("avant le premier rechargement, le fichier sert seul")
        void avantLePremierRechargement() {
            when(depot.findAll()).thenReturn(Flux.just(surcharge(CLE_GENERIQUE, "jamais lu")));

            var neuf = new MoteurDeModeles(
                    new ClassPathResource("modeles-notification.properties"), depot);

            assertThat(neuf.surchargesEnVigueur()).isEmpty();
            assertThat(corps(neuf, CanalDeNotification.COURRIEL))
                    .isEqualTo(neuf.valeurDuSocle(CLE_GENERIQUE));
        }
    }

    @Nested
    @DisplayName("La substitution de variables")
    class Substitution {

        /**
         * Un gabarit surcharge ne gagne aucun pouvoir.
         *
         * <p>La substitution reste litterale et en un seul passage. Cela compte
         * davantage depuis que les textes viennent d'une table : un gabarit du
         * fichier passe en revue de code, un gabarit surcharge non.
         */
        @Test
        @DisplayName("une surcharge ne permet pas de faire lire une variable non fournie")
        void aucuneRecursionDepuisUneSurcharge() {
            var moteur = moteur(surcharge(CLE_GENERIQUE, "Voici {injection}"));

            var rendu = moteur.rendre(MODELE, CanalDeNotification.COURRIEL,
                    Map.of("injection", "{code}", "code", "654321"));

            assertThat(rendu).isPresent();
            assertThat(rendu.get().corps())
                    .withFailMessage("la valeur substituee a ete re-parcourue : "
                            + "un emetteur peut faire lire une variable qu'il "
                            + "n'a pas fournie")
                    .isEqualTo("Voici {code}")
                    .doesNotContain("654321");
        }
    }
}
