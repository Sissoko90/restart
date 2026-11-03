package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.service.IDisjoncteurSortant;
import com.abdatytch.auth_service.service.impl.DisjoncteurSortantServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification du jeton d'identite Apple.
 *
 * <h2>Le test le plus important de cette suite</h2>
 *
 * {@code audienceDUneAutreApplicationRefusee}. Sans le controle de
 * {@code aud}, un jeton d'identite Apple <b>parfaitement valide</b> — signe par
 * Apple, non expire, emis par {@code appleid.apple.com} — mais obtenu par
 * <b>une autre application</b> serait accepte ici, et permettrait de prendre le
 * compte correspondant.
 *
 * <p>Ce n'est pas une hypothese : c'est la faille publiee sur Sign in with
 * Apple en 2020. Tout le reste de la verification peut etre juste et laisser
 * cette porte ouverte, parce que le jeton <b>est</b> authentique — il n'est
 * simplement pas destine a nous.
 *
 * <h2>Pourquoi une fausse autorite Apple</h2>
 *
 * La suite genere sa propre paire RSA et publie un JWKS de son cru. Elle verifie
 * donc la <b>logique</b> de verification sans reseau, sans compte Apple, et de
 * façon deterministe. Utiliser le vrai JWKS d'Apple rendrait les tests
 * dependants d'Internet et de la rotation de ses cles.
 */
@DisplayName("ClesPubliquesApple — verification du jeton d'identite")
class ClesPubliquesAppleTest {

    private static final String NOTRE_SERVICES_ID = "com.exemple.restart.web";
    private static final String KID = "apple-kid-1";

    private static KeyPair apple;
    private static KeyPair imposteur;
    private static String jwks;

    @BeforeAll
    static void preparerUneFausseAutoriteApple() throws Exception {
        KeyPairGenerator generateur = KeyPairGenerator.getInstance("RSA");
        generateur.initialize(2048);
        apple = generateur.generateKeyPair();
        imposteur = generateur.generateKeyPair();

        var jwk = Jwks.builder().key((java.security.interfaces.RSAPublicKey) apple.getPublic())
                .id(KID)
                .build();
        jwks = new ObjectMapper().writeValueAsString(Map.of("keys", List.of(jwk)));
    }

    /** Jeton signe par « Apple », avec les revendications demandees. */
    private static String jeton(String emetteur, String audience, Instant echeance,
                                KeyPair signataire) {
        return Jwts.builder()
                .header().keyId(KID).and()
                .issuer(emetteur)
                .subject("001234.abcdef.5678")
                .audience().add(audience).and()
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(echeance))
                .claim("email", "personne@privaterelay.appleid.com")
                .signWith(signataire.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private static String jetonValide() {
        return jeton(ClesPubliquesApple.EMETTEUR, NOTRE_SERVICES_ID,
                Instant.now().plusSeconds(600), apple);
    }

    private static IDisjoncteurSortant disjoncteurReel() {
        return new DisjoncteurSortantServiceImpl(
                CircuitBreakerRegistry.ofDefaults(), 50f, 100, 100, 30L);
    }

    /** Fausse autorite servant {@code jwks}, et comptant ses appels. */
    private static ClesPubliquesApple autorite(AtomicInteger appels, String corps) {
        WebClient.Builder constructeur = WebClient.builder()
                .exchangeFunction(requete -> {
                    appels.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .body(corps)
                            .build());
                });
        return new ClesPubliquesApple(constructeur, disjoncteurReel(), NOTRE_SERVICES_ID, 5);
    }

    // ==================================================================
    @Nested
    @DisplayName("Les trois controles qui ferment une attaque")
    class TroisControles {

        @Test
        @DisplayName("un jeton valide et destine a NOUS est accepte")
        void jetonValideAccepte() {
            var cles = autorite(new AtomicInteger(), jwks);

            StepVerifier.create(cles.verifier(jetonValide()))
                    .assertNext(revendications -> assertThat(revendications.getSubject())
                            .isEqualTo("001234.abcdef.5678"))
                    .verifyComplete();
        }

