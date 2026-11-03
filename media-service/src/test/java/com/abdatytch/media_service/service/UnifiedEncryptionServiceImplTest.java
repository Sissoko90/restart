package com.abdatytch.media_service.service;

import com.abdatytch.media_service.service.impl.UnifiedEncryptionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chiffrement des pièces d'identité déposées par les utilisateurs.
 *
 * <p>C'est la donnée la plus sensible que la plateforme conserve. Les propriétés
 * vérifiées ici ne sont pas décoratives :
 * <ul>
 *   <li><b>Le va-et-vient</b> — un chiffrement qu'on ne sait pas défaire détruit
 *       les documents aussi sûrement qu'une suppression.</li>
 *   <li><b>Un vecteur d'initialisation distinct à chaque fois</b> — le réutiliser
 *       en mode GCM est une faute grave : deux chiffrés sous la même clé et le
 *       même IV révèlent le OU exclusif des clairs, et compromettent la clé
 *       d'authentification.</li>
 *   <li><b>L'authentification</b> — GCM doit refuser un chiffré modifié. Sans
 *       cela, un chiffré altéré se déchiffrerait en données arbitraires.</li>
 *   <li><b>La séparation des clés</b> — le document d'un utilisateur ne doit pas
 *       être déchiffrable sous l'identité d'un autre.</li>
 * </ul>
 */
@DisplayName("UnifiedEncryptionServiceImpl — chiffrement des documents d'identité")
class UnifiedEncryptionServiceImplTest {

    private static final String CLE_MAITRESSE = "cle-maitresse-de-test-a-forte-entropie-0123456789";
    private static final String UTILISATEUR = "11111111-2222-3333-4444-555555555555";
    private static final String DOCUMENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static final byte[] CONTENU =
            "Carte nationale d'identité — Awa Traoré".getBytes(StandardCharsets.UTF_8);

