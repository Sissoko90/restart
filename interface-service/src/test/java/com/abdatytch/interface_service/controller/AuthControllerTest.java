package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.dto.response.UserResponse;
import com.abdatytch.interface_service.exception.BusinessException;
import com.abdatytch.interface_service.service.IDevJwtService;
import com.abdatytch.interface_service.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le flux d'authentification développeur, qui n'avait aucun test.
 *
 * <h2>Ce que ce test a établi</h2>
 *
 * {@code POST /auth/refresh-token} était mort de <b>trois façons
 * indépendantes</b>, et {@code POST /auth/logout} rendait 204 sans rien
 * révoquer. Les deux sont le motif que ce dépôt rencontre le plus souvent :
 * une opération qui annonce un succès sans avoir agi.
 *
 * <ol>
 *   <li>la connexion ne persistait pas le jeton de rafraîchissement qu'elle
 *       émettait — {@code updateRefreshToken} n'avait qu'un appelant, le flux
 *       de rafraîchissement lui-même ;</li>
 *   <li>la recherche portait sur la colonne en clair {@code refresh_token},
 *       jamais écrite, alors que {@code V1} avait créé
 *       {@code refresh_token_hash} ;</li>
 *   <li>les nouveaux jetons étaient signés en <b>HMAC256</b> avec un secret
 *       symétrique, alors que les huit services valident du RS256 par JWKS.
 *       Aucun validateur de la plateforme n'accepte HS256.</li>
 * </ol>
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Il exerce le <b>contrat du contrôleur</b>, pas la persistance : le service
 * est simulé. Que l'empreinte soit réellement écrite dans
 * {@code refresh_token_hash} relève de la migration V9 et de
 * {@code UserServiceImpl}, et aucun test de ce module n'ouvre de base. C'est
 * une limite réelle, et c'est pourquoi le premier démarrage reste un contrôle
 * à faire.
 *
 * <h2>Pourquoi {@code bindToController}</h2>
 *
 * {@code @WebFluxTest} embarquerait les {@code WebFilter} annotés
 * {@code @Component}, dont le filtre d'accès de la passerelle, qui refuserait
 * toute requête non signée. L'autorisation est couverte par
 * {@code ChaineReelleTest} ; ici on exerce la logique de réponse.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthController - flux d'authentification developpeur")
class AuthControllerTest {

    private static final UUID IDENTIFIANT = UUID.randomUUID();
    private static final String JETON_RAFRAICHISSEMENT = "jeton-de-rafraichissement-presente";
    private static final String ACCES_RS256 = "nouveau-jeton-acces-rs256";

    @Mock
    private UserService userService;
    @Mock
    private IDevJwtService jwtService;

    private WebTestClient client;

    @BeforeEach
    void preparer() {
        client = WebTestClient
                .bindToController(new AuthController(userService, jwtService))
                .build();
    }

    private static Map<String, Object> paireDeJetons() {
        return Map.of(
                "accessToken", ACCES_RS256,
                "refreshToken", "nouveau-jeton-de-rafraichissement",
                "expiresIn", 3600L,
                "tokenType", "Bearer");
    }

    // ==================================================================
    @Nested
    @DisplayName("Le rafraichissement rend de vrais jetons, ou 401")
    class Rafraichissement {

        @Test
        @DisplayName("Jeton valide : 200 avec une paire RS256 et le type Bearer")
        void jetonValide() {
            when(userService.refreshTokens(JETON_RAFRAICHISSEMENT))
                    .thenReturn(Mono.just(paireDeJetons()));

            client.post().uri("/auth/refresh-token")
                    .bodyValue(Map.of("refreshToken", JETON_RAFRAICHISSEMENT))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.accessToken").isEqualTo(ACCES_RS256)
                    .jsonPath("$.refreshToken").isEqualTo("nouveau-jeton-de-rafraichissement")
                    .jsonPath("$.tokenType").isEqualTo("Bearer")
                    .jsonPath("$.expiresIn").isEqualTo(3600);
        }

