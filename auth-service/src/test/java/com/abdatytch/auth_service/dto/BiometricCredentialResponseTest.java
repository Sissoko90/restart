package com.abdatytch.auth_service.dto;

import com.abdatytch.auth_service.dto.response.BiometricCredentialResponse;
import com.abdatytch.auth_service.entities.BiometricCredential;
import com.abdatytch.auth_service.enums.BiometricType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que {@code GET /auth/biometric/credentials} laisse sortir du service.
 *
 * <h2>Pourquoi cette décision a été prise</h2>
 *
 * L'endpoint sérialisait l'entité {@link BiometricCredential} en entier. Parmi
 * ses champs, {@code publicKey} avait déjà servi : l'ancienne vérification
 * biométrique calculait
 * {@code Base64(publicKey + authenticatorData + clientDataJSON + challenge)} et
 * acceptait tout appelant capable de fournir ces quatre valeurs. Cet endpoint
 * livrait la seule que le client ne possédait pas.
 *
 * <p>La vérification passe désormais par webauthn4j, donc la clé publique n'est
 * plus suffisante pour usurper un compte. Mais l'exposer reste fournir
 * gratuitement un élément d'attaque pour la prochaine erreur — et une clé
 * publique d'authentificateur n'a aucune utilité pour le client, qui veut
 * seulement reconnaître ses appareils.
 *
 * <h2>Le test qui compte</h2>
 *
 * {@code aucunChampSensibleAjouteParInadvertance} énumère les champs du DTO par
 * réflexion et échoue sur tout nom sensible. Un test qui se contenterait de
 * vérifier l'absence des trois champs connus laisserait passer le quatrième,
 * ajouté dans six mois par quelqu'un qui aura voulu « compléter » la réponse.
 */
@DisplayName("BiometricCredentialResponse - ce qui sort du service")
class BiometricCredentialResponseTest {

    private BiometricCredential entite;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper().findAndRegisterModules();