        /**
         * La faille de 2020, et le test qui la ferme.
         *
         * <p>Le jeton est authentique de bout en bout : signe par Apple, emis
         * par {@code appleid.apple.com}, non expire. Il est simplement destine
         * a une <b>autre</b> application. L'accepter permettrait a qui detient
         * un jeton obtenu ailleurs de prendre le compte correspondant chez nous.
         */
        @Test
        @DisplayName("un jeton emis pour une AUTRE application est refuse")
        void audienceDUneAutreApplicationRefusee() {
            var cles = autorite(new AtomicInteger(), jwks);
            String pourQuelquunDautre = jeton(ClesPubliquesApple.EMETTEUR,
                    "com.autre.application", Instant.now().plusSeconds(600), apple);

            StepVerifier.create(cles.verifier(pourQuelquunDautre))
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur)
                                .withFailMessage("""
                                        Un jeton Apple AUTHENTIQUE mais emis pour une \
                                        autre application est accepte.

                                        C'est la faille publiee sur Sign in with Apple en \
                                        2020 : la signature est valide, l'emetteur est le \
                                        bon, l'echeance est bonne — seul `aud` differe. \
                                        Qui obtient un jeton par une autre application \
                                        peut prendre le compte correspondant ici.""")
                                .isInstanceOf(AuthException.class);
                        assertThat(((AuthException) erreur).getErrorCode())
                                .isEqualTo(ErrorCode.TOKEN_INVALID);
                    });
        }

        @Test
        @DisplayName("un jeton d'un autre emetteur est refuse")
        void autreEmetteurRefuse() {
            var cles = autorite(new AtomicInteger(), jwks);
            String autreEmetteur = jeton("https://pas-apple.example",
                    NOTRE_SERVICES_ID, Instant.now().plusSeconds(600), apple);

            StepVerifier.create(cles.verifier(autreEmetteur))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("un jeton signe par une autre cle est refuse, meme avec le bon kid")
        void signatureImposteurRefusee() {
            // Le kid annonce la cle d'Apple, mais la signature vient d'ailleurs.
            // C'est l'attaque la plus directe : forger un jeton en pretendant
            // qu'il vient d'Apple.
            var cles = autorite(new AtomicInteger(), jwks);
            String forge = jeton(ClesPubliquesApple.EMETTEUR, NOTRE_SERVICES_ID,
                    Instant.now().plusSeconds(600), imposteur);

            StepVerifier.create(cles.verifier(forge))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("un jeton expire est refuse")
        void jetonExpireRefuse() {
            var cles = autorite(new AtomicInteger(), jwks);
            String expire = jeton(ClesPubliquesApple.EMETTEUR, NOTRE_SERVICES_ID,
                    Instant.now().minusSeconds(10), apple);

            StepVerifier.create(cles.verifier(expire))
                    .verifyError(AuthException.class);
        }

        @Test
        @DisplayName("un jeton vide ou mal forme est refuse sans appeler Apple")
        void jetonMalFormeRefuse() {
            AtomicInteger appels = new AtomicInteger();
            var cles = autorite(appels, jwks);

            StepVerifier.create(cles.verifier("")).verifyError(AuthException.class);
            StepVerifier.create(cles.verifier("pas-un-jeton")).verifyError(AuthException.class);

            assertThat(appels.get())
                    .withFailMessage("un jeton mal forme declenche un appel a Apple : "
                            + "n'importe qui pourrait nous faire marteler son JWKS")
                    .isZero();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le rechargement est borne")
    class RechargementBorne {

        /**
         * Sans cette borne, un {@code kid} aleatoire suffit a nous faire
         * appeler Apple.
         *
         * <p>Apple fait tourner ses cles, donc un {@code kid} inconnu doit bien
         * declencher un rechargement — mais une fois, pas a chaque jeton. Sinon
         * il suffit d'envoyer des jetons portant des {@code kid} aleatoires pour
         * nous faire limiter par Apple, ce qui empecherait les connexions
         * legitimes. L'attaquant obtient le deni de service sans rien casser.
         */
        @Test
        @DisplayName("un kid inconnu ne declenche qu'UN appel, pas un par jeton")
        void kidInconnuNeMartelePas() {
            AtomicInteger appels = new AtomicInteger();
            var cles = autorite(appels, jwks);

            String avecKidInconnu = Jwts.builder()
                    .header().keyId("kid-force-par-un-attaquant").and()
                    .issuer(ClesPubliquesApple.EMETTEUR)
                    .subject("x")
                    .audience().add(NOTRE_SERVICES_ID).and()
                    .expiration(Date.from(Instant.now().plusSeconds(600)))
                    .signWith(imposteur.getPrivate(), Jwts.SIG.RS256)
                    .compact();

            for (int i = 0; i < 5; i++) {
                StepVerifier.create(cles.verifier(avecKidInconnu))
                        .verifyError(AuthException.class);
            }

            assertThat(appels.get())
                    .withFailMessage("""
                            %d appels au JWKS d'Apple pour 5 jetons a kid inconnu.

                            Un seul est attendu : au-dela, un attaquant nous fait \
                            marteler Apple en envoyant des kid aleatoires, et Apple \
                            finit par nous limiter — ce qui empeche les connexions \
                            legitimes.""", appels.get())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("une cle connue ne declenche aucun appel supplementaire")
        void cleConnueNeRechargePas() {
            AtomicInteger appels = new AtomicInteger();
            var cles = autorite(appels, jwks);

            for (int i = 0; i < 3; i++) {
                StepVerifier.create(cles.verifier(jetonValide()))
                        .expectNextCount(1)
                        .verifyComplete();
            }

            assertThat(appels.get())
                    .withFailMessage("le cache ne sert a rien : un appel a Apple par "
                            + "connexion s'ajouterait au chemin d'authentification")
                    .isEqualTo(1);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un JWKS inexploitable n'est pas un succes")
    class JwksInexploitable {

        @Test
        @DisplayName("un corps vide rend une indisponibilite, pas un cache vide")
        void corpsVideEstUneIndisponibilite() {
            // Le piege du Mono vide, corrige une dizaine de fois dans ce depot :
            // un rechargement « reussi » sans aucune cle laisserait toute
            // connexion Apple echouer avec « jeton invalide », ce qui accuse le
            // jeton au lieu de la dependance.
            var cles = autorite(new AtomicInteger(), "");

            StepVerifier.create(cles.recharger())
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }

        @Test
        @DisplayName("un JWKS que jjwt refuse de lire est une indisponibilite, pas un 500")
        void jwksIllisible() {
            // jjwt leve ses propres exceptions ici (jeu vide, JSON invalide).
            // Les laisser remonter ferait sortir une exception de bibliotheque
            // jusqu'au gestionnaire d'erreurs, qui rendrait 500 pour une simple
            // indisponibilite d'Apple.
            for (String corps : new String[]{"{\"keys\":[]}", "{", "pas du json"}) {
                var cles = autorite(new AtomicInteger(), corps);

                StepVerifier.create(cles.recharger())
                        .verifyErrorSatisfies(erreur -> {
                            assertThat(erreur)
                                    .withFailMessage("une exception jjwt remonte telle "
                                            + "quelle : l'appelant rendra 500 la ou "
                                            + "Apple est seulement indisponible")
                                    .isInstanceOf(AuthException.class);
                            assertThat(((AuthException) erreur).getErrorCode())
                                    .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        });
            }
        }

        /**
         * La branche « zero cle retenue », que {@code keys: []} n'atteint pas.
         *
         * <p>Avec un tableau vide, c'est jjwt qui refuse en amont. Pour atteindre
         * le decompte, il faut un jeu que jjwt lit <b>sans erreur</b> mais dont
         * aucune cle n'est publique : une cle symetrique fait exactement cela.
         *
         * <p>Sans cette branche, le rechargement rendrait « succes » avec un
         * cache vide, et chaque connexion Apple echouerait ensuite en
         * {@code TOKEN_INVALID} — un message qui accuse le jeton de la personne
         * alors que la cause est chez nous.
         */
        @Test
        @DisplayName("un jeu lisible mais sans aucune cle PUBLIQUE est une indisponibilite")
        void aucuneClePublique() {
            String symetrique = """
                    {"keys":[{"kty":"oct","kid":"s1",\
                    "k":"AyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T"}]}""";
            var cles = autorite(new AtomicInteger(), symetrique);

            StepVerifier.create(cles.recharger())
                    .verifyErrorSatisfies(erreur -> {
                        assertThat(erreur).isInstanceOf(AuthException.class);
                        assertThat(((AuthException) erreur).getErrorCode())
                                .withFailMessage("""
                                        Un rechargement sans aucune cle publique est \
                                        rapporte comme un succes.

                                        Le cache reste vide et toute connexion Apple \
                                        echoue ensuite en TOKEN_INVALID : le diagnostic \
                                        accuse le jeton de la personne alors que la \
                                        cause est le jeu de cles.""")
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    });
            assertThat(cles.tailleDuCache()).isZero();
        }
    }
}