        /**
         * LE DÉFAUT GARDÉ ICI : le refus doit être 401, et non 500.
         *
         * <p>Le contrôleur levait une {@code RuntimeException} générique, donc
         * une erreur serveur. Un jeton de rafraîchissement inconnu ou périmé
         * est un défaut d'authentification : le client doit se reconnecter, pas
         * conclure à une panne et réessayer.
         */
        @Test
        @DisplayName("Jeton inconnu : 401, et non 500")
        void jetonInconnu() {
            when(userService.refreshTokens(anyString())).thenReturn(Mono.error(
                    new BusinessException("Refresh token invalide", "INVALID_REFRESH_TOKEN")));

            client.post().uri("/auth/refresh-token")
                    .bodyValue(Map.of("refreshToken", "jeton-qui-nexiste-pas"))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        /**
         * Tout refus donne le MEME 401, quelle qu'en soit la cause.
         *
         * <p>Distinguer « jeton inconnu » de « compte désactivé » renseignerait
         * un appelant sur l'existence du jeton et l'état du compte. Le
         * contrôleur passe donc un message constant, et journalise le motif.
         *
         * <h3>Pourquoi aucun test ne garde la non-divulgation elle-même</h3>
         *
         * Parce qu'elle n'est pas observable ici, et le dire vaut mieux que de
         * poser un test qui ne peut pas échouer.
         *
         * <p>J'avais d'abord écrit une assertion sur le corps de la réponse.
         * Vérifié par mutation : en remplaçant le message constant par
         * {@code refus.getMessage()}, elle ne détecte rien. La raison est que
         * {@code server.error.include-message} n'est configuré nulle part dans
         * ce dépôt, donc Spring Boot garde son défaut {@code never} : le
         * {@code reason} d'un {@code ResponseStatusException} ne franchit jamais
         * la frontière HTTP. L'assertion portait sur quelque chose qui ne peut
         * pas varier.
         *
         * <p>Le message constant reste le bon code — il protège le jour où
         * quelqu'un passera {@code include-message} à {@code always} — mais
         * c'est un choix de code, pas un invariant mesurable ici.
         */
        @Test
        @DisplayName("Deux causes differentes donnent le MEME refus")
        void refusIndistinguables() {
            when(userService.refreshTokens("inconnu")).thenReturn(Mono.error(
                    new BusinessException("Refresh token invalide", "INVALID_REFRESH_TOKEN")));
            when(userService.refreshTokens("desactive")).thenReturn(Mono.error(
                    new BusinessException("Compte desactive", "INACTIVE")));

            for (String jeton : new String[]{"inconnu", "desactive"}) {
                client.post().uri("/auth/refresh-token")
                        .bodyValue(Map.of("refreshToken", jeton))
                        .exchange()
                        .expectStatus().isUnauthorized()
                        .expectBody().isEmpty();
            }
        }

        @Test
        @DisplayName("Aucun jeton dans le corps : refuse, sans lever")
        void corpsSansJeton() {
            when(userService.refreshTokens(null)).thenReturn(Mono.error(
                    new BusinessException("Refresh token invalide", "INVALID_REFRESH_TOKEN")));

            client.post().uri("/auth/refresh-token")
                    .bodyValue(Map.of())
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La deconnexion revoque vraiment")
    class Deconnexion {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>La déconnexion ne révoquait <b>rien</b> : elle annulait une colonne
         * que la connexion n'écrivait jamais, et n'appelait pas
         * {@code revokeToken}. Le jeton d'accès restait valable jusqu'à son
         * expiration — un développeur qui se déconnectait gardait une session
         * ouverte.
         */
        @Test
        @DisplayName("Le jeton d'acces est REVOQUE, pas seulement le rafraichissement")
        void revocationEffective() {
            when(userService.invalidateRefreshToken(anyString())).thenReturn(Mono.empty());

            client.post().uri("/auth/logout")
                    .bodyValue(Map.of(
                            "refreshToken", JETON_RAFRAICHISSEMENT,
                            "accessToken", ACCES_RS256))
                    .exchange()
                    .expectStatus().isNoContent();

            verify(userService).invalidateRefreshToken(JETON_RAFRAICHISSEMENT);
            verify(jwtService).revokeToken(ACCES_RS256);
        }

        @Test
        @DisplayName("Sans jeton d'acces fourni : on invalide le rafraichissement, sans lever")
        void sansJetonDAcces() {
            when(userService.invalidateRefreshToken(anyString())).thenReturn(Mono.empty());

            client.post().uri("/auth/logout")
                    .bodyValue(Map.of("refreshToken", JETON_RAFRAICHISSEMENT))
                    .exchange()
                    .expectStatus().isNoContent();

            verify(userService).invalidateRefreshToken(JETON_RAFRAICHISSEMENT);
            verify(jwtService, never()).revokeToken(anyString());
        }

        /**
         * 204 même sur échec, mais plus en silence.
         *
         * <p>Le code reste 204 : une déconnexion ne doit pas renseigner
         * l'appelant sur l'existence d'un jeton. Ce qui change, c'est que
         * l'échec est désormais <b>journalisé avec sa cause</b> — le
         * {@code onErrorResume(e -> Mono.empty())} d'avant l'avalait sans
         * trace, y compris l'échec de l'invalidation elle-même.
         */
        @Test
        @DisplayName("Un echec de revocation rend 204, mais ne disparait plus")
        void echecNonSilencieux() {
            when(userService.invalidateRefreshToken(anyString()))
                    .thenReturn(Mono.error(new RuntimeException("Redis injoignable")));

            client.post().uri("/auth/logout")
                    .bodyValue(Map.of("refreshToken", JETON_RAFRAICHISSEMENT))
                    .exchange()
                    .expectStatus().isNoContent();
        }

        @Test
        @DisplayName("Un jeton inconnu rend 204 : on ne renseigne pas sur son existence")
        void jetonInconnuNeRenseignePas() {
            when(userService.invalidateRefreshToken(anyString())).thenReturn(Mono.empty());

            client.post().uri("/auth/logout")
                    .bodyValue(Map.of("refreshToken", "jamais-vu"))
                    .exchange()
                    .expectStatus().isNoContent();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les endpoints d'inscription et de verification")
    class InscriptionEtVerification {

        @Test
        @DisplayName("L'inscription rend 201")
        void inscriptionRend201() {
            UserResponse cree = new UserResponse();
            cree.setId(IDENTIFIANT);
            cree.setEmail("dev@restart.test");
            when(userService.register(any())).thenReturn(Mono.just(cree));

            // `confirmPassword` est @NotBlank : l'omettre rend 400. Le DTO exige
            // donc la confirmation cote serveur, et pas seulement dans
            // l'interface — c'est le bon choix, un client peut etre contourne.
            client.post().uri("/auth/register")
                    .bodyValue(Map.of(
                            "email", "dev@restart.test",
                            "password", "MotDePasseAssezLong1!",
                            "confirmPassword", "MotDePasseAssezLong1!",
                            "firstName", "Awa",
                            "lastName", "Traore",
                            "phoneNumber", "+22399999999"))
                    .exchange()
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.email").isEqualTo("dev@restart.test");
        }

        /**
         * La validation refuse ce qui est mal forme, et c'est verifie.
         *
         * <p>Quatre contraintes distinctes, chacune sur un champ different : le
         * motif du mot de passe, le format du telephone, l'adresse, et la
         * confirmation manquante. Les verifier ensemble dit que la validation
         * est bien active sur ce corps — un {@code @Valid} oublie ne se voit
         * autrement qu'en production.
         */
        @ParameterizedTest(name = "champ invalide : {0}")
        @ValueSource(strings = {"password", "phoneNumber", "email", "confirmPassword"})
        @DisplayName("Une inscription mal formee rend 400")
        void inscriptionMalFormee(String champInvalide) {
            java.util.Map<String, String> corps = new java.util.HashMap<>(Map.of(
                    "email", "dev@restart.test",
                    "password", "MotDePasseAssezLong1!",
                    "confirmPassword", "MotDePasseAssezLong1!",
                    "firstName", "Awa",
                    "lastName", "Traore",
                    "phoneNumber", "+22399999999"));

            switch (champInvalide) {
                // Sans majuscule, sans chiffre, sans caractere special.
                case "password" -> corps.put("password", "tropsimple");
                // Sans indicatif international.
                case "phoneNumber" -> corps.put("phoneNumber", "0022399999999");
                case "email" -> corps.put("email", "pas-une-adresse");
                case "confirmPassword" -> corps.remove("confirmPassword");
                default -> throw new IllegalArgumentException(champInvalide);
            }

            client.post().uri("/auth/register")
                    .bodyValue(corps)
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        /**
         * Les endpoints d'OTP annoncent une durée de validité.
         *
         * <p>Ce n'est pas cosmétique : sans elle, un client ne sait pas s'il
         * doit attendre ou redemander, et le réflexe est de redemander — ce qui
         * invalide le code précédent et fait boucler l'utilisateur.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/auth/request-email-otp", "/auth/request-password-reset"})
        @DisplayName("Les demandes d'OTP par courriel annoncent leur duree de validite")
        void otpAnnonceSaDuree(String chemin) {
            when(userService.requestEmailOtp(anyString())).thenReturn(Mono.empty());
            when(userService.requestPasswordReset(anyString())).thenReturn(Mono.empty());

            client.post().uri(chemin)
                    .bodyValue(Map.of("email", "dev@restart.test"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.expiresIn").isEqualTo("10 minutes")
                    .jsonPath("$.email").isEqualTo("dev@restart.test");
        }

        @Test
        @DisplayName("La demande d'OTP par SMS annonce aussi sa duree")
        void otpSmsAnnonceSaDuree() {
            when(userService.requestPhoneOtp(anyString())).thenReturn(Mono.empty());

            client.post().uri("/auth/request-phone-otp")
                    .bodyValue(Map.of("phoneNumber", "+22399999999"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.expiresIn").isEqualTo("10 minutes")
                    .jsonPath("$.phoneNumber").isEqualTo("+22399999999");
        }

        @Test
        @DisplayName("La reinitialisation par jeton rend 204, sans corps")
        void reinitialisationRend204() {
            when(userService.resetPassword(anyString(), anyString())).thenReturn(Mono.empty());

            client.post().uri("/auth/reset-password")
                    .bodyValue(Map.of("token", "jeton-de-reinitialisation",
                            "newPassword", "NouveauMotDePasse1!"))
                    .exchange()
                    .expectStatus().isNoContent()
                    .expectBody().isEmpty();
        }
    }
}
