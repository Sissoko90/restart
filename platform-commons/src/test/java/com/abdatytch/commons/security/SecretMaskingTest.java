package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Masquage des justificatifs dans les journaux.
 *
 * <h2>Pourquoi deux méthodes et non une</h2>
 *
 * La distinction entre {@code masquer} et {@code masquerCodeCourt} est le cœur
 * de cette classe, et {@code codeCourtNestPasEmpreinte} en est la
 * démonstration : une empreinte protège un jeton de 500 caractères et ne
 * protège <b>pas</b> un code à six chiffres. Un million de hachages se
 * calculent instantanément.
 *
 * <p>Confondre les deux cas donnerait une protection apparente : des journaux
 * remplis de {@code [empr:a1b2c3]} que n'importe qui pourrait retraduire en
 * codes à usage unique.
 */
@DisplayName("SecretMasking - citer sans divulguer")
class SecretMaskingTest {

    /** Un jeton JWT plausible, assez long pour qu'une empreinte soit sûre. */
    private static final String JETON =
            "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6ImRldi0xIn0"
            + ".eyJzdWIiOiIxMTExMTExMS0xMTExIiwidHlwZSI6InVzZXIifQ"
            + ".tL3kQm9xYz1ABcDefGhIjKlMnOpQrStUvWxYz0123456789";

    // ==================================================================
    @Nested
    @DisplayName("Justificatifs longs - empreinte")
    class JustificatifsLongs {

        @Test
        @DisplayName("le jeton n'apparaît pas dans la sortie")
        void jetonAbsent() {
            String sortie = SecretMasking.masquer(JETON);

            assertThat(sortie)
                    .withFailMessage("le jeton se retrouve dans le journal")
                    .doesNotContain(JETON);
            // Ni meme un fragment exploitable : une signature partielle suffirait
            // a confirmer un jeton devine par ailleurs.
            assertThat(sortie).doesNotContain(JETON.substring(0, 20));
        }

        @Test
        @DisplayName("la même valeur donne toujours la même empreinte")
        void empreinteStable() {
            // C'est ce qui rend le masquage utile plutot que seulement sur :
            // relier « validation », « revoque » et « rafraichissement » au meme
            // porteur lors d'une investigation.
            assertThat(SecretMasking.masquer(JETON))
                    .isEqualTo(SecretMasking.masquer(JETON));
        }

        @Test
        @DisplayName("deux valeurs différentes donnent des empreintes différentes")
        void empreintesDistinctes() {
            assertThat(SecretMasking.masquer(JETON))
                    .isNotEqualTo(SecretMasking.masquer(JETON + "x"));
        }

        @Test
        @DisplayName("l'empreinte est courte - un journal reste lisible")
        void empreinteCourte() {
            // Une empreinte de 64 caracteres par ligne rendrait les journaux
            // illisibles, et un journal illisible n'est pas consulte.
            assertThat(SecretMasking.masquer(JETON)).hasSizeLessThan(16);
        }

        @Test
        @DisplayName("les empreintes ne collisionnent pas sur un échantillon réaliste")
        void pasDeCollisionSurEchantillon() {
            // Six caracteres hexadecimaux font 16,7 millions de valeurs. Sur
            // quelques milliers de jetons, les collisions doivent rester nulles,
            // sinon la correlation designerait deux porteurs distincts.
            Set<String> empreintes = new HashSet<>();
            for (int i = 0; i < 5000; i++) {
                empreintes.add(SecretMasking.masquer(JETON + i));
            }

            assertThat(empreintes)
                    .withFailMessage("collisions d'empreintes : la corrélation "
                            + "mélangerait deux porteurs")
                    .hasSize(5000);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Secrets courts - aucune empreinte")
    class SecretsCourts {

        @ParameterizedTest(name = "code « {0} »")
        @ValueSource(strings = {"483920", "000000", "12345678", "9999"})
        @DisplayName("un code de vérification est intégralement masqué")
        void codeIntegralementMasque(String code) {
            assertThat(SecretMasking.masquerCodeCourt(code))
                    .withFailMessage("le code se retrouve dans le journal")
                    .doesNotContain(code)
                    .isEqualTo("[masqué]");
        }

        @Test
        @DisplayName("un code à six chiffres NE reçoit PAS d'empreinte - la démonstration")
        void codeCourtNestPasEmpreinte() throws Exception {
            // LE TEST QUI JUSTIFIE L'EXISTENCE DE DEUX METHODES.
            //
            // Si un code a six chiffres recevait une empreinte, la publier
            // reviendrait a publier le code : on parcourt le million de
            // candidats en une fraction de seconde. Ce test FAIT l'attaque pour
            // demontrer qu'elle marche, puis verifie que masquerCodeCourt n'y
            // expose pas.
            String code = "483920";

            // L'attaque, sur l'empreinte brute : elle aboutit.
            String empreinteDuCode = SecretMasking.empreinte(code);
            String retrouve = null;
            for (int candidat = 483000; candidat <= 484000; candidat++) {
                String essai = String.format("%06d", candidat);
                if (SecretMasking.empreinte(essai).equals(empreinteDuCode)) {
                    retrouve = essai;
                    break;
                }
            }
            assertThat(retrouve)
                    .withFailMessage("l'attaque par force brute devrait aboutir : "
                            + "c'est précisément la raison de ne pas empreinter "
                            + "un code court")
                    .isEqualTo(code);

            // Et masquerCodeCourt ne publie donc aucune empreinte.
            assertThat(SecretMasking.masquerCodeCourt(code))
                    .withFailMessage("une empreinte de code court est publiée : "
                            + "elle est réversible, donc ce n'est pas un masquage")
                    .doesNotContain(empreinteDuCode);
        }

        @Test
        @DisplayName("masquer() refuse aussi d'empreinter une valeur trop courte")
        void masquerRefuseLesValeursCourtes() {
            // Garde-fou : quelqu'un qui appellerait masquer() sur un code par
            // inadvertance ne doit pas obtenir une empreinte reversible.
            assertThat(SecretMasking.masquer("483920"))
                    .withFailMessage("masquer() empreinte une valeur courte, donc "
                            + "réversible : l'erreur d'appel n'est pas rattrapée")
                    .isEqualTo("[masqué]");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Entrées dégradées")
    class EntreesDegradees {

        @Test
        @DisplayName("null et vide ne lèvent pas et se distinguent d'un secret")
        void nullEtVide() {
            // Journaliser « null » laisserait croire a une valeur ; « [absent] »
            // dit ce qui s'est passe.
            assertThat(SecretMasking.masquer(null)).isEqualTo("[absent]");
            assertThat(SecretMasking.masquer("")).isEqualTo("[absent]");
            assertThat(SecretMasking.masquer("   ")).isEqualTo("[absent]");
            assertThat(SecretMasking.masquerCodeCourt(null)).isEqualTo("[absent]");
        }

        @Test
        @DisplayName("l'empreinte correspond bien au début du SHA-256")
        void empreinteConformeAuCondense() throws Exception {
            // Verrouille l'algorithme : le changer invaliderait la correlation
            // entre d'anciennes et de nouvelles lignes de journal.
            byte[] condense = MessageDigest.getInstance("SHA-256")
                    .digest(JETON.getBytes(StandardCharsets.UTF_8));
            StringBuilder attendu = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                attendu.append(String.format("%02x", condense[i]));
            }

            assertThat(SecretMasking.empreinte(JETON)).isEqualTo(attendu.toString());
        }
    }
}
