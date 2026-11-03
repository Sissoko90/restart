package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'echange de code chez Apple, de bout en bout.
 *
 * <h2>Ce qui distingue cet echange de celui de Google</h2>
 *
 * Apple n'a <b>aucun</b> point de profil. L'identite n'est pas obtenue par un
 * second appel avec le jeton d'acces : elle est <b>dans</b> le {@code id_token}
 * rendu par l'echange. Toute la securite de la connexion Apple tient donc a la
 * verification de ce jeton, testee en detail dans
 * {@link ClesPubliquesAppleTest} — en particulier le controle de l'audience, qui
 * est la faille publiee en 2020.
 *
 * <p>Cette suite verifie ce qui l'entoure : que le jeton est bien soumis a cette
 * verification, et qu'une reponse incomplete ne produit pas un compte vide.
 */
@DisplayName("FournisseurApple — echange du code d'autorisation")
class FournisseurAppleTest {

    private static final String KID = "apple-kid-essai";

    private static KeyPair apple;
    private static String jwks;

    @BeforeAll
    static void preparerUneFausseAutoriteApple() throws Exception {
        KeyPairGenerator generateur = KeyPairGenerator.getInstance("RSA");
        generateur.initialize(2048);
        apple = generateur.generateKeyPair();

        var jwk = Jwks.builder().key((RSAPublicKey) apple.getPublic()).id(KID).build();
        jwks = new ObjectMapper().writeValueAsString(Map.of("keys", List.of(jwk)));
    }