        entite = new BiometricCredential();
        entite.setUserId("11111111-1111-1111-1111-111111111111");
        entite.setCredentialId("credential-abc");
        entite.setBiometricType(BiometricType.FINGERPRINT);
        entite.setPublicKey("CLE-PUBLIQUE-QUI-NE-DOIT-PAS-SORTIR");
        entite.setAaguid("AAGUID-MODELE-DU-MATERIEL");
        entite.setDeviceId("IDENTIFIANT-STABLE-APPAREIL");
        entite.setDeviceName("iPhone de Test");
        entite.setDeviceOs("iOS 18");
        entite.setIsActive(true);
        entite.setCounter(42L);
        entite.setAttestationFormat("packed");
        entite.setRegisteredAt(LocalDateTime.now());
        entite.setLastUsedAt(LocalDateTime.now());
    }

    private String corpsJson() throws Exception {
        return json.writeValueAsString(BiometricCredentialResponse.de(entite));
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui ne sort plus")
    class NeSortPlus {

        @Test
        @DisplayName("la clé publique de l'authentificateur n'est pas renvoyée")
        void clePubliqueAbsente() throws Exception {
            assertThat(corpsJson())
                    .withFailMessage("la clé publique part au client : elle était la "
                            + "pièce manquante de l'ancienne attaque par usurpation")
                    .doesNotContain("CLE-PUBLIQUE-QUI-NE-DOIT-PAS-SORTIR")
                    .doesNotContain("publicKey");
        }

        @Test
        @DisplayName("l'aaguid n'est pas renvoyé - c'est la marque et le modèle du matériel")
        void aaguidAbsent() throws Exception {
            // L'aaguid designe le modele exact de l'authentificateur. Il
            // contribue a l'empreinte unique de la personne, sans servir a rien
            // cote client.
            assertThat(corpsJson())
                    .doesNotContain("AAGUID-MODELE-DU-MATERIEL")
                    .doesNotContain("aaguid");
        }

        @Test
        @DisplayName("le compteur anti-clonage n'est pas renvoyé")
        void compteurAbsent() throws Exception {
            // Connaitre l'etat du compteur permettrait de choisir une valeur
            // credible si sa verification devenait contournable.
            assertThat(corpsJson()).doesNotContain("counter");
        }

        @Test
        @DisplayName("l'identifiant stable d'appareil n'est pas renvoyé")
        void deviceIdAbsent() throws Exception {
            // C'est un traceur : il relie des sessions entre elles. deviceName
            // et deviceOs suffisent a ce que l'utilisateur reconnaisse son
            // appareil.
            assertThat(corpsJson())
                    .doesNotContain("IDENTIFIANT-STABLE-APPAREIL")
                    .doesNotContain("deviceId");
        }

        @Test
        @DisplayName("l'identifiant interne de l'utilisateur n'est pas renvoyé")
        void userIdAbsent() throws Exception {
            // L'appelant est deja l'utilisateur : le lui renvoyer n'apporte
            // rien, et l'inclure dans une reponse de liste invite a construire
            // des endpoints qui acceptent un userId en entree.
            assertThat(corpsJson()).doesNotContain("userId");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Ce qui doit rester, pour que l'endpoint serve encore")
    class ResteUtile {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "credentialId", "biometricType", "deviceName",
                "deviceOs", "isActive", "registeredAt"
        })
        @DisplayName("les champs nécessaires à l'usage réel sont présents")
        void champsUtiles(String champ) throws Exception {
            // Retirer trop est l'autre facon de casser l'endpoint. L'usage reel
            // est : reconnaitre ses appareils pour en revoquer un.
            assertThat(corpsJson())
                    .withFailMessage("%s a disparu : l'utilisateur ne peut plus "
                            + "reconnaître ses appareils ni en révoquer un", champ)
                    .contains(champ);
        }

        @Test
        @DisplayName("credentialId est conservé car DELETE /credentials/{id} l'exige")
        void credentialIdConserve() throws Exception {
            // Sans lui, la revocation d'un appareil devient impossible — le
            // retirer aurait rendu l'endpoint de suppression inutilisable.
            assertThat(corpsJson()).contains("credential-abc");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Garde-fou contre une régression future")
    class GardeFou {

        @Test
        @DisplayName("aucun champ au nom sensible ne peut être ajouté au DTO")
        void aucunChampSensibleAjouteParInadvertance() {
            // CE TEST EST LE PLUS IMPORTANT DU FICHIER.
            //
            // Verifier l'absence des quatre champs connus ne protege que du
            // passe. Ce controle porte sur la FORME du DTO : tout champ dont le
            // nom evoque du materiel cryptographique, un identifiant de
            // materiel ou un etat interne fait echouer le test, meme s'il
            // n'existe pas encore aujourd'hui.
            List<String> interdits = List.of(
                    "publickey", "privatekey", "secret", "aaguid", "counter",
                    "attestation", "deviceid", "userid", "signature", "challenge");

            List<String> fautifs = Arrays.stream(
                            BiometricCredentialResponse.class.getDeclaredFields())
                    .filter(f -> !f.isSynthetic())
                    .map(Field::getName)
                    .filter(nom -> interdits.stream()
                            .anyMatch(i -> nom.toLowerCase().contains(i)))
                    .toList();

            assertThat(fautifs)
                    .withFailMessage("""
                            Ces champs du DTO portent un nom sensible : %s

                            La réponse de GET /auth/biometric/credentials est
                            publique pour l'appelant. Si ce champ est réellement
                            nécessaire, il faut le justifier et retirer son nom de
                            la liste des interdits — pas l'ajouter en silence.""",
                            fautifs)
                    .isEmpty();
        }

        @Test
        @DisplayName("le DTO expose MOINS de champs que l'entité")
        void dtoPlusPauvreQueLEntite() {
            // Si les deux comptes devenaient egaux, c'est que quelqu'un aurait
            // recopie l'entite champ par champ — et le DTO n'aurait plus
            // d'objet.
            long champsDto = Arrays.stream(
                            BiometricCredentialResponse.class.getDeclaredFields())
                    .filter(f -> !f.isSynthetic()).count();
            long champsEntite = Arrays.stream(BiometricCredential.class.getDeclaredFields())
                    .filter(f -> !f.isSynthetic()).count();

            assertThat(champsDto)
                    .withFailMessage("le DTO a %d champs pour %d dans l'entité : "
                            + "il ne filtre plus rien", champsDto, champsEntite)
                    .isLessThan(champsEntite);
        }
    }
}
