package com.abdatytch.auth_service.service.oauth2;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le secret client d'Apple : un JWT ES256, produit et renouvele.
 *
 * <h2>Ce que cette suite protege</h2>
 *
 * Apple n'accepte un echange de code que si le secret porte exactement
 * {@code iss} = Team ID, {@code sub} = Services ID,
 * {@code aud = https://appleid.apple.com}, un {@code kid} en en-tete, et une
 * signature ES256. Une seule de ces valeurs fausse produit un
 * {@code invalid_client} — un message qui ne designe <b>pas</b> la cause, et
 * qui a envoye bien des integrations chercher du cote des identifiants.
 *
 * <p>Chaque assertion ici correspond donc a une exigence d'Apple, pas a un
 * choix d'implementation.
 *
 * <h2>Pourquoi une cle generee et non la vraie</h2>
 *
 * La suite fabrique sa propre paire EC P-256. Aucun secret reel n'entre dans le
 * depot, et le test vaut sans qu'un compte Apple existe — ce qui etait
 * necessaire ici, l'acces a la console n'etant pas encore ouvert au moment de
 * l'ecrire.
 */
@DisplayName("SecretClientApple — le JWT exige par Apple")
class SecretClientAppleTest {

    private static final String TEAM_ID = "A1B2C3D4E5";
    private static final String SERVICES_ID = "com.exemple.restart.web";
    private static final String KEY_ID = "ABCDE12345";

    private static String clePriveePkcs8;
    private static KeyPair paire;

    @BeforeAll
    static void genererUneCleDEssai() throws Exception {
        KeyPairGenerator generateur = KeyPairGenerator.getInstance("EC");
        generateur.initialize(new ECGenParameterSpec("secp256r1"));
        paire = generateur.generateKeyPair();
        clePriveePkcs8 = Base64.getEncoder().encodeToString(paire.getPrivate().getEncoded());
    }

    private static SecretClientApple secret(String cle) {
        return new SecretClientApple(TEAM_ID, SERVICES_ID, KEY_ID, cle);
    }

    // ==================================================================
    @Nested
    @DisplayName("Le contenu exige par Apple")
    class ContenuExige {

        @Test
        @DisplayName("iss, sub, aud et kid sont ceux qu'Apple attend")
        void revendicationsExactes() {
            String jeton = secret(clePriveePkcs8).valeur();

            var lu = Jwts.parser()
                    .verifyWith(paire.getPublic())
                    .build()
                    .parseSignedClaims(jeton);

            assertThat(lu.getPayload().getIssuer())
                    .withFailMessage("iss doit etre le Team ID : Apple refuse avec "
                            + "invalid_client, qui ne designe pas la cause")
                    .isEqualTo(TEAM_ID);
            assertThat(lu.getPayload().getSubject())
                    .withFailMessage("sub doit etre le Services ID — PAS l'App ID, "
                            + "confusion la plus frequente dans cette integration")
                    .isEqualTo(SERVICES_ID);
            assertThat(lu.getPayload().getAudience())
                    .containsExactly(SecretClientApple.AUDIENCE);
            assertThat(lu.getHeader().getKeyId())
                    .withFailMessage("le kid identifie la cle de signature : sans lui "
                            + "Apple ne sait pas laquelle verifier")
                    .isEqualTo(KEY_ID);
        }

        @Test
        @DisplayName("la signature est bien ES256, et verifiable par la cle publique")
        void signatureES256() {
            String jeton = secret(clePriveePkcs8).valeur();

            var lu = Jwts.parser().verifyWith(paire.getPublic()).build()
                    .parseSignedClaims(jeton);

            assertThat(lu.getHeader().getAlgorithm())
                    .withFailMessage("Apple n'accepte QUE ES256 pour ce secret")
                    .isEqualTo("ES256");
        }

        @Test
        @DisplayName("l'echeance reste sous le plafond de six mois impose par Apple")
        void echeanceSousLePlafond() {
            String jeton = secret(clePriveePkcs8).valeur();

            var charge = Jwts.parser().verifyWith(paire.getPublic()).build()
                    .parseSignedClaims(jeton).getPayload();

            Instant emission = charge.getIssuedAt().toInstant();
            Instant echeance = charge.getExpiration().toInstant();

            assertThat(echeance).isAfter(Instant.now());
            assertThat(java.time.Duration.between(emission, echeance))
                    .withFailMessage("Apple refuse un secret dont la validite depasse "
                            + "six mois")
                    .isLessThan(java.time.Duration.ofDays(180));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Renouvellement")
    class Renouvellement {

        @Test
        @DisplayName("le secret est reutilise tant que son echeance est loin")
        void reutiliseTantQueValide() {
            // Signer est du calcul. Le refaire a chaque echange de code
            // placerait ce calcul sur le chemin de connexion — la lecon des
            // 241 ms de BCrypt.
            SecretClientApple generateur = secret(clePriveePkcs8);

            assertThat(generateur.valeur())
                    .withFailMessage("un nouveau secret est produit a chaque appel : "
                            + "une signature ES256 s'ajoute alors a chaque connexion")
                    .isEqualTo(generateur.valeur());
        }

        @Test
        @DisplayName("la marge de renouvellement precede largement l'echeance")
        void margeAvantEcheance() {
            // Un secret qui expire PENDANT un echange de code produit un
            // invalid_client chez Apple, qui n'indique pas la cause. La marge
            // doit donc etre large devant la duree de validite, pas symbolique.
            assertThat(SecretClientApple.MARGE_DE_RENOUVELLEMENT)
                    .isGreaterThanOrEqualTo(java.time.Duration.ofHours(1));
            assertThat(SecretClientApple.VALIDITE)
                    .isGreaterThan(SecretClientApple.MARGE_DE_RENOUVELLEMENT.multipliedBy(2));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une cle illisible echoue a la CONSTRUCTION")
    class CleIllisible {

        /**
         * Le moment de l'echec compte autant que l'echec.
         *
         * <p>Si la cle n'etait validee qu'au premier echange de code, une cle
         * mal copiee se manifesterait a la premiere connexion Apple d'un
         * utilisateur reel — en production, et sous la forme d'un 500.
         */
        @ParameterizedTest(name = "cle = « {0} »")
        @ValueSource(strings = {"", "   ", "pas-du-base64", "YWJjZA=="})
        @DisplayName("une cle absente ou invalide est refusee tout de suite")
        void refuseeALaConstruction(String cle) {
            assertThatThrownBy(() -> secret(cle))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("le message d'erreur ne reprend AUCUN fragment de la cle")
        void messageSansFragmentDeCle() {
            // Une cle mal copiee reste un secret : la moitie d'une cle privee
            // EC reduit considerablement l'effort pour retrouver l'autre.
            String presqueVraie = clePriveePkcs8.substring(0, 40);

            assertThatThrownBy(() -> secret(presqueVraie))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(erreur -> assertThat(erreur.getMessage())
                            .withFailMessage("un fragment de la cle privee apparait "
                                    + "dans le message, donc dans les journaux de "
                                    + "l'appelant")
                            .doesNotContain(presqueVraie.substring(0, 20)));
        }

        @Test
        @DisplayName("les en-tetes PEM et les retours a la ligne sont tolleres")
        void pemTolere() {
            // Un .p8 colle dans une variable d'environnement arrive presque
            // toujours avec ses en-tetes, et parfois avec des \\n litteraux.
            // Refuser cette forme ferait perdre des heures a chaque
            // deploiement.
            String avecPem = "-----BEGIN PRIVATE KEY-----\\n"
                    + clePriveePkcs8 + "\\n-----END PRIVATE KEY-----\\n";

            assertThat(secret(avecPem).valeur()).isNotBlank();
        }
    }
}
