package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.service.IDevJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'autorité de signature de la plateforme.
 *
 * <h2>Ce que ces trois endpoints portent</h2>
 *
 * {@code /internal/jwt/sign} émet un jeton pour <b>n'importe quel</b>
 * utilisateur avec <b>n'importe quels</b> rôles. C'est la surface la plus
 * puissante du dépôt, et sa seule protection est la comparaison d'un secret
 * partagé — {@code INTERNAL_SERVICE_SECRET}, qui n'a jamais été committé.
 *
 * <h2>Les deux défauts corrigés sur /refresh</h2>
 *
 * <ol>
 *   <li>{@code validateTokenReactive} ne contrôle que la signature,
 *       l'expiration et la révocation — <b>pas le type</b>. Un jeton d'ACCÈS
 *       était donc accepté comme jeton de rafraîchissement, ce qui permettait
 *       de prolonger une session indéfiniment : le rafraîchissement perdait
 *       toute limite de durée ;</li>
 *   <li>la régénération passait {@code roles = null}, si bien que le nouveau
 *       jeton <b>ne portait aucun rôle</b>. Un administrateur perdait ses
 *       droits au premier rafraîchissement, sans message ni trace. Le symptôme
 *       serait apparu comme un 401 ou un 403 intermittent.</li>
 * </ol>
 *
 * <p>L'endpoint n'est pas emprunté par auth-service aujourd'hui — il a son
 * propre flux de rafraîchissement en base — donc ces défauts étaient latents.
 * Ils restaient atteignables par tout détenteur du secret interne.
 */
@DisplayName("InternalJwtController - l'autorité de signature")
class InternalJwtControllerTest {

    private static final String SECRET = "secret-interne-de-test";
    private static final String UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    private IDevJwtService jwtService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        jwtService = Mockito.mock(IDevJwtService.class);
        when(jwtService.getAccessTokenExpiration()).thenReturn(900L);
        when(jwtService.generateTokenForUser(anyString(), any(), any(), any(), any(), any()))
                .thenReturn("nouveau-jeton-acces");
        when(jwtService.generateRefreshTokenForUser(anyString(), any()))
                .thenReturn("nouveau-jeton-rafraichissement");
        when(jwtService.extractUserId(anyString())).thenReturn(UTILISATEUR);

