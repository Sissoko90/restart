package com.abdatytch.notification_service.service.impl;

import com.abdatytch.commons.notification.CanalDeNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quelles cles de surcharge sont acceptees, et pourquoi les autres sont refusees.
 *
 * <h2>Ce que ce controle empeche</h2>
 *
 * Le moteur n'interroge que {@code <modele>[.<canal>].sujet} et
 * {@code ...corps}. Une surcharge posee sous toute autre forme serait acceptee
 * par la table, relue au rechargement, listee par l'API — et jamais consultee.
 * L'exploitant verrait sa correction « enregistree » et sans effet sur les
 * messages envoyes.
 *
 * <p>C'est le motif dominant de ce depot : du travail present et jamais
 * atteint. Le controle est pose a l'ECRITURE, seul endroit ou il peut encore
 * dire non.
 */
@DisplayName("La forme d'une clé de modèle")
class CleDeModeleTest {

    @Nested
    @DisplayName("Les clés que le moteur lit")
    class Acceptees {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "projet.cree.corps",
                "projet.cree.sujet",
                "securite.code-de-verification.corps",
                "securite.reinitialisation-mot-de-passe.sujet",
                // Nom de modele a un seul segment : rien ne l'interdit.
                "bienvenue.corps",
                // Chiffres admis : un modele versionne reste nommable.
                "offre2026.corps",
                // La forme normale d'une variante de canal — et la cle qu'une
                // premiere version de ce test refusait a tort, croyant y voir
                // une ambiguite. Le moteur y lit le modele « securite » sur le
                // canal « sms », sans equivoque.
                "securite.sms.corps"
        })
        @DisplayName("une clé bien formée est acceptée")
        void bienFormee(String cle) {
            assertThat(CleDeModele.motifDInvalidite(cle))
                    .withFailMessage("« %s » est refusee alors que le moteur la "
                            + "lirait : %s", cle, CleDeModele.motifDInvalidite(cle))
                    .isNull();
        }

        /**
         * Chaque canal de l'enumeration est surchargeable.
         *
         * <p>Derive de {@link CanalDeNotification} et non d'une liste recopiee :
         * un canal ajoute demain devient surchargeable sans qu'il faille y
         * penser. Sans ce test, une liste oubliee se verrait en « cle invalide »
         * sur un canal pourtant implemente.
         */
        @ParameterizedTest(name = "{0}")
        @EnumSource(CanalDeNotification.class)
        @DisplayName("tout canal de l'énumération peut porter une variante")
        void toutCanalEstSurchargeable(CanalDeNotification canal) {
            String cle = "projet.cree." + canal.name().toLowerCase(Locale.ROOT) + ".corps";

            assertThat(CleDeModele.valide(cle))
                    .withFailMessage("le canal %s n'est pas reconnu dans une cle "
                            + "(« %s ») alors qu'il est implemente : %s",
                            canal, cle, CleDeModele.motifDInvalidite(cle))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Les clés que le moteur ne lirait jamais")
    class Refusees {

        /**
         * Le cas central : une terminaison que le moteur n'interroge pas.
         *
         * <p>{@code projet.cree} tout court, ou {@code projet.cree.titre} :
         * enregistrables, relues, listees, et sans aucun effet.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "projet.cree",
                "projet.cree.titre",
                "projet.cree.body",
                "projet.cree.corps.extra"
        })
        @DisplayName("une terminaison autre que .sujet ou .corps est refusée")
        void terminaisonInconnue(String cle) {
            assertThat(CleDeModele.motifDInvalidite(cle))
                    .withFailMessage("« %s » est acceptee : elle serait "
                            + "enregistree et jamais consultee", cle)
                    .isNotNull();
        }

        /**
         * Les majuscules, parce qu'une cle est comparee litteralement.
         *
         * <p>{@code Projet.cree.corps} et {@code projet.cree.corps} seraient
         * deux surcharges distinctes, dont une seule servirait — et c'est
         * exactement le genre d'ecart qu'on ne voit pas en relisant une liste.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"Projet.cree.corps", "projet.Cree.corps", "PROJET.cree.corps"})
        @DisplayName("une majuscule dans le nom du modèle est refusée")
        void majusculeRefusee(String cle) {
            assertThat(CleDeModele.motifDInvalidite(cle))
                    .withFailMessage("« %s » est acceptee : elle cohabiterait "
                            + "avec sa variante en minuscules", cle)
                    .isNotNull();
        }

        /**
         * Un nom de canal ne peut pas nommer un modele.
         *
         * <h4>Ce que la premiere version de ce test affirmait, et pourquoi
         * c'etait faux</h4>
         *
         * Elle exigeait le refus de {@code securite.sms.corps}, au motif que la
         * cle serait ambigue. Elle ne l'est pas : le moteur essaie toujours la
         * cle du canal d'abord, donc il y lit le modele {@code securite} sur le
         * canal {@code sms}, sans equivoque. Cette cle doit etre ACCEPTEE — elle
         * est meme la forme normale d'une variante SMS.
         *
         * <p>Ce qui n'est pas exprimable, c'est un modele <b>nomme</b>
         * {@code securite.sms}. Et aucune validation ne peut le detecter,
         * puisque les deux intentions produisent la meme chaine : il n'y a rien
         * a refuser dans la cle.
         *
         * <p>La regle tenable est donc en amont, sur le nommage : un nom de
         * canal ne sert pas de segment de modele. C'est cela que ce test fixe.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                // Le canal occupe la place du nom de modele.
                "sms.corps",
                "courriel.sujet",
                // Et plus loin dans le nom : « sms » reste un segment de modele
                // ici, puisque l'avant-dernier segment n'est pas un canal.
                "sms.quelque-chose.corps",
                "push.alerte.sujet"
        })
        @DisplayName("un nom de canal employé comme segment de modèle est refusé")
        void canalCommeNomDeModele(String cle) {
            assertThat(CleDeModele.motifDInvalidite(cle))
                    .withFailMessage("« %s » est acceptee : un nom de canal y "
                            + "sert de nom de modele, et la cle se lirait "
                            + "autrement qu'elle ne s'ecrit", cle)
                    .isNotNull()
                    .contains("nom de canal");
        }

        @Test
        @DisplayName("une clé sans aucun segment de modèle est refusée")
        void sansNomDeModele() {
            assertThat(CleDeModele.motifDInvalidite("corps")).isNotNull();
            assertThat(CleDeModele.motifDInvalidite(".corps")).isNotNull();
        }

        @Test
        @DisplayName("une clé vide ou absente est refusée")
        void videOuAbsente() {
            assertThat(CleDeModele.motifDInvalidite(null)).isNotNull();
            assertThat(CleDeModele.motifDInvalidite("")).isNotNull();
            assertThat(CleDeModele.motifDInvalidite("   ")).isNotNull();
        }

        /**
         * La borne de la colonne, et non une borne inventee.
         *
         * <p>Sans ce controle, une cle trop longue serait refusee par
         * PostgreSQL : un 500 au lieu d'un 400 explicite, et une trace de
         * contrainte violee dans les journaux.
         */
        @Test
        @DisplayName("une clé plus longue que la colonne est refusée avant la base")
        void tropLongue() {
            String trop = "a".repeat(CleDeModele.LONGUEUR_MAXIMALE) + ".corps";

            assertThat(CleDeModele.motifDInvalidite(trop))
                    .isNotNull()
                    .contains(String.valueOf(CleDeModele.LONGUEUR_MAXIMALE));
        }
    }

    @Nested
    @DisplayName("Le motif du refus")
    class Motif {

        /**
         * Un motif, et non « cle invalide ».
         *
         * <p>Le texte part dans la reponse HTTP d'une API d'administration : il
         * doit dire QUOI corriger, sans contexte. Plusieurs controles differents
         * derriere un seul message obligeraient a deviner lequel a echoue.
         */
        @Test
        @DisplayName("il nomme ce qui est en cause")
        void nommeLaCause() {
            assertThat(CleDeModele.motifDInvalidite("projet.cree.titre"))
                    .contains("titre")
                    .contains(".sujet")
                    .contains(".corps");

            assertThat(CleDeModele.motifDInvalidite("Projet.cree.corps"))
                    .contains("Projet");
        }

        @Test
        @DisplayName("il est nul exactement quand la clé est valide")
        void nulQuandValide() {
            assertThat(CleDeModele.valide("projet.cree.corps")).isTrue();
            assertThat(CleDeModele.motifDInvalidite("projet.cree.corps")).isNull();

            assertThat(CleDeModele.valide("projet.cree")).isFalse();
            assertThat(CleDeModele.motifDInvalidite("projet.cree")).isNotNull();
        }
    }
}
