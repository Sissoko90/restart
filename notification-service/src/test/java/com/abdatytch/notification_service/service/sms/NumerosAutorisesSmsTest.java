package com.abdatytch.notification_service.service.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Les numéros vers lesquels un SMS peut réellement partir")
class NumerosAutorisesSmsTest {

    @Nested
    @DisplayName("Liste vide : aucun filtrage")
    class ListeVide {

        /**
         * Le sens d'une liste vide est le point le plus important de cette
         * classe, parce que l'autre choix aurait ete silencieusement
         * destructeur : si une liste vide refusait tout, ajouter ce garde-fou
         * aurait coupe l'envoi de TOUS les codes de verification en production,
         * sans que personne ne change de configuration.
         */
        @Test
        @DisplayName("une liste vide laisse passer n'importe quel numéro")
        void listeVideLaissePasser() {
            assertThat(new NumerosAutorisesSms("").autorise("+22370000000")).isTrue();
            assertThat(new NumerosAutorisesSms(null).autorise("+22370000000")).isTrue();
            assertThat(new NumerosAutorisesSms("   ").autorise("+22370000000")).isTrue();
        }

        @Test
        @DisplayName("une liste faite uniquement d'entrées illisibles est vide, donc inactive")
        void entreesIllisiblesSeulement() {
            // Et non « une liste qui refuse tout ». Des entrees illisibles
            // signalent une configuration fautive, pas une volonte de tout
            // bloquer : les ecarter ramene au comportement par defaut, qui est
            // celui de la production.
            var liste = new NumerosAutorisesSms("pas-un-numero, 12, ???");

            assertThat(liste.inactif()).isTrue();
            assertThat(liste.taille()).isZero();
            assertThat(liste.autorise("+22370000000")).isTrue();
        }
    }

    @Nested
    @DisplayName("Liste renseignée")
    class ListeRenseignee {

        /**
         * La comparaison porte sur le numero NORMALISE, et c'est ce qui rend ce
         * garde-fou utilisable.
         *
         * <p>Comparer les chaines brutes aurait produit le pire des resultats :
         * un garde-fou qui refuse le numero meme qu'on lui a demande
         * d'autoriser, parce qu'il est ecrit autrement dans la configuration et
         * dans la base.
         */
        @Test
        @DisplayName("le même numéro écrit autrement est reconnu")
        void memeNumeroAutreEcriture() {
            var liste = new NumerosAutorisesSms("+223 70 00 00 00");

            assertThat(liste.autorise("+22370000000")).isTrue();
            assertThat(liste.autorise("0022370000000")).isTrue();
            assertThat(liste.autorise("22370000000")).isTrue();
            assertThat(liste.autorise("223-70-00-00-00")).isTrue();
        }

        @Test
        @DisplayName("un numéro absent de la liste est refusé")
        void numeroAbsentRefuse() {
            var liste = new NumerosAutorisesSms("+22370000000,+22371111111");

            assertThat(liste.taille()).isEqualTo(2);
            assertThat(liste.autorise("+22372222222")).isFalse();
        }

        /**
         * Un numero qu'on n'a pas su lire n'est jamais autorise.
         *
         * <p>Le contraire serait un trou : {@code normaliser} rend la chaine
         * vide pour une saisie inexploitable, et une comparaison negligente
         * aurait pu faire correspondre « vide » a « vide » si la liste
         * contenait elle aussi une entree illisible.
         */
        @Test
        @DisplayName("un numéro inexploitable est refusé, même si la liste contient une entrée illisible")
        void numeroInexploitableRefuse() {
            var liste = new NumerosAutorisesSms("+22370000000, pas-un-numero");

            assertThat(liste.taille()).isEqualTo(1);
            assertThat(liste.autorise("pas-un-numero")).isFalse();
            assertThat(liste.autorise("")).isFalse();
            assertThat(liste.autorise(null)).isFalse();
        }
    }
}