        // Le secret arrive par CONSTRUCTEUR, et non plus par @Value sur un
        // champ. Avec un champ annote, ce test etait impossible :
        // bindToController n'utilise pas l'instance fournie telle quelle, donc
        // une valeur posee par ReflectionTestUtils n'atteignait pas le
        // gestionnaire et tous les appels repondaient 403 — avec un secret
        // pourtant identique des deux cotes.
        client = WebTestClient.bindToController(
                new InternalJwtController(jwtService, SECRET)).build();
    }

    /** Pose le type et les rôles que le jeton présenté est censé porter. */
    private void jetonPorte(String type, String roles) {
        when(jwtService.extractClaim(anyString(), Mockito.eq("type"))).thenReturn(type);
        when(jwtService.extractClaim(anyString(), Mockito.eq("roles"))).thenReturn(roles);
        when(jwtService.extractClaim(anyString(), Mockito.eq("projectId"))).thenReturn("projet-1");
    }

    private WebTestClient.ResponseSpec rafraichir(String secret) {
        return client.post().uri("/internal/jwt/refresh")
                .header("X-Internal-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"jeton-presente\"}")
                .exchange();
    }

    // ==================================================================
    @Nested
    @DisplayName("Le secret interne protège les trois endpoints")
    class SecretInterne {

        @Test
        @DisplayName("un mauvais secret est refusé sur /sign")
        void mauvaisSecretSurSign() {
            client.post().uri("/internal/jwt/sign")
                    .header("X-Internal-Secret", "mauvais")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"userId\":\"" + UTILISATEUR + "\",\"roles\":[\"ROLE_SUPER_ADMIN\"]}")
                    .exchange()
                    .expectStatus().isForbidden();

            // L'assertion qui compte : AUCUN jeton n'a ete signe.
            verify(jwtService, never()).generateTokenForUser(
                    anyString(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("un mauvais secret est refusé sur /refresh")
        void mauvaisSecretSurRefresh() {
            rafraichir("mauvais").expectStatus().isForbidden();
            verify(jwtService, never()).validateTokenReactive(anyString());
        }

        @Test
        @DisplayName("un secret absent est refusé, pas traité comme vide")
        void secretAbsent() {
            client.post().uri("/internal/jwt/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"refreshToken\":\"x\"}")
                    .exchange()
                    .expectStatus().value(code -> assertThat(code).isNotEqualTo(200));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : /refresh exige un jeton de RAFRAÎCHISSEMENT")
    class TypeDuJeton {

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"refresh", "refresh_user"})
        @DisplayName("un vrai jeton de rafraîchissement est accepté")
        void typeAccepte(String type) {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte(type, "[ROLE_USER]");

            rafraichir(SECRET).expectStatus().isOk()
                    .expectBody().jsonPath("$.accessToken").isEqualTo("nouveau-jeton-acces");
        }

        @ParameterizedTest(name = "type={0}")
        @ValueSource(strings = {"user", "developer", "maintenance", "inconnu"})
        @DisplayName("un jeton d'ACCÈS est refusé comme jeton de rafraîchissement")
        void jetonAccesRefuse(String type) {
            // LE DEFAUT. validateTokenReactive ne verifie que signature,
            // expiration et revocation. Un jeton d'acces la franchit donc, et
            // produisait un nouveau jeton d'acces : de quoi prolonger une
            // session indefiniment a partir d'un seul jeton, ce qui annule la
            // raison d'etre d'une duree de vie courte.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte(type, "[ROLE_ADMIN]");

            rafraichir(SECRET).expectStatus().isUnauthorized();

            verify(jwtService, never()).generateTokenForUser(
                    anyString(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("un jeton sans claim type est refusé")
        void typeAbsent() {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte(null, "[ROLE_USER]");

            rafraichir(SECRET).expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("le refus est identique quelle qu'en soit la cause")
        void refusUniforme() {
            // Un jeton invalide et un jeton du mauvais type doivent produire la
            // meme reponse : toute difference indiquerait a l'appelant que son
            // jeton est authentique mais mal typé.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(false));
            jetonPorte("refresh", "[ROLE_USER]");
            byte[] invalide = rafraichir(SECRET).expectStatus().isUnauthorized()
                    .expectBody().returnResult().getResponseBody();

            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte("user", "[ROLE_USER]");
            byte[] mauvaisType = rafraichir(SECRET).expectStatus().isUnauthorized()
                    .expectBody().returnResult().getResponseBody();

            assertThat(new String(mauvaisType))
                    .withFailMessage("la réponse distingue « invalide » de « mauvais "
                            + "type » : c'est un oracle sur l'authenticité du jeton")
                    .isEqualTo(new String(invalide));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le défaut corrigé : les rôles survivent au rafraîchissement")
    class RolesConserves {

        @Test
        @DisplayName("les rôles du jeton présenté sont reportés sur le nouveau")
        void rolesReportes() {
            // LE DEFAUT. L'appel passait `roles = null`, donc le jeton regenere
            // ne portait AUCUN role. Un administrateur perdait ses droits au
            // premier rafraichissement, sans message ni trace — un 403
            // intermittent attribuable a tout sauf a sa cause.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte("refresh_user", "[ROLE_SUPER_ADMIN, ROLE_ADMIN]");

            rafraichir(SECRET).expectStatus().isOk();

            verify(jwtService).generateTokenForUser(
                    Mockito.eq(UTILISATEUR), Mockito.eq("projet-1"),
                    isNull(), isNull(),
                    Mockito.argThat(roles -> roles != null
                            && roles.contains("ROLE_SUPER_ADMIN")
                            && roles.contains("ROLE_ADMIN")),
                    isNull());
        }

        @Test
        @DisplayName("un jeton sans rôle n'en invente pas")
        void aucunRole() {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte("refresh_user", null);

            rafraichir(SECRET).expectStatus().isOk();

            // null plutot qu'une liste vide : generateTokenForUser n'ecrit le
            // claim que s'il est non vide, et un `roles: []` explicite se lirait
            // « aucun role accorde » la ou son absence se lit « non applicable ».
            verify(jwtService).generateTokenForUser(
                    anyString(), any(), isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("un claim roles malformé ne fait pas échouer la requête")
        void rolesMalformes() {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte("refresh_user", "[]");

            rafraichir(SECRET).expectStatus().isOk();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Dégradation")
    class Degradation {

        @Test
        @DisplayName("une validation VIDE ne perd pas la requête")
        void validationVide() {
            // Le piege du Mono vide, dans l'autorite de signature. Le Mono.zip
            // de validateTokenReactive se termine vide si l'une de ses sources
            // Redis l'est : le flatMap ne s'executerait jamais et le client
            // attendrait jusqu'a son delai d'expiration.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.empty());
            jetonPorte("refresh_user", "[ROLE_USER]");

            rafraichir(SECRET)
                    .expectStatus().value(code -> assertThat(code)
                            .withFailMessage("une validation vide ne produit aucune "
                                    + "réponse : la requête est perdue")
                            .isEqualTo(401));
        }

        @Test
        @DisplayName("/verify rend valid=false sur une validation vide")
        void verifierValidationVide() {
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.empty());

            client.post().uri("/internal/jwt/verify")
                    .header("X-Internal-Secret", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"token\":\"jeton\"}")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.valid").isEqualTo(false);
        }

        @Test
        @DisplayName("/verify rend le TYPE, sans quoi « valide » est ambigu")
        void verifierRendLeType() {
            // Sans le type, l'appelant ne peut pas distinguer un jeton d'acces
            // d'un jeton de rafraichissement, alors que les deux sont valides.
            // C'est precisement la confusion qui rendait /refresh permissif.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            jetonPorte("user", "[ROLE_USER]");

            client.post().uri("/internal/jwt/verify")
                    .header("X-Internal-Secret", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"token\":\"jeton\"}")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.type").isEqualTo("user");
        }

        @Test
        @DisplayName("une erreur d'analyse sur /verify ne produit pas de 500")
        void verifierErreurDAnalyse() {
            // extractUserId analyse le jeton a nouveau et peut lever sur une
            // forme inattendue. Un 500 renseignerait sur la pile d'appels de
            // l'autorite de signature.
            when(jwtService.validateTokenReactive(anyString())).thenReturn(Mono.just(true));
            when(jwtService.extractUserId(anyString()))
                    .thenThrow(new IllegalArgumentException("jeton illisible"));

            client.post().uri("/internal/jwt/verify")
                    .header("X-Internal-Secret", SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"token\":\"jeton\"}")
                    .exchange()
                    .expectStatus().value(code -> assertThat(code).isNotEqualTo(500));
        }
    }
}
