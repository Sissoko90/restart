package com.abdatytch.user_service.client;

import com.abdatytch.user_service.dto.response.AuthUserDTO;
import com.abdatytch.user_service.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Le client d'identité : ce qu'il demande, ce qu'il garde, ce qu'il laisse passer.
 *
 * <h2>Le chemin que personne n'exerçait</h2>
 *
 * L'ancien client Feign n'avait aucun test : {@code UserAdminServiceImplTest} le
 * simulait par Mockito, donc le chemin HTTP réel n'était jamais parcouru. C'est
 * ce qui a permis au préfixe {@code /api/v1} de rester en place et de faire
 * répondre <b>404 à chaque appel d'identité</b> sans que rien ne le signale —
 * d'autant que le repli du mapper posait alors des valeurs d'apparence
 * plausible. Ce test vérifie donc d'abord le chemin demandé, puisque c'est
 * exactement là qu'était le défaut.
 *
 * <h2>Comment l'échange est simulé</h2>
 *
 * Par un {@code ExchangeFunction}, et non par un serveur. Le constructeur du
 * client pose un {@code clientConnector} ; une fonction d'échange explicite a la
 * priorité sur lui à la construction du {@code WebClient}, ce qui permet de
 * tester la chaîne complète — en-têtes, URI, décodage, codes d'erreur — sans
 * ouvrir de port.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClientIdentiteAuth — identite, cache et propagation")
class ClientIdentiteAuthTest {

    private static final String AUTH_USER_ID = "auth-123";
    private static final String SECRET = "secret-de-test";
    private static final String CLE = "auth:identite:" + AUTH_USER_ID;
    private static final String CHEMIN_ATTENDU = "/internal/auth/users/" + AUTH_USER_ID;

    private static final String CORPS = """
            {"id":"auth-123",
             "email":"awa.diallo@example.ml",
             "phoneNumber":"+22370000000",
             "countryCode":"ML",
             "firstName":"Awa",
             "lastName":"Diallo",
             "role":"USER",
             "isActive":true,
             "isPhoneVerified":true,
             "isEmailVerified":false,
             "lastLogin":"2026-10-30T14:05:00",
             "createdAt":"2026-01-02T09:00:00"}
            """;

    @Mock
    private ReactiveRedisTemplate<String, Object> redis;
    @Mock
    private ReactiveValueOperations<String, Object> valeurs;

