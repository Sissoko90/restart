package com.abdatytch.auth_service.service.oauth2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le nom qu'Apple n'envoie qu'une fois.
 *
 * <h2>Ce qui est en jeu</h2>
 *
 * Apple n'a aucun point de profil. Le nom arrive dans le champ {@code user} du
 * formulaire de rappel, et <b>uniquement a la premiere autorisation</b> de cette
 * personne pour cette application. Les connexions suivantes ne le renvoient
 * plus : un nom non lu ce jour-la est perdu definitivement, sauf si la personne
 * va revoquer l'acces dans les reglages de son appareil puis se reconnecte — ce
 * qu'on ne peut pas lui demander.
 *
 * <p>C'est la cause connue des comptes Apple anonymes, et elle n'est pas
 * rattrapable apres coup. D'ou cette suite.
 *
 * <h2>Deux exigences opposees</h2>
 *
 * Lire le nom quand il est la, et ne <b>rien</b> casser quand il n'y est pas —
 * ce qui est le cas de toutes les connexions sauf la premiere. Une lecture
 * stricte ferait echouer la connexion de retour de chaque personne deja
 * inscrite.
 */
@DisplayName("ProfilInitialApple — le nom transmis une seule fois")
class ProfilInitialAppleTest {

    private static final ObjectMapper JACKSON = new ObjectMapper();

    private static ProfilInitialApple lire(String champUser) {
        return ProfilInitialApple.lire(champUser, JACKSON);
    }

    // ==================================================================
    @Nested
    @DisplayName("Premiere autorisation : le nom est la")
    class PremiereAutorisation {

        @Test
        @DisplayName("prenom et nom sont lus dans le champ user d'Apple")
        void nomLu() {
            // Forme exacte envoyee par Apple.
            String champUser = """
                    {"name":{"firstName":"Awa","lastName":"Diallo"},\
                    "email":"awa@privaterelay.appleid.com"}""";

            ProfilInitialApple profil = lire(champUser);

            assertThat(profil.estPresent()).isTrue();
            assertThat(profil.prenom()).isEqualTo("Awa");
            assertThat(profil.nom()).isEqualTo("Diallo");
            assertThat(profil.nomComplet()).isEqualTo("Awa Diallo");
        }

        @Test
        @DisplayName("un seul des deux champs suffit")
        void unSeulChamp() {
            assertThat(lire("{\"name\":{\"firstName\":\"Awa\"}}").nomComplet())
                    .isEqualTo("Awa");
            assertThat(lire("{\"name\":{\"lastName\":\"Diallo\"}}").nomComplet())
                    .isEqualTo("Diallo");
        }

        @Test
        @DisplayName("les espaces autour des valeurs sont retires")
        void espacesRetires() {
            ProfilInitialApple profil = lire(
                    "{\"name\":{\"firstName\":\"  Awa \",\"lastName\":\" Diallo  \"}}");

            assertThat(profil.nomComplet()).isEqualTo("Awa Diallo");
        }

        @Test
        @DisplayName("un champ present mais vide compte comme absent")
        void champVideCompteCommeAbsent() {
            // Un nom fait d'espaces enregistrerait un etat civil vide, qui
            // passerait ensuite pour un nom fourni — et empecherait de le
            // completer plus tard.
            ProfilInitialApple profil = lire(
                    "{\"name\":{\"firstName\":\"   \",\"lastName\":\"\"}}");

            assertThat(profil.estPresent()).isFalse();
            assertThat(profil.nomComplet()).isNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Connexions suivantes : l'absence est l'etat NORMAL")
    class AbsenceNormale {

        /**
         * Le cas majoritaire, et celui qui ne doit surtout rien casser.
         *
         * <p>Apple n'envoie le champ qu'a la premiere autorisation. Toutes les
         * connexions suivantes arrivent sans lui : une lecture stricte ferait
         * echouer la connexion de retour de chaque personne deja inscrite —
         * c'est-a-dire presque tout le trafic Apple.
         */
        @ParameterizedTest(name = "champ user = « {0} »")
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("un champ user absent rend un profil vide, sans erreur")
        void absenceToleree(String absent) {
            ProfilInitialApple profil = lire(absent);

            assertThat(profil.estPresent()).isFalse();
            assertThat(profil.prenom()).isNull();
            assertThat(profil.nom()).isNull();
            assertThat(profil.nomComplet()).isNull();
        }

        @Test
        @DisplayName("un JSON sans objet name rend un profil vide")
        void sansObjetName() {
            assertThat(lire("{\"email\":\"a@b.test\"}").estPresent()).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Contenu inattendu : jamais fatal")
    class ContenuInattendu {

        /**
         * Le champ vient d'un formulaire poste par un tiers.
         *
         * <p>Il ne sert qu'a renseigner un etat civil, jamais a identifier —
         * l'identite vient du {@code sub} du {@code id_token} verifie. Un
         * contenu malforme ne doit donc pas refuser la connexion : mieux vaut un
         * compte sans nom qu'une personne qui ne peut pas entrer.
         */
        @ParameterizedTest(name = "« {0} »")
        @ValueSource(strings = {
                "pas du json",
                "{",
                "[]",
                "\"une chaine\"",
                "{\"name\":\"pas un objet\"}",
                "{\"name\":{\"firstName\":42,\"lastName\":true}}",
                "{\"name\":null}"
        })
        @DisplayName("un contenu illisible ou d'un autre type rend un profil vide")
        void contenuIllisibleNEstPasFatal(String champUser) {
            ProfilInitialApple profil = lire(champUser);

            assertThat(profil.estPresent())
                    .withFailMessage("un contenu inattendu a produit un nom : la "
                            + "valeur retenue ne vient alors pas d'Apple")
                    .isFalse();
        }

        @Test
        @DisplayName("un champ user profond ne fait pas echouer la lecture")
        void jsonProfond() {
            // Le corps vient du reseau : une lecture qui leverait ici
            // transformerait un rappel en 500, et la personne ne pourrait pas
            // se connecter.
            String profond = "{\"name\":{\"firstName\":\"Awa\",\"extra\":"
                    + "{\"a\":{\"b\":{\"c\":[1,2,3]}}}}}";

            assertThat(lire(profond).prenom()).isEqualTo("Awa");
        }
    }
}