    private static String idToken(String audience, String adresse, Object emailVerified) {
        var constructeur = Jwts.builder()
                .header().keyId(KID).and()
                .issuer(ClesPubliquesApple.EMETTEUR)
                .subject("001234.abcdef.5678")
                .audience().add(audience).and()
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(Instant.now().plusSeconds(600)));
        if (adresse != null) {
            constructeur.claim("email", adresse);
        }
        if (emailVerified != null) {
            constructeur.claim("email_verified", emailVerified);
        }
        return constructeur.signWith(apple.getPrivate(), Jwts.SIG.RS256).compact();
    }

    /** Autorite de cles servant le JWKS d'essai. */
    private static ClesPubliquesApple autoriteDeCles() {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(jwks)
                        .build()));
        return new ClesPubliquesApple(constructeur, AppleDEssai.disjoncteur(),
                AppleDEssai.SERVICES_ID, 5);
    }

    /** Fournisseur dont l'echange rend le corps donne. */
    private static FournisseurApple avecReponse(String corps) {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(corps)
                        .build()));
        return AppleDEssai.configure(constructeur, autoriteDeCles());
    }

    private static String reponseAvec(String jeton) {
        return "{\"access_token\":\"at-apple\",\"refresh_token\":\"rt-apple\","
                + "\"expires_in\":3600,\"id_token\":\"" + jeton + "\"}";
    }

    // ==================================================================
    @Nested
    @DisplayName("L'identite vient du id_token")
    class IdentiteDuJeton {

        @Test
        @DisplayName("le sub d'Apple devient l'identifiant du fournisseur")
        void subDevientProviderId() {
            // Le « sub » est stable pour cette application et ne change pas si
            // la personne change d'adresse. C'est la seule cle de liaison
            // fiable : lier sur l'adresse perdrait le compte au premier
            // changement.
            var fournisseur = avecReponse(reponseAvec(
                    idToken(AppleDEssai.SERVICES_ID, "personne@exemple.test", true)));

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .assertNext(info -> {
                        assertThat(info.getProviderId()).isEqualTo("001234.abcdef.5678");
                        assertThat(info.getAccessToken()).isEqualTo("at-apple");
                        assertThat(info.getExpiresIn()).isEqualTo(3600L);
                    })
                    .verifyComplete();
        }

        /**
         * La consequence de l'absence de point de profil chez Apple.
         *
         * <p>Apple ne transmet le nom que dans le champ {@code user} du
         * formulaire de rappel, et <b>uniquement a la premiere autorisation</b>.
         * Il n'arrive donc jamais par l'echange de code. Le test fixe cette
         * limite pour qu'elle ne soit pas prise pour un defaut : c'est le rappel
         * qui doit enregistrer le nom, et la deuxieme connexion n'apportera
         * plus rien.
         */
        @Test
        @DisplayName("le nom n'arrive PAS par l'echange : Apple ne l'envoie qu'au rappel")
        void nomAbsentDeLEchange() {
            var fournisseur = avecReponse(reponseAvec(
                    idToken(AppleDEssai.SERVICES_ID, "personne@exemple.test", true)));

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .assertNext(info -> assertThat(info.getName()).isNull())
                    .verifyComplete();
        }

        @Test
        @DisplayName("une adresse de relais prive est acceptee")
        void adresseDeRelaisAcceptee() {
            // « Masquer mon adresse » est le choix propose par defaut par Apple.
            // Refuser ce domaine reviendrait a refuser la connexion a une
            // grande partie des personnes qui passent par Apple.
            String relais = "abc123" + AppConstants.APPLE_PRIVATE_RELAY_DOMAIN;
            var fournisseur = avecReponse(reponseAvec(
                    idToken(AppleDEssai.SERVICES_ID, relais, true)));

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .assertNext(info -> {
                        assertThat(info.getEmail()).isEqualTo(relais);
                        assertThat(info.getEmailVerified()).isTrue();
                    })
                    .verifyComplete();
        }

        /**
         * Apple envoie {@code email_verified} tantot en booleen, tantot en
         * chaine.
         *
         * <p>Le lire uniquement comme un booleen rend faux pour la chaine
         * {@code "true"} : une adresse verifiee par Apple serait alors traitee
         * comme non verifiee, et la personne se verrait demander une
         * verification de plus — pour une adresse qu'Apple a deja verifiee.
         */
        @Test
        @DisplayName("email_verified est lu qu'il soit booleen ou chaine")
        void emailVerifieDansLesDeuxFormes() {
            for (Object forme : new Object[]{true, "true"}) {
                var fournisseur = avecReponse(reponseAvec(
                        idToken(AppleDEssai.SERVICES_ID, "a@exemple.test", forme)));

                StepVerifier.create(fournisseur.echangerLeCode("code"))
                        .assertNext(info -> assertThat(info.getEmailVerified())
                                .withFailMessage("email_verified sous la forme %s "
                                        + "(%s) est lu comme non verifie", forme,
                                        forme.getClass().getSimpleName())
                                .isTrue())
                        .verifyComplete();
            }
        }

        @Test
        @DisplayName("email_verified faux reste faux")
        void emailNonVerifie() {
            var fournisseur = avecReponse(reponseAvec(
                    idToken(AppleDEssai.SERVICES_ID, "a@exemple.test", "false")));

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .assertNext(info -> assertThat(info.getEmailVerified()).isFalse())
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le id_token est reellement verifie")
    class JetonVerifie {

        /**
         * Le lien entre cette classe et la faille de 2020.
         *
         * <p>{@link ClesPubliquesAppleTest} prouve que la verification refuse un
         * jeton destine a une autre application. Ce test-ci prouve que
         * l'echange <b>passe bien par</b> cette verification : une
         * implementation qui lirait les revendications sans verifier produirait
         * exactement le meme resultat sur tous les tests precedents.
         */
        @Test
        @DisplayName("un jeton emis pour une AUTRE application fait echouer l'echange")
        void jetonDUneAutreApplicationRefuse() {
            var fournisseur = avecReponse(reponseAvec(
                    idToken("com.autre.application", "a@exemple.test", true)));

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    L'echange accepte un id_token emis pour une \
                                    autre application.

                                    Le jeton est authentique — signe par Apple, bon \
                                    emetteur, non expire — mais il ne nous est pas \
                                    destine. C'est la faille publiee sur Sign in \
                                    with Apple en 2020. Le resultat de ce test est \
                                    le seul qui distingue une verification reelle \
                                    d'une simple lecture des revendications.""")
                            .isInstanceOf(AuthException.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une reponse incomplete n'est pas un succes")
    class ReponseIncomplete {

        @Test
        @DisplayName("une reponse 200 SANS id_token est une erreur, pas un compte vide")
        void sansIdTokenEstUneErreur() {
            // Sans id_token il n'y a aucune identite. Continuer avec des champs
            // nuls creerait un compte sans identifiant de fournisseur, donc un
            // compte que personne ne peut plus retrouver — et un deuxieme a la
            // connexion suivante.
            var fournisseur = avecReponse("{\"access_token\":\"at\",\"expires_in\":3600}");

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
        }

        @Test
        @DisplayName("un id_token vide est traite comme absent")
        void idTokenVide() {
            var fournisseur = avecReponse("{\"id_token\":\"\"}");

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("un corps vide rend une erreur et non un Mono vide")
        void corpsVide() {
            // Le piege du Mono vide : la chaine d'authentification se
            // terminerait sans jeton ni refus, et le client attendrait son
            // propre delai d'expiration.
            WebClient.Builder constructeur = WebClient.builder()
                    .exchangeFunction(requete -> Mono.just(
                            ClientResponse.create(HttpStatus.OK).build()));
            var fournisseur = AppleDEssai.configure(constructeur, autoriteDeCles());

            StepVerifier.create(fournisseur.echangerLeCode("code"))
                    .verifyError(AuthException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'URL d'autorisation respecte les exigences d'Apple")
    class UrlDAutorisation {

        /**
         * Les deux parametres qu'Apple impose, et qui n'ont pas d'equivalent
         * chez Google ni chez Facebook.
         */
        @Test
        @DisplayName("response_type demande le id_token, et response_mode est form_post")
        void parametresImposesParApple() {
            String url = AppleDEssai.configure().urlDAutorisation("etat-123");

            assertThat(url)
                    .withFailMessage("sans « id_token » dans response_type, Apple "
                            + "ne renvoie aucune identite — et il n'y a aucun "
                            + "point de profil pour la recuperer")
                    .contains("response_type=code+id_token");
            assertThat(url)
                    .withFailMessage("Apple EXIGE form_post des que le perimetre "
                            + "demande name ou email, et refuse l'autorisation "
                            + "sinon")
                    .contains("response_mode=form_post");
            assertThat(url).contains("state=etat-123");
        }
    }
}
