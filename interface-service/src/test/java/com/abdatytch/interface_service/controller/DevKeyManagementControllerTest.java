package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.service.IDevKeyRotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

/**
 * Les quatre endpoints de gestion des clés de signature.
 *
 * <h2>Pourquoi ce test manquait, et ce qu'il garde</h2>
 *
 * Ce contrôleur n'avait aucun test. {@code ChaineReelleTest} exerce bien les
 * règles d'autorisation qui le protègent, mais il le fait sur une route
 * attrape-tout : le <b>corps</b> du contrôleur n'était jamais appelé.
 *
 * <p>Or c'est là que vivait le défaut. {@code rotateKeys()} du service rendait
 * un {@code Mono} <b>vide</b> quand la rotation est désactivée — ce qui est le
 * cas par défaut — et le {@code .then(...)} de ce contrôleur transformait ce
 * vide en {@code 200 {"status":"success","message":"rotation completed
 * successfully"}}, avec un {@code newKeyId} inchangé.
 *
 * <p>La leçon du dépôt s'applique telle quelle : un {@code Mono} vide qui
 * traverse un opérateur ne fait pas pendre la requête, il produit une réponse
 * de succès vide de sens. C'est pire qu'une erreur, qui se voit.
 *
 * <h2>Pourquoi {@code bindToController} et non {@code @WebFluxTest}</h2>
 *
 * {@code @WebFluxTest} embarque les {@code WebFilter} annotés
 * {@code @Component} — dont le filtre d'accès de la passerelle, qui refuserait
 * toute requête non signée. Et il coûte des secondes là où ceci coûte des
 * millisecondes. L'autorisation est couverte par {@code ChaineReelleTest} ; ici
 * on exerce la logique de réponse, et elle seule.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DevKeyManagementController - gestion des cles de signature")
class DevKeyManagementControllerTest {

    private static final String KID_COURANT = "kid-courant-aaa";

    /** Engendree une fois : une paire RSA coute cher, et son contenu importe peu. */
    private static KeyPair paire;

    @Mock
    private IDevKeyRotationService keyRotationService;

    private WebTestClient client;

    @BeforeEach
    void preparer() throws Exception {
        if (paire == null) {
            KeyPairGenerator generateur = KeyPairGenerator.getInstance("RSA");
            generateur.initialize(2048);
            paire = generateur.generateKeyPair();
        }

        when(keyRotationService.getCurrentKeyId()).thenReturn(KID_COURANT);
        when(keyRotationService.getAllValidPublicKeysById())
                .thenReturn(Map.of(KID_COURANT, paire.getPublic()));
        when(keyRotationService.getAllValidPublicKeys())
                .thenReturn(List.of(paire.getPublic()));
        when(keyRotationService.isRotationNeeded()).thenReturn(false);

        client = WebTestClient
                .bindToController(new DevKeyManagementController(keyRotationService))
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("La rotation dit la verite sur ce qu'elle a fait")
    class RotationDitLaVerite {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>Rotation désactivée : la réponse doit dire qu'elle a été
         * <b>refusée</b>, et le code doit être 409 — la requête est bien formée,
         * mais l'état courant du service l'interdit. Un 500 dirait que le
         * service est cassé alors qu'il manque un réglage ; un 200 mentait.
         */
        @Test
        @DisplayName("Rotation desactivee : 409 refused, et le kid courant INCHANGE")
        void rotationDesactivee() {
            when(keyRotationService.rotateKeys()).thenReturn(Mono.error(
                    new IllegalStateException("La rotation des cles est desactivee.")));

            String corps = client.post().uri("/dev/admin/keys/rotate")
                    .exchange()
                    .expectStatus().isEqualTo(409)
                    .expectBody(String.class)
                    .returnResult().getResponseBody();

            org.assertj.core.api.Assertions.assertThat(corps)
                    .as("la reponse doit dire que la rotation a ete REFUSEE, "
                            + "nommer le motif, et ne presenter aucune cle neuve")
                    .contains("\"status\":\"refused\"")
                    .contains("desactivee")
                    // Le kid COURANT, nomme comme tel : l'ancienne reponse
                    // publiait ce meme identifiant sous le nom « newKeyId »,
                    // ce qui le faisait passer pour une cle neuve.
                    .contains("\"currentKeyId\":\"" + KID_COURANT + "\"")
                    .doesNotContain("newKeyId");
        }

        @Test
        @DisplayName("Rotation reussie : 200 success avec le NOUVEL identifiant")
        void rotationReussie() {
            when(keyRotationService.rotateKeys()).thenReturn(Mono.empty());
            when(keyRotationService.getCurrentKeyId()).thenReturn("kid-tout-neuf");

            client.post().uri("/dev/admin/keys/rotate")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("success")
                    .jsonPath("$.newKeyId").isEqualTo("kid-tout-neuf");
        }

        /**
         * Une panne réelle reste une erreur serveur.
         *
         * <p>Distinguer le refus de la panne est tout l'objet du correctif :
         * les confondre fait chercher une panne là où il manque un réglage, ou
         * l'inverse.
         */
        @Test
        @DisplayName("Panne reelle : 500 error, et non 409")
        void panneReelle() {
            when(keyRotationService.rotateKeys()).thenReturn(Mono.error(
                    new RuntimeException("Redis injoignable")));

            client.post().uri("/dev/admin/keys/rotate")
                    .exchange()
                    .expectStatus().is5xxServerError()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("error");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le JWKS : la forme compte autant que le contenu")
    class FormeDuJwks {

        /**
         * Le {@code kid} publié doit être celui du service de rotation.
         *
         * <p>C'est la source unique de vérité. S'il différait de celui inscrit
         * dans l'en-tête des jetons, chaque consommateur chercherait une clé
         * inexistante et refuserait tout — une panne totale d'authentification,
         * sans message qui désigne la cause.
         */
        @Test
        @DisplayName("Le kid, le type et l'algorithme sont ceux attendus")
        void formeDeLaCle() {
            client.get().uri("/dev/.well-known/jwks.json")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.keys.length()").isEqualTo(1)
                    .jsonPath("$.keys[0].kid").isEqualTo(KID_COURANT)
                    .jsonPath("$.keys[0].kty").isEqualTo("RSA")
                    .jsonPath("$.keys[0].alg").isEqualTo("RS256")
                    .jsonPath("$.keys[0].use").isEqualTo("sig");
        }

        /**
         * Le modulo se recompose exactement en celui de la clé.
         *
         * <p>C'est la propriété qui compte vraiment : un consommateur qui lit ce
         * JWKS doit retrouver la clé qui a signé le jeton. Comparer des chaînes
         * ne dirait rien ; comparer des <b>nombres</b> dit si la validation
         * réussira.
         */
        @Test
        @DisplayName("Le modulo publie se recompose EXACTEMENT en celui de la cle")
        void moduloRecomposable() {
            RSAPublicKey attendue = (RSAPublicKey) paire.getPublic();
            String json = jwks();

            BigInteger moduloPublie = new BigInteger(1,
                    Base64.getUrlDecoder().decode(extraire(json, "n")));
            BigInteger exposantPublie = new BigInteger(1,
                    Base64.getUrlDecoder().decode(extraire(json, "e")));

            org.assertj.core.api.Assertions.assertThat(moduloPublie)
                    .as("un consommateur doit retrouver la cle qui a signe le jeton")
                    .isEqualTo(attendue.getModulus());
            org.assertj.core.api.Assertions.assertThat(exposantPublie)
                    .isEqualTo(attendue.getPublicExponent());
        }

        /**
         * Le modulo est encodé sans zéro de tête, comme la RFC 7518 l'exige.
         *
         * <h3>Ce que ce test garde, et ce qu'il ne faut PAS en dire</h3>
         *
         * {@code BigInteger.toByteArray()} rend une représentation en complément
         * à deux : pour un modulo RSA-2048, dont le bit de poids fort est
         * toujours à 1, elle fait <b>257</b> octets dont le premier est nul.
         * {@code base64UrlEncode} retire cet octet.
         *
         * <p>J'avais d'abord écrit que publier cet octet ferait reconstruire une
         * autre clé et refuser tous les jetons. <b>C'est faux</b>, et la
         * vérification par mutation l'a montré : l'assertion de recomposition
         * ci-dessus ne détecte pas le retrait de ce retrait. La raison est que
         * {@code new BigInteger(1, octets)} traite son argument comme une
         * magnitude et <b>ignore les zéros de tête</b> — et c'est exactement
         * ainsi que Nimbus décode, via {@code Base64URL.decodeToBigInteger()}.
         * Le modulo est donc numériquement identique, et la validation réussit.
         *
         * <p>Ce qui reste vrai : la RFC 7518 §6.3.1.1 exige la représentation
         * non signée <b>de longueur minimale</b>. L'octet superflu est une
         * non-conformité, qu'un consommateur strict peut refuser, et que rien ne
         * signalerait — le JWKS aurait l'air normal. Ce test garde donc la
         * conformité, sur la <b>longueur</b> et non sur la valeur, parce que
         * c'est la seule façon de l'observer.
         */
        @Test
        @DisplayName("Le modulo respecte la RFC 7518 : aucun zero de tete")
        void moduloSansZeroDeTete() {
            RSAPublicKey attendue = (RSAPublicKey) paire.getPublic();
            byte[] publie = Base64.getUrlDecoder().decode(extraire(jwks(), "n"));

            int octetsAttendus = (attendue.getModulus().bitLength() + 7) / 8;

            org.assertj.core.api.Assertions.assertThat(publie)
                    .as("RFC 7518 §6.3.1.1 : representation non signee de longueur "
                            + "MINIMALE. BigInteger.toByteArray() ajoute un octet nul "
                            + "de signe pour un modulo RSA, et il doit etre retire.")
                    .hasSize(octetsAttendus);
            org.assertj.core.api.Assertions.assertThat(publie[0])
                    .as("le premier octet d'un modulo RSA ne peut pas etre nul")
                    .isNotEqualTo((byte) 0);
        }

        @Test
        @DisplayName("Aucune cle valide : un ensemble VIDE, et non une erreur")
        void aucuneCle() {
            // Un JWKS vide est une reponse legitime : elle dit « je n'ai rien a
            // publier ». Une erreur ferait croire le service en panne, et les
            // consommateurs reessaieraient en boucle.
            when(keyRotationService.getAllValidPublicKeysById()).thenReturn(Map.of());

            client.get().uri("/dev/.well-known/jwks.json")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.keys.length()").isEqualTo(0);
        }

        /**
         * Le JWKS est mis en cache une heure, et il le doit.
         *
         * <p>Huit services le lisent pour valider les jetons. Sans cet en-tête,
         * chaque validation pourrait déclencher un appel réseau vers l'autorité
         * JWT — qui se retrouverait sur le chemin de chaque requête de la
         * plateforme.
         */
        @Test
        @DisplayName("Le JWKS porte son en-tete de cache")
        void enTeteDeCache() {
            client.get().uri("/dev/.well-known/jwks.json")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueMatches("Cache-Control", ".*max-age=3600.*");
        }

        /** Le corps brut du JWKS, pour les assertions qui portent sur l'encodage. */
        private String jwks() {
            byte[] corps = client.get().uri("/dev/.well-known/jwks.json")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();
            return new String(corps, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** Extrait la valeur d'un champ du premier JWK, sans dependre d'un parseur. */
        private String extraire(String json, String champ) {
            var motif = java.util.regex.Pattern
                    .compile("\"" + champ + "\"\\s*:\\s*\"([^\"]+)\"");
            var trouve = motif.matcher(json);
            if (!trouve.find()) {
                throw new AssertionError("champ '" + champ + "' absent du JWKS : " + json);
            }
            return trouve.group(1);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le statut et le nettoyage")
    class StatutEtNettoyage {

        @Test
        @DisplayName("Le statut nomme la cle courante, le nombre de cles et l'emetteur")
        void statut() {
            client.get().uri("/dev/admin/keys/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.currentKeyId").isEqualTo(KID_COURANT)
                    .jsonPath("$.totalValidKeys").isEqualTo(1)
                    .jsonPath("$.rotationNeeded").isEqualTo(false)
                    .jsonPath("$.issuer").isEqualTo("interface-service")
                    // L'audience annoncee ici doit rester celle que les jetons
                    // portent reellement. docs/JWT-CONTRACT.md etait reste a
                    // l'ancien nom apres le renommage, et qui recopiait cette
                    // valeur configurait une audience que personne n'emet.
                    .jsonPath("$.audience").isEqualTo("restart-developers");
        }

        @Test
        @DisplayName("Le nettoyage rend le NOMBRE de cles retirees")
        void nettoyage() {
            when(keyRotationService.cleanupExpiredKeys()).thenReturn(Mono.just(3));

            client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/dev/admin/keys/expired")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("success")
                    .jsonPath("$.removedKeys").isEqualTo(3);
        }

        @Test
        @DisplayName("Zero cle retiree reste un succes, et le dit")
        void nettoyageSansRien() {
            // Zero n'est pas un echec : c'est le cas normal quand aucune cle
            // n'a encore expire. Le rendre en erreur ferait croire a une panne
            // a chaque passage du planificateur.
            when(keyRotationService.cleanupExpiredKeys()).thenReturn(Mono.just(0));

            client.method(org.springframework.http.HttpMethod.DELETE)
                    .uri("/dev/admin/keys/expired")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.removedKeys").isEqualTo(0);
        }
    }
}
