package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.OAuth2AuthResponse;
import com.abdatytch.auth_service.exception.AuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Le code qui sort les jetons de l'URL.
 *
 * <h2>Ce que ces tests ferment</h2>
 *
 * La redirection de fin de parcours OAuth2 portait
 * {@code ?accessToken=...&refreshToken=...}. Une chaine de requete n'est pas un
 * canal prive : elle entre dans l'historique du navigateur, dans les journaux
 * d'acces de tout proxy et du frontal, et dans l'en-tete {@code Referer} des
 * requetes emises par la page chargee. Le jeton de rafraichissement y etait le
 * plus expose — il suffit a renouveler une session pendant des jours, sans mot
 * de passe ni second facteur.
 *
 * <p>Le cas central est {@link UsageUnique#unSecondEchangeEchoue()} : si le code
 * etait rejouable, la correction n'en serait pas une. Quiconque retrouve l'URL
 * dans un journal obtiendrait les jetons a son tour, et on aurait seulement
 * deplace la fuite.
 */
@DisplayName("CodeDeRemiseOAuth2Redis - les jetons quittent l'URL")
class CodeDeRemiseOAuth2RedisTest {

    private Map<String, String> magasin;
    private Set<String> clesEcrites;
    private Duration dureeDeVie;

    private CodeDeRemiseOAuth2Redis codes;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void preparerUnRedisEnMemoire() {
        magasin = new HashMap<>();
        clesEcrites = new HashSet<>();

        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valeurs = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);

        when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(appel -> {
                    magasin.put(appel.getArgument(0), appel.getArgument(1));
                    clesEcrites.add(appel.getArgument(0));
                    // L'echeance est capturee : sans elle, chaque parcours
                    // abandonne laisserait des jetons utilisables pour toujours.
                    dureeDeVie = appel.getArgument(2);
                    return Mono.just(true);
                });

        // getAndDelete : la lecture RETIRE la valeur. C'est ce qui rend l'usage
        // unique, et les tests s'appuient dessus plutot que sur un get simple.
        when(valeurs.getAndDelete(anyString()))
                .thenAnswer(appel -> {
                    String retiree = magasin.remove(appel.getArgument(0));
                    return retiree == null ? Mono.empty() : Mono.just(retiree);
                });

        codes = new CodeDeRemiseOAuth2Redis(redis, new ObjectMapper(), 120);
    }

    private static OAuth2AuthResponse reponseComplete() {
        return OAuth2AuthResponse.builder()
                .profileComplete(true)
                .accessToken("jeton-d-acces")
                .refreshToken("jeton-de-rafraichissement")
                .expiresIn(3600L)
                .tokenType("Bearer")
                .build();
    }

    private static OAuth2AuthResponse reponseIncomplete() {
        return OAuth2AuthResponse.builder()
                .profileComplete(false)
                .tempToken("jeton-temporaire")
                .missingFields(new String[] {"phoneNumber"})
                .build();
    }

    @Nested
    @DisplayName("Production du code")
    class Production {

        @Test
        @DisplayName("Le code est imprevisible et different a chaque parcours")
        void codeImprevisible() {
            // Un code devinable ne protegerait de rien : il vaut les jetons
            // qu'il designe le temps de sa validite.
            Set<String> vus = new HashSet<>();
            for (int tour = 0; tour < 50; tour++) {
                vus.add(codes.creer(reponseComplete()).block());
            }

            assertThat(vus)
                    .withFailMessage("deux parcours ont produit le meme code")
                    .hasSize(50);
            assertThat(vus).allSatisfy(code -> assertThat(code).hasSizeGreaterThan(32));
        }

        @Test
        @DisplayName("Le code NE contient aucun jeton")
        void leCodeNePorteAucunJeton() {
            // Le code est ce qui se retrouvera dans l'URL, donc dans
            // l'historique et les journaux. S'il portait un jeton, meme encode,
            // la correction serait cosmetique.
            String code = codes.creer(reponseComplete()).block();

            assertThat(code)
                    .doesNotContain("jeton-d-acces")
                    .doesNotContain("jeton-de-rafraichissement");
        }

        @Test
        @DisplayName("La cle porte le prefixe declare")
        void prefixeDeLaCle() {
            codes.creer(reponseComplete()).block();

            assertThat(clesEcrites)
                    .allSatisfy(cle -> assertThat(cle)
                            .startsWith(AppConstants.OAUTH2_REMISE_PREFIX));
        }

        @Test
        @DisplayName("Une echeance est posee, de l'ordre de la minute")
        void echeancePosee() {
            codes.creer(reponseComplete()).block();

            assertThat(dureeDeVie)
                    .as("sans echeance, un onglet ferme laisserait des jetons "
                            + "utilisables pour toujours")
                    .isNotNull()
                    .isEqualTo(Duration.ofSeconds(120));
        }
    }

    @Nested
    @DisplayName("Echange")
    class Echange {

        @Test
        @DisplayName("Le code rend la reponse complete, jetons compris")
        void reponseRendueIntacte() {
            String code = codes.creer(reponseComplete()).block();

            StepVerifier.create(codes.consommer(code))
                    .assertNext(rendue -> {
                        assertThat(rendue.isProfileComplete()).isTrue();
                        assertThat(rendue.getAccessToken()).isEqualTo("jeton-d-acces");
                        assertThat(rendue.getRefreshToken())
                                .isEqualTo("jeton-de-rafraichissement");
                        assertThat(rendue.getExpiresIn()).isEqualTo(3600L);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Le profil incomplet suit le MEME chemin")
        void profilIncomplet() {
            // Son tempToken voyageait aussi dans l'URL. Il est plus limite, mais
            // il permet de completer un profil, donc de prendre la main sur un
            // compte en cours de creation. Un seul chemin pour les deux cas
            // evite d'avoir a se souvenir lequel est protege.
            String code = codes.creer(reponseIncomplete()).block();

            StepVerifier.create(codes.consommer(code))
                    .assertNext(rendue -> {
                        assertThat(rendue.isProfileComplete()).isFalse();
                        assertThat(rendue.getTempToken()).isEqualTo("jeton-temporaire");
                        assertThat(rendue.getMissingFields()).containsExactly("phoneNumber");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Usage unique")
    class UsageUnique {

        @Test
        @DisplayName("Un second echange echoue")
        void unSecondEchangeEchoue() {
            // Le cas qui fait tenir toute la correction. Un code rejouable
            // laisserait quiconque retrouve l'URL dans un journal obtenir les
            // jetons a son tour : la fuite serait deplacee, pas fermee.
            String code = codes.creer(reponseComplete()).block();

            StepVerifier.create(codes.consommer(code)).expectNextCount(1).verifyComplete();

            StepVerifier.create(codes.consommer(code))
                    .expectErrorSatisfies(erreur ->
                            assertThat(((AuthException) erreur).getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_REQUEST))
                    .verify();
        }

        @Test
        @DisplayName("Le code est retire de Redis a la lecture, pas seulement lu")
        void codeRetireDeRedis() {
            String code = codes.creer(reponseComplete()).block();
            assertThat(magasin).hasSize(1);

            codes.consommer(code).block();

            assertThat(magasin)
                    .as("un code laisse en place resterait utilisable jusqu'a son echeance")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Refus")
    class Refus {

        @Test
        @DisplayName("Un code inconnu est refuse")
        void codeInconnu() {
            StepVerifier.create(codes.consommer("un-code-qui-n-existe-pas"))
                    .expectErrorSatisfies(erreur ->
                            assertThat(((AuthException) erreur).getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_REQUEST))
                    .verify();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Un code absent est refuse sans interroger Redis")
        void codeAbsent(String code) {
            StepVerifier.create(codes.consommer(code))
                    .expectError(AuthException.class)
                    .verify();
        }

        @Test
        @DisplayName("Inconnu et deja utilise rendent la MEME erreur")
        void memeErreurPourInconnuEtConsomme() {
            // Les distinguer dirait a un attaquant qui a trouve un code dans un
            // journal s'il est arrive trop tard — donc si l'URL qu'il a vue
            // valait la peine d'etre cherchee ailleurs.
            String code = codes.creer(reponseComplete()).block();
            codes.consommer(code).block();

            ErrorCode surConsomme = erreurDe(code);
            ErrorCode surInconnu = erreurDe("jamais-emis");

            assertThat(surConsomme).isEqualTo(surInconnu);
        }

        private ErrorCode erreurDe(String code) {
            try {
                codes.consommer(code).block();
                throw new AssertionError("ce code aurait du etre refuse");
            } catch (AuthException refus) {
                return refus.getErrorCode();
            }
        }
    }
}
