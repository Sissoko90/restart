package com.abdatytch.gateway_service.security;

import com.abdatytch.commons.security.JwksKeyProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Le Gateway est le seul point d'entrée : tout ce qui passe ici atteint les
 * services métier, tout ce qui est refusé ici ne les atteint jamais.
 *
 * Ces tests verrouillent les deux directions :
 *  - un jeton légitime DOIT passer (une régression ici coupe la plateforme) ;
 *  - un jeton forgé, expiré, d'un autre émetteur, sans audience, ou d'un
 *    mauvais type DOIT être refusé.
 *
 * Les jetons sont construits avec de vraies paires RSA, jamais mockés : c'est
 * la validation cryptographique réelle qui est vérifiée.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JwtWebFilter — porte d'entrée de la plateforme")
class JwtWebFilterTest {

    private static final String ISSUER = "interface-service";
    private static final String AUDIENCES = "restart-developers,user-service,media-service";
    private static final String KID = "key-2026-09";

    @Mock
    private JwksKeyProvider jwksKeyProvider;

    @Mock
    private TokenRevocationChecker revocationChecker;

    private JwtWebFilter filter;

    private KeyPair authorityKeys;
    private KeyPair attackerKeys;

    /** Passe à `true` dès que la requête a été transmise aux services en aval. */
    private AtomicBoolean forwarded;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        authorityKeys = generator.generateKeyPair();
        attackerKeys = generator.generateKeyPair();

        // Le fournisseur JWKS ne connaît que la clé publique de l'autorité :
        // un jeton signé par `attackerKeys` n'a donc aucune clé de vérification.
        PublicKey authorityPublic = authorityKeys.getPublic();
        when(jwksKeyProvider.getDefaultPublicKey()).thenReturn(authorityPublic);
        when(jwksKeyProvider.getPublicKey(KID)).thenReturn(authorityPublic);
        Locator<Key> locator = header -> authorityPublic;
        when(jwksKeyProvider.keyLocator()).thenReturn(locator);

        // Par défaut aucun jeton n'est révoqué : chaque test qui s'intéresse à la
        // révocation surcharge ce comportement explicitement.
        when(revocationChecker.isRevoked(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(false));

        filter = new JwtWebFilter(jwksKeyProvider, revocationChecker, ISSUER, AUDIENCES);

        forwarded = new AtomicBoolean(false);
        chain = exchange -> Mono.fromRunnable(() -> forwarded.set(true));
    }

    // ------------------------------------------------------------------
    // Fabrique de jetons
    // ------------------------------------------------------------------