    private final List<ClientRequest> requetes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        requetes.clear();
        when(redis.opsForValue()).thenReturn(valeurs);
        when(valeurs.get(anyString())).thenReturn(Mono.empty());
        when(valeurs.set(anyString(), any(), any(Duration.class))).thenReturn(Mono.just(true));
        when(redis.delete(anyString())).thenReturn(Mono.just(1L));
    }

    private ClientIdentiteAuth clientAvec(ExchangeFunction echange) {
        return new ClientIdentiteAuth(
                WebClient.builder().exchangeFunction(requete -> {
                    requetes.add(requete);
                    return echange.exchange(requete);
                }),
                redis, SECRET, 3000, 5000);
    }

    private static ExchangeFunction reponse(HttpStatusCode statut, String corps) {
        return requete -> Mono.just(ClientResponse.create(statut)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(corps)
                .build());
    }

    private static ExchangeFunction sansCorps(HttpStatusCode statut) {
        return requete -> Mono.just(ClientResponse.create(statut).build());
    }

    // ==================================================================
    @Nested
    @DisplayName("La requete envoyee")
    class LaRequete {

        /**
         * Le chemin, sans préfixe public.
         *
         * <p>C'est l'assertion la plus importante du fichier. L'appel partait
         * vers {@code /api/v1/internal/auth/users/...} : {@code /api/v1} est le
         * préfixe que la passerelle retire, et cet appel ne passe pas par elle.
         * Aucun service de la plateforme n'a de {@code context-path}.
         */
        @Test
        @DisplayName("le chemin ne porte PAS le prefixe public /api/v1")
        void cheminSansPrefixePublic() {
            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(requetes).hasSize(1);
            assertThat(requetes.get(0).url().getPath())
                    .withFailMessage("le prefixe public /api/v1 est revenu : "
                            + "auth-service rendra 404 a chaque appel d'identite")
                    .isEqualTo(CHEMIN_ATTENDU);
        }

        /** L'hôte est l'identifiant de service, que l'équilibreur résout. */
        @Test
        @DisplayName("l'hote est l'identifiant de service auth-service")
        void hoteEstLIdentifiantDeService() {
            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(requetes.get(0).url().getHost()).isEqualTo("auth-service");
        }

        /**
         * Le secret INTERNE, et non celui du Gateway.
         *
         * <p>L'appel portait {@code X-Secret-Key}, la clé du Gateway — qui
         * figure dans les en-têtes que le Gateway <b>purge</b> des requêtes
         * entrantes, et qui authentifie le Gateway auprès des services, pas un
         * service auprès d'un autre.
         */
        @Test
        @DisplayName("l'en-tete X-Internal-Secret porte le secret inter-services")
        void secretInterneEnvoye() {
            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .expectNextCount(1)
                    .verifyComplete();

            assertThat(requetes.get(0).headers().getFirst("X-Internal-Secret"))
                    .isEqualTo(SECRET);
            assertThat(requetes.get(0).headers().getFirst("X-Secret-Key"))
                    .withFailMessage("la cle du Gateway est repartie sur un appel "
                            + "de service a service")
                    .isNull();
        }

        @Test
        @DisplayName("un identifiant vide est refuse sans aucun appel reseau")
        void identifiantVide() {
            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite("  "))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            // Sans ce garde-fou l'appel partirait vers
            // /internal/auth/users/ et rendrait un 404 que rien ne
            // distinguerait d'un compte inexistant.
            assertThat(requetes).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le cache")
    class LeCache {

        @Test
        @DisplayName("un manque interroge auth-service puis met en cache 60 s")
        void manquePuisMiseEnCache() {
            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .assertNext(identite -> {
                        assertThat(identite.email()).isEqualTo("awa.diallo@example.ml");
                        assertThat(identite.role()).isEqualTo(UserRole.USER);
                        assertThat(identite.lastLogin())
                                .isEqualTo(LocalDateTime.of(2026, 10, 30, 14, 5));
                    })
                    .verifyComplete();

            assertThat(requetes).hasSize(1);
            verify(valeurs).set(eq(CLE), any(AuthUserDTO.class), eq(Duration.ofSeconds(60)));
        }

        @Test
        @DisplayName("une entree en cache evite tout appel reseau")
        void lectureServieParLeCache() {
            AuthUserDTO enCache = new AuthUserDTO(
                    AUTH_USER_ID, "deja@example.ml", null, null, null, null,
                    UserRole.USER, true, false, false, null, null);
            when(valeurs.get(CLE)).thenReturn(Mono.<Object>just(enCache));

            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .expectNext(enCache)
                    .verifyComplete();

            assertThat(requetes)
                    .withFailMessage("le cache n'a pas servi la lecture : "
                            + "auth-service a ete interroge quand meme")
                    .isEmpty();
            verify(valeurs, never()).set(anyString(), any(), any(Duration.class));
        }

        /**
         * Un cache en panne ralentit, il ne casse pas.
         *
         * <p>C'est la propriété qui distingue un cache d'une dépendance : Redis
         * absent doit faire repartir chaque lecture vers auth-service, pas
         * refuser la réponse.
         */
        @Test
        @DisplayName("un cache illisible laisse passer vers auth-service")
        void cacheEnPanne() {
            when(valeurs.get(CLE))
                    .thenReturn(Mono.error(new IllegalStateException("redis injoignable")));

            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .assertNext(identite ->
                            assertThat(identite.email()).isEqualTo("awa.diallo@example.ml"))
                    .verifyComplete();

            assertThat(requetes).hasSize(1);
        }

        /** Une écriture de cache ratée ne doit pas perdre l'identité obtenue. */
        @Test
        @DisplayName("une ecriture de cache ratee ne perd pas la reponse")
        void ecritureRatee() {
            when(valeurs.set(anyString(), any(), any(Duration.class)))
                    .thenReturn(Mono.error(new IllegalStateException("disque plein")));

            StepVerifier.create(clientAvec(reponse(HttpStatus.OK, CORPS)).identite(AUTH_USER_ID))
                    .assertNext(identite ->
                            assertThat(identite.email()).isEqualTo("awa.diallo@example.ml"))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les echecs remontent, et ne sont jamais mis en cache")
    class LesEchecs {

        /**
         * Aucun repli ici.
         *
         * <p>Le repli vit dans {@code UserMapper}, parce que l'autre appelant du
         * client — la propagation de fermeture de compte — doit impérativement
         * échouer. Un repli posé dans le client laisserait un compte révoqué
         * localement mais toujours actif dans auth-service.
         */
        @Test
        @DisplayName("un 500 est propage")
        void erreurServeurPropagee() {
            StepVerifier.create(clientAvec(sansCorps(HttpStatus.INTERNAL_SERVER_ERROR))
                            .identite(AUTH_USER_ID))
                    .expectError(WebClientResponseException.class)
                    .verify();
        }

        @Test
        @DisplayName("un echec n'ecrit JAMAIS dans le cache")
        void echecNonMisEnCache() {
            StepVerifier.create(clientAvec(sansCorps(HttpStatus.NOT_FOUND))
                            .identite(AUTH_USER_ID))
                    .expectError(WebClientResponseException.class)
                    .verify();

            // L'ecriture est en aval du succes : une identite indisponible ne
            // peut pas etre figee une minute dans le cache.
            verify(valeurs, never()).set(anyString(), any(), any(Duration.class));
        }

        /**
         * Le piège du {@code Mono} vide.
         *
         * <p>{@code bodyToMono} rend un {@code Mono} vide sur une réponse sans
         * corps. Laissé tel quel, ce vide remonterait au {@code Mono.zip} de
         * {@code UserMapper}, qui ignore une source vide : la réponse
         * utilisateur entière disparaîtrait et l'appelant recevrait un 404 pour
         * un compte qui existe.
         */
        @Test
        @DisplayName("un 200 sans corps devient une erreur, et non un Mono vide")
        void corpsVideDevientErreur() {
            StepVerifier.create(clientAvec(sansCorps(HttpStatus.OK)).identite(AUTH_USER_ID))
                    .expectError(IllegalStateException.class)
                    .verify();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La propagation de fermeture")
    class LaFermeture {

        @Test
        @DisplayName("un DELETE est envoye sur le bon chemin, puis le cache est invalide")
        void suppressionPuisInvalidation() {
            StepVerifier.create(clientAvec(sansCorps(HttpStatus.NO_CONTENT))
                            .supprimer(AUTH_USER_ID))
                    .verifyComplete();

            assertThat(requetes).hasSize(1);
            assertThat(requetes.get(0).method()).isEqualTo(HttpMethod.DELETE);
            assertThat(requetes.get(0).url().getPath()).isEqualTo(CHEMIN_ATTENDU);
            verify(redis).delete(CLE);
        }

        /**
         * L'ordre compte : supprimer d'abord, oublier ensuite.
         *
         * <p>Invalider avant l'appel puis échouer laisserait auth-service
         * inchangé et le cache vide : la lecture suivante rechargerait
         * aussitôt la même identité. L'ordre retenu ne laisse jamais une
         * identité en cache pour un compte réellement supprimé.
         */
        @Test
        @DisplayName("un echec de suppression est propage et n'invalide rien")
        void echecDeSuppressionPropage() {
            StepVerifier.create(clientAvec(sansCorps(HttpStatus.INTERNAL_SERVER_ERROR))
                            .supprimer(AUTH_USER_ID))
                    .expectError(WebClientResponseException.class)
                    .verify();

            verify(redis, never()).delete(anyString());
        }
    }
}