    private UnifiedEncryptionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UnifiedEncryptionServiceImpl();
        ReflectionTestUtils.setField(service, "masterKey", CLE_MAITRESSE);
        ReflectionTestUtils.setField(service, "encryptionEnabled", true);
    }

    private byte[] chiffrer(byte[] contenu, String userId, String documentId) {
        return service.encryptDocument(contenu, userId, documentId).block();
    }

    // ==================================================================
    @Nested
    @DisplayName("Va-et-vient")
    class VaEtVient {

        @Test
        @DisplayName("un document chiffré puis déchiffré est identique à l'original")
        void allerRetour() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);

            StepVerifier.create(service.decryptDocument(chiffre, UTILISATEUR, DOCUMENT))
                    .assertNext(clair -> assertThat(clair).isEqualTo(CONTENU))
                    .verifyComplete();
        }

        @Test
        @DisplayName("le chiffré ne contient pas le clair")
        void clairAbsentDuChiffre() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);

            assertThat(new String(chiffre, StandardCharsets.ISO_8859_1))
                    .doesNotContain("Awa Traoré");
        }

        @Test
        @DisplayName("un document vide est traité sans erreur")
        void documentVide() {
            byte[] chiffre = chiffrer(new byte[0], UTILISATEUR, DOCUMENT);

            StepVerifier.create(service.decryptDocument(chiffre, UTILISATEUR, DOCUMENT))
                    .assertNext(clair -> assertThat(clair).isEmpty())
                    .verifyComplete();
        }

        @Test
        @DisplayName("un document volumineux est traité intégralement")
        void documentVolumineux() {
            byte[] gros = new byte[2 * 1024 * 1024];
            new java.util.Random(42).nextBytes(gros);

            StepVerifier.create(service.decryptDocument(
                            chiffrer(gros, UTILISATEUR, DOCUMENT), UTILISATEUR, DOCUMENT))
                    .assertNext(clair -> assertThat(clair).isEqualTo(gros))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Vecteur d'initialisation")
    class VecteurInitialisation {

        @Test
        @DisplayName("deux chiffrements du MÊME contenu produisent des chiffrés différents")
        void ivDistinctAChaqueFois() {
            byte[] premier = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);
            byte[] second = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);

            assertThat(premier)
                    .withFailMessage("le vecteur d'initialisation est réutilisé : en mode GCM "
                            + "c'est une faute grave — deux chiffrés sous la même clé et le "
                            + "même IV révèlent le OU exclusif des clairs")
                    .isNotEqualTo(second);
        }

        @Test
        @DisplayName("cent chiffrements produisent cent vecteurs distincts")
        void pasDeCollisionSurCentTirages() {
            Set<String> vecteurs = new HashSet<>();
            for (int i = 0; i < 100; i++) {
                byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);
                // Les 12 premiers octets portent l'IV, préfixé au chiffré.
                vecteurs.add(Base64.getEncoder()
                        .encodeToString(java.util.Arrays.copyOf(chiffre, 12)));
            }

            assertThat(vecteurs).hasSize(100);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Authentification du chiffré (GCM)")
    class Authentification {

        @Test
        @DisplayName("un chiffré modifié d'un seul bit est refusé")
        void chiffreAltereRefuse() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);
            chiffre[chiffre.length - 5] ^= 0x01;

            StepVerifier.create(service.decryptDocument(chiffre, UTILISATEUR, DOCUMENT))
                    .verifyError();
        }

        @Test
        @DisplayName("un vecteur d'initialisation modifié est refusé")
        void ivAltereRefuse() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);
            chiffre[0] ^= 0x01;

            StepVerifier.create(service.decryptDocument(chiffre, UTILISATEUR, DOCUMENT))
                    .verifyError();
        }

        @Test
        @DisplayName("un chiffré tronqué est refusé")
        void chiffreTronqueRefuse() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);
            byte[] tronque = java.util.Arrays.copyOf(chiffre, chiffre.length - 4);

            StepVerifier.create(service.decryptDocument(tronque, UTILISATEUR, DOCUMENT))
                    .verifyError();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Séparation des clés")
    class SeparationDesCles {

        @Test
        @DisplayName("le document d'un utilisateur n'est pas déchiffrable sous une autre identité")
        void pasDeDechiffrementCroiseEntreUtilisateurs() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);

            StepVerifier.create(service.decryptDocument(
                            chiffre, "99999999-9999-9999-9999-999999999999", DOCUMENT))
                    .verifyError();
        }

        @Test
        @DisplayName("un document n'est pas déchiffrable sous l'identifiant d'un autre document")
        void pasDeDechiffrementCroiseEntreDocuments() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);

            StepVerifier.create(service.decryptDocument(
                            chiffre, UTILISATEUR, "ffffffff-ffff-ffff-ffff-ffffffffffff"))
                    .verifyError();
        }

        @Test
        @DisplayName("une clé maîtresse différente ne déchiffre pas")
        void cleMaitresseDifferente() {
            byte[] chiffre = chiffrer(CONTENU, UTILISATEUR, DOCUMENT);

            UnifiedEncryptionServiceImpl autre = new UnifiedEncryptionServiceImpl();
            ReflectionTestUtils.setField(autre, "masterKey", "une-autre-cle-maitresse");
            ReflectionTestUtils.setField(autre, "encryptionEnabled", true);

            StepVerifier.create(autre.decryptDocument(chiffre, UTILISATEUR, DOCUMENT))
                    .verifyError();
        }

        @Test
        @DisplayName("deux couples (utilisateur, document) distincts ne partagent pas de clé")
        void pasDeCollisionDeDerivation() {
            // La clé est dérivée d'une concaténation. Si celle-ci est ambiguë,
            // deux couples différents produisent la MÊME clé : le document de
            // l'un devient déchiffrable sous l'identité de l'autre.
            byte[] chiffre = chiffrer(CONTENU, "alice", "photo:recto");

            assertThatThrownBy(() -> service.decryptDocument(chiffre, "alice:photo", "recto").block())
                    .withFailMessage("deux couples (utilisateur, document) distincts dérivent "
                            + "la même clé : le document de l'un est déchiffrable sous "
                            + "l'identité de l'autre")
                    .isInstanceOf(Throwable.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Chiffrement désactivé")
    class ChiffrementDesactive {

        @BeforeEach
        void desactiver() {
            ReflectionTestUtils.setField(service, "encryptionEnabled", false);
        }

        @Test
        @DisplayName("le contenu traverse sans modification")
        void passePlat() {
            StepVerifier.create(service.encryptDocument(CONTENU, UTILISATEUR, DOCUMENT))
                    .assertNext(sortie -> assertThat(sortie).isEqualTo(CONTENU))
                    .verifyComplete();

            StepVerifier.create(service.decryptDocument(CONTENU, UTILISATEUR, DOCUMENT))
                    .assertNext(sortie -> assertThat(sortie).isEqualTo(CONTENU))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Empreinte de fichier")
    class Empreinte {

        @Test
        @DisplayName("l'empreinte est un SHA-256 encodé en Base64")
        void formatSha256() {
            StepVerifier.create(service.generateFileHash(CONTENU))
                    .assertNext(empreinte -> {
                        // SHA-256 : 32 octets, soit 44 caractères en Base64 avec remplissage.
                        assertThat(empreinte).hasSize(44);
                        assertThat(Base64.getDecoder().decode(empreinte)).hasSize(32);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("le même contenu donne toujours la même empreinte")
        void empreinteStable() {
            String premiere = service.generateFileHash(CONTENU).block();
            String seconde = service.generateFileHash(CONTENU).block();

            assertThat(premiere).isEqualTo(seconde);
        }

        @Test
        @DisplayName("un contenu modifié donne une empreinte différente")
        void empreinteSensibleAuContenu() {
            // C'est la propriété qui permet de détecter la corruption d'un
            // document entre le dépôt et la relecture.
            byte[] modifie = CONTENU.clone();
            modifie[0] ^= 0x01;

            assertThat(service.generateFileHash(CONTENU).block())
                    .isNotEqualTo(service.generateFileHash(modifie).block());
        }
    }
}
