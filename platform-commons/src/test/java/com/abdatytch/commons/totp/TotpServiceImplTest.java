package com.abdatytch.commons.totp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOTP — second facteur de l'authentification administrateur.
 *
 * <p>Deux exigences, de natures différentes :
 * <ul>
 *   <li><b>Interopérabilité.</b> Les codes doivent être ceux que produit Google
 *       Authenticator. Une implémentation « presque » conforme à la RFC 6238
 *       génère des codes cohérents avec elle-même — les tests de va-et-vient
 *       passent — mais qu'aucune application d'authentification ne reconnaît.
 *       La 2FA devient alors impossible à activer, et le défaut n'apparaît
 *       qu'au premier utilisateur réel.</li>
 *   <li><b>Sécurité.</b> Un code trop ancien, rejoué ou malformé doit être
 *       refusé.</li>
 * </ul>
 *
 * <p>L'interopérabilité est vérifiée par une implémentation de référence écrite
 * dans ce test, elle-même validée par le vecteur d'essai officiel de la
 * RFC 6238. Comparer la production à cette référence a du sens ; la comparer à
 * elle-même n'en aurait aucun.
 */
@DisplayName("TotpServiceImpl — second facteur administrateur")
class TotpServiceImplTest {

    private static final int TIME_STEP = 30;

    private final TotpServiceImpl service = new TotpServiceImpl();

    // ==================================================================
    // Implémentation de référence (RFC 6238), indépendante de la production
    // ==================================================================

