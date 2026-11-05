package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La règle de révocation globale, gardée là où elle vit.
 *
 * <h2>Le défaut gardé ici</h2>
 *
 * La clé Redis {@code dev:revoked:<sub>} porte l'<b>instant</b> de la
 * révocation. Ses <b>deux</b> lecteurs — la validation de l'autorité JWT et le
 * vérificateur de la passerelle — faisaient un simple {@code hasKey} et
 * ignoraient cette valeur.
 *
 * <p>Conséquence : une révocation globale aurait verrouillé le porteur pendant
 * les sept jours de vie de la clé, <b>reconnexion comprise</b>. Chaque jeton
 * neuf était rejeté. L'horodatage était écrit pour éviter exactement cela, et
 * n'était relu nulle part.
 *
 * <p>Le test le plus important est donc celui du jeton postérieur : il garde la
 * moitié qu'on oublie — qu'un jeton émis APRÈS la révocation reste valide.
 * Sans lui, un correctif ultérieur qui refermerait tout passerait pour correct.
 *
 * <h2>Pourquoi ce test est ici et non dans les deux modules</h2>
 *
 * Parce que la règle est ici. Deux lecteurs d'une même clé qui n'appliquent pas
 * la même règle, c'est exactement ce que ce module partagé existe pour
 * empêcher — et le dépôt l'a déjà payé sur les chemins publics, la lecture
 * d'adresse IP et la lecture des rôles.
 */
@DisplayName("RevocationGlobale - un jeton posterieur a la revocation reste valide")
class RevocationGlobaleTest {

    private static final Instant REVOQUE_LE = Instant.parse("2026-11-05T10:00:00Z");
    private static final String HORODATAGE = String.valueOf(REVOQUE_LE.toEpochMilli());

    // ==================================================================
    @Nested
    @DisplayName("Aucune revocation enregistree")
    class AucuneRevocation {

        /**
         * Clé absente : on OUVRE, et c'est le seul cas où l'on ouvre.
         *
         * <p>Une clé absente n'est pas une incertitude, c'est une absence. Y
         * répondre « révoqué » refuserait tous les jetons de la plateforme en
         * permanence, puisque la clé n'existe que le temps d'une révocation.
         */
        @ParameterizedTest(name = "valeur = [{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("Valeur vide ou blanche : le jeton passe")
        void valeurVide(String valeur) {
            assertThat(RevocationGlobale.frappe(valeur, Instant.now())).isFalse();
        }

        @Test
        @DisplayName("Valeur nulle : le jeton passe")
        void valeurNulle() {
            assertThat(RevocationGlobale.frappe(null, Instant.now())).isFalse();
        }

        /**
         * Même sans date d'émission, une clé absente laisse passer.
         *
         * <p>L'ordre des contrôles compte : si le {@code null} de la date était
         * examiné d'abord, un jeton sans {@code iat} serait refusé alors
         * qu'aucune révocation n'existe — tous les jetons sans {@code iat}
         * seraient rejetés en permanence.
         */
        @Test
        @DisplayName("Cle absente ET jeton sans date : le jeton passe quand meme")
        void cleAbsenteEtSansDate() {
            assertThat(RevocationGlobale.frappe(null, null)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un jeton emis AVANT la revocation est frappe")
    class Avant {

        @Test
        @DisplayName("Emis une heure avant : frappe")
        void jetonAnterieurFrappe() {
            assertThat(RevocationGlobale.frappe(
                    HORODATAGE, REVOQUE_LE.minusSeconds(3600))).isTrue();
        }

        /**
         * LA MOITIÉ QU'ON OUBLIE, et le cœur du correctif.
         *
         * <p>Un jeton émis APRÈS la révocation doit rester valide : c'est ce qui
         * permet au porteur de se reconnecter. L'ancien {@code hasKey} le
         * refusait, et verrouillait donc le compte sept jours.
         */
        @Test
        @DisplayName("Emis une seconde APRES : passe — c'est la reconnexion")
        void jetonPosterieurPasse() {
            assertThat(RevocationGlobale.frappe(
                    HORODATAGE, REVOQUE_LE.plusSeconds(1)))
                    .as("un jeton emis apres la revocation doit rester valide, "
                            + "sinon le porteur est verrouille sept jours")
                    .isFalse();
        }

        /**
         * À l'instant exact : frappé. Le doute ferme.
         *
         * <p>L'horloge de l'émetteur et celle du révocateur ne sont pas la
         * même, et la milliseconde ne tranche rien. Un jeton émis dans la même
         * milliseconde que la révocation pourrait l'avoir précédée.
         */
        @Test
        @DisplayName("Emis a l'instant EXACT de la revocation : frappe")
        void jetonSimultaneFrappe() {
            assertThat(RevocationGlobale.frappe(HORODATAGE, REVOQUE_LE)).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les replis sont FERMES")
    class ReplisFermes {

        /**
         * Jeton sans {@code iat} : refusé.
         *
         * <p>On ne peut pas établir qu'il postdate la révocation. Le refuser est
         * le seul choix sûr — et c'est aussi un signal : l'émission de cette
         * plateforme pose toujours {@code iat}, donc un jeton sans cette date
         * n'en vient pas.
         */
        @Test
        @DisplayName("Jeton sans date d'emission : refuse")
        void sansDateEmission() {
            assertThat(RevocationGlobale.frappe(HORODATAGE, null)).isTrue();
        }

        /**
         * Valeur illisible : refusé.
         *
         * <p>Ne pas savoir quand la révocation a eu lieu, c'est ne pas savoir si
         * le jeton la précède. Accepter sur une valeur qu'on ne comprend pas
         * ferait d'une corruption de donnée une ouverture.
         */
        @ParameterizedTest(name = "valeur = {0}")
        @ValueSource(strings = {"pas-un-nombre", "2026-11-05T10:00:00Z", "12.5",
                "9999999999999999999999"})
        @DisplayName("Horodatage illisible : refuse")
        void horodatageIllisible(String valeur) {
            assertThat(RevocationGlobale.frappe(valeur, Instant.now()))
                    .as("une valeur qu'on ne comprend pas ne doit pas devenir "
                            + "une ouverture")
                    .isTrue();
        }

        /**
         * Les espaces autour de la valeur ne cassent pas la lecture.
         *
         * <p>Redis rend la valeur telle qu'elle a été écrite, mais un outil
         * d'administration ou un script de migration peut en ajouter. Une
         * valeur parfaitement valable entourée d'espaces serait sinon traitée
         * comme illisible, donc comme une révocation de tout.
         */
        @Test
        @DisplayName("Horodatage entoure d'espaces : lu correctement")
        void horodatageAvecEspaces() {
            assertThat(RevocationGlobale.frappe(
                    "  " + HORODATAGE + "  ", REVOQUE_LE.plusSeconds(1))).isFalse();
            assertThat(RevocationGlobale.frappe(
                    "  " + HORODATAGE + "  ", REVOQUE_LE.minusSeconds(1))).isTrue();
        }
    }
}
