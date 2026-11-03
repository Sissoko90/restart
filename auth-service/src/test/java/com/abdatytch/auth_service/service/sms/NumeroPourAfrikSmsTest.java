package com.abdatytch.auth_service.service.sms;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le numéro est la condition d'un SMS qui part.
 *
 * <p>AfrikSMS refuse le {@code +} que la plateforme stocke, et refuse sans
 * erreur HTTP : un {@code code} de rejet dans un corps 200. Une normalisation
 * absente ou fausse ne se verrait donc pas dans les journaux comme une panne,
 * mais seulement comme des utilisateurs qui ne reçoivent rien.
 */
@DisplayName("Normalisation du numéro pour AfrikSMS")
class NumeroPourAfrikSmsTest {

    @Nested
    @DisplayName("Préfixes à retirer")
    class Prefixes {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "+22890909090, 22890909090",
                "0022890909090, 22890909090",
                "22890909090, 22890909090",
                // Le + puis le 00 : forme qui apparait dans les saisies
                // manuelles. Les deux retraits doivent s'enchainer.
                "+0022890909090, 22890909090",
        })
        void retireLePlusEtLeDoubleZero(String entree, String attendu) {
            assertThat(NumeroPourAfrikSms.normaliser(entree)).isEqualTo(attendu);
        }

        @Test
        @DisplayName("un indicatif ne commence jamais par zéro, le 00 est donc sûr à retirer")
        void leDoubleZeroNAmputePasUnIndicatif() {
            // Retirer « 00 » d'un numero qui commence par « 0 » simple serait
            // une erreur ; il n'y a rien a retirer ici.
            assertThat(NumeroPourAfrikSms.normaliser("033712345678"))
                    .isEqualTo("033712345678");
        }
    }

    @Nested
    @DisplayName("Séparateurs de mise en forme")
    class Separateurs {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "+228 90 90 90 90",
                "+228-90-90-90-90",
                "+228.90.90.90.90",
                "(+228) 90 90 90 90",
                "+228/90/90/90/90",
                // Espace insecable : ce que produit un copier-coller depuis un
                // navigateur ou un traitement de texte.
                "+228 90 90 90 90",
        })
        void sontRetires(String entree) {
            assertThat(NumeroPourAfrikSms.normaliser(entree)).isEqualTo("22890909090");
        }
    }

    @Nested
    @DisplayName("Refus")
    class Refus {

        @Test
        @DisplayName("null et vide")
        void nullEtVide() {
            assertThat(NumeroPourAfrikSms.normaliser(null)).isEmpty();
            assertThat(NumeroPourAfrikSms.normaliser("")).isEmpty();
            assertThat(NumeroPourAfrikSms.normaliser("   ")).isEmpty();
        }

        @Test
        @DisplayName("une lettre au milieu est refusée, et surtout pas retirée")
        void uneLettreEstRefusee() {
            // RETIRER le caractere fautif donnerait « 2289090909 », un numero
            // d'apparence valide et destine a quelqu'un d'autre : le code de
            // verification partirait chez un inconnu. Refuser est la seule
            // issue correcte. Verifie par mutation : remplacer le refus par un
            // retrait fait echouer ce test.
            assertThat(NumeroPourAfrikSms.normaliser("228909x0909")).isEmpty();
        }

        @ParameterizedTest(name = "trop court ou trop long: {0}")
        @ValueSource(strings = {
                "1234567",            // 7 chiffres
                "1234567890123456",   // 16 chiffres, au-dela de E.164
        })
        void longueurInvraisemblable(String entree) {
            assertThat(NumeroPourAfrikSms.normaliser(entree)).isEmpty();
        }

        @Test
        @DisplayName("les bornes de longueur sont inclusives")
        void bornesInclusives() {
            assertThat(NumeroPourAfrikSms.normaliser("12345678")).isEqualTo("12345678");
            assertThat(NumeroPourAfrikSms.normaliser("123456789012345"))
                    .isEqualTo("123456789012345");
        }

        @Test
        @DisplayName("un numéro réduit à un préfixe ne passe pas")
        void reduitAUnPrefixe() {
            assertThat(NumeroPourAfrikSms.normaliser("+")).isEmpty();
            assertThat(NumeroPourAfrikSms.normaliser("00")).isEmpty();
        }
    }

    @Test
    @DisplayName("un numéro national sans indicatif est transmis tel quel, pas complété")
    void aucunIndicatifNEstDevine() {
        // Completer avec un indicatif par defaut enverrait le code de
        // verification dans un autre pays, a un abonne qui porte le meme numero
        // national. Le numero part tel quel et c'est AfrikSMS qui le refuse,
        // visiblement, par son code.
        assertThat(NumeroPourAfrikSms.normaliser("90909090")).isEqualTo("90909090");
    }
}