    /** Secret du vecteur d'essai officiel : « 12345678901234567890 » en ASCII. */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    private static String referenceCode(byte[] secret, long timeWindow, int digits) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret, "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeWindow).array());

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        return String.format("%0" + digits + "d", binary % (int) Math.pow(10, digits));
    }

    private static long currentWindow() {
        return System.currentTimeMillis() / 1000L / TIME_STEP;
    }

    // ==================================================================
    @Nested
    @DisplayName("Conformité RFC 6238 (interopérabilité Google Authenticator)")
    class Conformite {

        @Test
        @DisplayName("l'implémentation de référence reproduit le vecteur d'essai officiel")
        void referenceValidee() throws Exception {
            // RFC 6238, annexe B : à T = 59 s (donc fenêtre 1), SHA-1,
            // le code à 8 chiffres attendu est 94287082.
            assertThat(referenceCode(RFC_SECRET, 59L / TIME_STEP, 8)).isEqualTo("94287082");
            assertThat(referenceCode(RFC_SECRET, 1111111109L / TIME_STEP, 8)).isEqualTo("07081804");
            assertThat(referenceCode(RFC_SECRET, 1111111111L / TIME_STEP, 8)).isEqualTo("14050471");
            assertThat(referenceCode(RFC_SECRET, 1234567890L / TIME_STEP, 8)).isEqualTo("89005924");
            assertThat(referenceCode(RFC_SECRET, 2000000000L / TIME_STEP, 8)).isEqualTo("69279037");
        }

        @Test
        @DisplayName("le code produit est celui qu'attendrait une application d'authentification")
        void codeInteroperable() throws Exception {
            String attendu = referenceCode(RFC_SECRET, currentWindow(), 6);

            assertThat(service.generateTotpCode(RFC_SECRET_BASE32))
                    .withFailMessage("le code produit diffère de la RFC 6238 : aucune "
                            + "application d'authentification ne le reconnaîtra, et la 2FA "
                            + "sera impossible à activer")
                    .isEqualTo(attendu);
        }

        @Test
        @DisplayName("le décodage Base32 tolère la casse et les séparateurs")
        void base32Tolerant() {
            String espace = "GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ";

            assertThat(service.generateTotpCode(RFC_SECRET_BASE32.toLowerCase()))
                    .isEqualTo(service.generateTotpCode(RFC_SECRET_BASE32));
            assertThat(service.generateTotpCode(espace))
                    .isEqualTo(service.generateTotpCode(RFC_SECRET_BASE32));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Génération du secret")
    class GenerationDuSecret {

        @Test
        @DisplayName("le secret fait 160 bits, encodés en 32 caractères Base32")
        void longueurEtAlphabet() {
            String secret = service.generateTotpSecret();

            // 160 bits / 5 bits par caractère = 32 caractères.
            assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        }

        @Test
        @DisplayName("deux secrets consécutifs diffèrent")
        void secretsDistincts() {
            // Un générateur qui se répéterait donnerait le même second facteur à
            // plusieurs administrateurs.
            assertThat(service.generateTotpSecret()).isNotEqualTo(service.generateTotpSecret());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Validation d'un code")
    class Validation {

        private final String secret = service.generateTotpSecret();

        @Test
        @DisplayName("le code de la fenêtre courante est accepté")
        void codeCourantAccepte() {
            StepVerifier.create(service.validateTotpCode(secret, service.generateTotpCode(secret)))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un code des fenêtres adjacentes est accepté (décalage d'horloge)")
        void fenetresAdjacentesAcceptees() throws Exception {
            byte[] brut = decodeBase32ForTest(secret);

            for (long decalage : new long[]{-1L, 1L}) {
                String code = referenceCode(brut, currentWindow() + decalage, 6);
                Boolean accepte = service.validateTotpCode(secret, code).block();
                assertThat(accepte)
                        .withFailMessage("un décalage d'horloge de %d fenêtre(s) rend la 2FA "
                                + "inutilisable", decalage)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("un code d'une fenêtre trop ancienne est refusé")
        void fenetreTropAncienneRefusee() throws Exception {
            byte[] brut = decodeBase32ForTest(secret);
            // Cinq fenêtres, soit deux minutes et demie : bien au-delà de la
            // tolérance d'horloge. Accepter ce code élargirait sans raison la
            // durée pendant laquelle un code intercepté reste utilisable.
            String ancien = referenceCode(brut, currentWindow() - 5, 6);

            StepVerifier.create(service.validateTotpCode(secret, ancien))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("le code d'un autre secret est refusé")
        void secretEtrangerRefuse() {
            String autreSecret = service.generateTotpSecret();

            StepVerifier.create(service.validateTotpCode(secret, service.generateTotpCode(autreSecret)))
                    .expectNext(false)
                    .verifyComplete();
        }

        @ParameterizedTest(name = "code « {0} »")
        @ValueSource(strings = {"", "12345", "1234567", "abcdef", "  1234"})
        @NullSource
        @DisplayName("un code malformé est refusé sans erreur")
        void codeMalformeRefuse(String code) {
            StepVerifier.create(service.validateTotpCode(secret, code))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("un secret illisible fait échouer la validation, jamais réussir")
        void secretIllisible() {
            // La validation attrape ses exceptions : il faut s'assurer qu'elle
            // retombe sur « refusé » et non sur « accepté ».
            StepVerifier.create(service.validateTotpCode("!!!", "123456"))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("URL d'enrôlement (QR code)")
    class UrlEnrolement {

        @Test
        @DisplayName("l'URL porte le schéma, le secret et les paramètres attendus")
        void formatOtpauth() {
            String url = service.generateQrCodeUrl("admin@abdatech.com", "ABCDEF234567", "AbdaTech");

            assertThat(url)
                    .startsWith("otpauth://totp/")
                    .contains("secret=ABCDEF234567")
                    .contains("issuer=AbdaTech")
                    .contains("algorithm=SHA1")
                    .contains("digits=6")
                    .contains("period=30");
        }

        @Test
        @DisplayName("une adresse contenant un séparateur d'URL ne peut pas injecter de paramètre")
        void pasDInjectionDeParametre() {
            // Sans encodage, une adresse comme celle-ci insérerait un second
            // `issuer` dans l'URL : l'application d'authentification afficherait
            // un émetteur choisi par l'utilisateur, ce qui invite à confondre le
            // compte avec celui d'un autre service.
            String url = service.generateQrCodeUrl(
                    "victime@abdatech.com&issuer=Banque", "SECRET234567", "AbdaTech");

            assertThat(url.split("issuer=", -1))
                    .withFailMessage("l'URL contient deux paramètres `issuer` : "
                            + "l'adresse n'est pas encodée avant insertion — %s", url)
                    .hasSize(2);
        }
    }

    // ------------------------------------------------------------------
    /** Décodage Base32 minimal, pour alimenter l'implémentation de référence. */
    private static byte[] decodeBase32ForTest(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String clean = base32.toUpperCase().replaceAll("[^A-Z2-7]", "");
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