    private String token(Map<String, Object> claims, String issuer, List<String> audiences,
                         PrivateKey signingKey, long validForMillis) {
        Date now = new Date();
        var builder = Jwts.builder()
                .header().keyId(KID).and()
                .claims(claims)
                .id(java.util.UUID.randomUUID().toString())
                .subject("11111111-2222-3333-4444-555555555555")
                .issuer(issuer)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validForMillis));
        audiences.forEach(audience -> builder.audience().add(audience));
        return builder.signWith(signingKey, SignatureAlgorithm.RS256).compact();
    }

    /** Jeton d'utilisateur final : `roles` est une LISTE (cf. DevJwtServiceImpl). */
    private String userToken() {
        return token(Map.of(
                        "userId", "11111111-2222-3333-4444-555555555555",
                        "projectId", "proj-1",
                        "type", "user",
                        "roles", List.of("ROLE_USER")),
                ISSUER, List.of("user-service"), authorityKeys.getPrivate(), 60_000);
    }

    /** Jeton de développeur : `role` est une chaîne SIMPLE, et il n'y a pas de `roles`. */
    private String developerToken() {
        return token(Map.of(
                        "developerId", "11111111-2222-3333-4444-555555555555",
                        "email", "dev@abdatech.com",
                        "role", "ROLE_DEVELOPER",
                        "type", "developer"),
                ISSUER, List.of("restart-developers"), authorityKeys.getPrivate(), 60_000);
    }

    private MockServerWebExchange get(String path, String bearer) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (bearer != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return MockServerWebExchange.from(builder.build());
    }

    private void assertRejected(MockServerWebExchange exchange) {
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        assertThat(forwarded)
                .withFailMessage("la requête a été transmise aux services en aval alors "
                        + "qu'elle aurait dû être refusée au Gateway")
                .isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Le trafic légitime doit passer")
    class TraficLegitime {

        @Test
        @DisplayName("un jeton utilisateur valide est accepté et ses claims publiés en aval")
        void jetonUtilisateurAccepte() {
            MockServerWebExchange exchange = get("/api/v1/users/me", userToken());

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(forwarded)
                    .withFailMessage("le jeton utilisateur a été refusé : tous les appels "
                            + "des utilisateurs finaux échouent en 401")
                    .isTrue();
            assertThat(exchange.getAttributes())
                    .containsKey(JwtWebFilter.VALIDATED_CLAIMS_ATTRIBUTE);
        }

        @Test
        @DisplayName("un jeton développeur valide est accepté")
        void jetonDeveloppeurAccepte() {
            MockServerWebExchange exchange = get("/api/v1/quotas/usage", developerToken());

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(forwarded)
                    .withFailMessage("le jeton développeur a été refusé : le portail "
                            + "développeur est inutilisable")
                    .isTrue();
        }

        @Test
        @DisplayName("un chemin public passe sans jeton")
        void cheminPublicSansJeton() {
            StepVerifier.create(filter.filter(get("/api/v1/auth/login", null), chain))
                    .verifyComplete();

            assertThat(forwarded).isTrue();
        }

        @Test
        @DisplayName("un jeton valide ne doit pas voir sa reponse ECRASEE apres coup")
        void reponseNonEcraseeApresTransmission() {
            // LACUNE COMBLEE DANS CETTE SUITE.
            //
            // Les tests ci-dessus verifient que la chaine a ete ATTEINTE, via le
            // drapeau `forwarded`. Cela ne suffit pas : un filtre peut atteindre
            // la chaine PUIS ecraser la reponse.
            //
            // C'est arrive. Un switchIfEmpty place par erreur sur le Mono<Void>
            // final — lequel se termine vide en cas de SUCCES — faisait repondre
            // 401 a toute requete valide, apres l'avoir transmise. Les douze
            // tests de cette classe restaient verts, parce qu'aucun ne regardait
            // le statut de reponse sur un cas legitime.
            //
            // Verifier l'absence de statut d'erreur, et pas seulement la
            // traversee, est ce qui distingue « la requete est passee » de « la
            // requete a abouti ».
            MockServerWebExchange exchange = get("/api/v1/users/me", userToken());

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(forwarded).isTrue();
            assertThat(exchange.getResponse().getStatusCode())
                    .withFailMessage("la reponse porte le statut %s apres transmission : "
                            + "le filtre a atteint la chaine puis ecrase la reponse",
                            exchange.getResponse().getStatusCode())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Le trafic illégitime doit être arrêté")
    class TraficIllegitime {

        @Test
        @DisplayName("un jeton signé par une autre clé est refusé")
        void signatureEtrangere() {
            String forged = token(Map.of("type", "user", "roles", List.of("ROLE_ADMIN")),
                    ISSUER, List.of("user-service"), attackerKeys.getPrivate(), 60_000);

            assertRejected(get("/api/v1/users/me", forged));
        }

        @Test
        @DisplayName("un jeton émis par un autre émetteur est refusé")
        void emetteurEtranger() {
            String foreign = token(Map.of("type", "user", "roles", List.of("ROLE_USER")),
                    "evil-issuer", List.of("user-service"), authorityKeys.getPrivate(), 60_000);

            assertRejected(get("/api/v1/users/me", foreign));
        }

        @Test
        @DisplayName("un jeton expiré est refusé")
        void jetonExpire() {
            String expired = token(Map.of("type", "user", "roles", List.of("ROLE_USER")),
                    ISSUER, List.of("user-service"), authorityKeys.getPrivate(), -60_000);

            assertRejected(get("/api/v1/users/me", expired));
        }

        @Test
        @DisplayName("un jeton dont l'audience est étrangère est refusé")
        void audienceEtrangere() {
            String wrongAudience = token(Map.of("type", "user", "roles", List.of("ROLE_USER")),
                    ISSUER, List.of("un-autre-produit"), authorityKeys.getPrivate(), 60_000);

            assertRejected(get("/api/v1/users/me", wrongAudience));
        }

        @Test
        @DisplayName("un jeton SANS audience est refusé")
        void audienceAbsente() {
            // Un jeton sans `aud` n'est destiné à personne. L'accepter reviendrait à
            // accepter tout jeton signé par l'autorité, quel qu'en soit l'usage.
            String noAudience = token(Map.of("type", "user", "roles", List.of("ROLE_USER")),
                    ISSUER, List.of(), authorityKeys.getPrivate(), 60_000);

            assertRejected(get("/api/v1/users/me", noAudience));
        }

        @Test
        @DisplayName("un jeton de rafraîchissement ne peut pas servir de jeton d'accès")
        void refreshNonUtilisableCommeAcces() {
            // Les jetons de rafraîchissement vivent bien plus longtemps que les jetons
            // d'accès. Les accepter ici annulerait tout l'intérêt de la courte durée
            // de vie des jetons d'accès.
            String refresh = token(Map.of("type", "refresh_user", "userId", "u-1"),
                    ISSUER, List.of("user-service"), authorityKeys.getPrivate(), 60_000);

            assertRejected(get("/api/v1/users/me", refresh));
        }

        @Test
        @DisplayName("un jeton illisible est refusé")
        void jetonIllisible() {
            assertRejected(get("/api/v1/users/me", "ceci.nest.pas-un-jwt"));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("La révocation doit prendre effet dès l'entrée")
    class Revocation {

        @Test
        @DisplayName("un jeton révoqué est refusé malgré une signature valide")
        void jetonRevoque() {
            // Une signature valide prouve que l'autorité a émis le jeton, pas
            // qu'il est encore en vigueur : sans ce contrôle, une déconnexion ne
            // fermait aucune porte jusqu'à l'expiration naturelle du jeton.
            when(revocationChecker.isRevoked(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(true));

            assertRejected(get("/api/v1/users/me", userToken()));
        }

        @Test
        @DisplayName("la révocation est interrogée avec le jti ET le sujet du jeton")
        void revocationInterrogeeAvecLeBonJeton() {
            org.mockito.ArgumentCaptor<String> jti = org.mockito.ArgumentCaptor.forClass(String.class);
            org.mockito.ArgumentCaptor<String> subject = org.mockito.ArgumentCaptor.forClass(String.class);

            StepVerifier.create(filter.filter(get("/api/v1/users/me", userToken()), chain))
                    .verifyComplete();

            org.mockito.Mockito.verify(revocationChecker)
                    .isRevoked(jti.capture(), subject.capture());

            assertThat(jti.getValue()).isNotBlank();
            assertThat(subject.getValue()).isEqualTo("11111111-2222-3333-4444-555555555555");
        }
    }
}
