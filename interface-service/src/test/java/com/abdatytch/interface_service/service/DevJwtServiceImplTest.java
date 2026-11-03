package com.abdatytch.interface_service.service;

import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.service.impl.DevJwtServiceImpl;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verrouille le contrat JWT de la plateforme (docs/JWT-CONTRACT.md).
 *
 * <p>interface-service est la <b>seule autorité de signature</b> : tous les autres
 * services valident ce que cette classe produit. Une dérive silencieuse ici
 * (audience oubliée, `kid` déplacé dans le corps, claim `type` absent) casse
 * l'authentification de toute la plateforme sans qu'aucun test ne l'attrape.
 *
 * <p>Une vraie paire de clés RSA est générée pour le test : on vérifie ainsi la
 * signature réelle, pas une simulation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DevJwtServiceImpl - Contrat JWT")
class DevJwtServiceImplTest {

    private static final String ISSUER = "interface-service";
    private static final String AUDIENCES =
            "restart-developers,gateway-service,auth-service,user-service,"
                    + "media-service,quotas-service,setting-service";
    private static final String CURRENT_KID = "kid-courant";

    @Mock
    private IDevKeyRotationService keyRotationService;
    @Mock
    private IDevTokenBlacklistService tokenBlacklistService;

    private DevJwtServiceImpl jwtService;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        when(keyRotationService.getCurrentPrivateKey()).thenReturn(keyPair.getPrivate());
        when(keyRotationService.getCurrentPublicKey()).thenReturn(keyPair.getPublic());
        when(keyRotationService.getCurrentKeyId()).thenReturn(CURRENT_KID);
        when(keyRotationService.getPublicKeyById(CURRENT_KID)).thenReturn(keyPair.getPublic());

        when(tokenBlacklistService.isTokenRevoked(anyString())).thenReturn(Mono.just(false));
        when(tokenBlacklistService.hasDeveloperRevokedTokens(anyString())).thenReturn(Mono.just(false));

        jwtService = new DevJwtServiceImpl(keyRotationService, tokenBlacklistService);
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 3_600_000L);
        ReflectionTestUtils.setField(jwtService, "refreshExpirationMs", 604_800_000L);
        ReflectionTestUtils.setField(jwtService, "jwtIssuer", ISSUER);
        ReflectionTestUtils.setField(jwtService, "jwtAudience", AUDIENCES);
    }

    private User developer() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("dev@example.com");
        user.setUsername("dev");
        user.setRole("DEVELOPER");
        return user;
    }

    /** Parse le token avec la clé publique réelle et rend l'en-tête + les claims. */
    private Jws<Claims> parse(String token) {
        return Jwts.parser()
                .verifyWith((java.security.interfaces.RSAPublicKey) keyPair.getPublic())
                .build()
                .parseSignedClaims(token);
    }

    // ==========================================================
    // En-tête JWS : le `kid` (§2 du contrat)
    // ==========================================================

    @Test
    @DisplayName("le kid est dans l'EN-TÊTE, pas dans le corps")
    void kidIsInHeaderNotInBody() {
        Jws<Claims> jws = parse(jwtService.generateToken(developer()));

        // Sans kid dans l'en-tête, les consommateurs ne peuvent pas choisir la bonne
        // clé pendant une rotation : la validation échoue de façon non déterministe.
        assertThat(jws.getHeader().getKeyId()).isEqualTo(CURRENT_KID);
        assertThat(jws.getPayload().get("kid")).isNull();
    }

    @Test
    @DisplayName("le token de service final porte aussi le kid en en-tête")
    void userTokenAlsoCarriesKid() {
        String token = jwtService.generateTokenForUser(
                "user-1", "proj-1", "user@example.com", "user", List.of("ROLE_USER"), Map.of());

        assertThat(parse(token).getHeader().getKeyId()).isEqualTo(CURRENT_KID);
    }

    // ==========================================================
    // Audiences (§4 du contrat)
    // ==========================================================

    @Test
    @DisplayName("toutes les audiences configurées sont émises")
    void allConfiguredAudiencesAreEmitted() {
        Claims claims = parse(jwtService.generateToken(developer())).getPayload();

        // Une audience manquante = le service concerné rejette TOUS les tokens
        assertThat(claims.getAudience())
                .containsExactlyInAnyOrder(AUDIENCES.split(","));
    }

    @Test
    @DisplayName("l'émetteur est interface-service")
    void issuerIsInterfaceService() {
        assertThat(parse(jwtService.generateToken(developer())).getPayload().getIssuer())
                .isEqualTo(ISSUER);
    }

    // ==========================================================
    // Claims applicatifs (§5 du contrat)
    // ==========================================================

    @Test
    @DisplayName("un token développeur porte type=developer")
    void developerTokenHasDeveloperType() {
        Claims claims = parse(jwtService.generateToken(developer())).getPayload();

        // auth-service exige ce claim pour donner accès à son SDK /auth/**.
        // Sans lui, tout le SDK est inaccessible.
        assertThat(claims.get("type")).isEqualTo("developer");
        assertThat(claims.get("role")).isEqualTo("DEVELOPER");
        assertThat(claims.get("email")).isEqualTo("dev@example.com");
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    @DisplayName("un token utilisateur final porte type=user et ses rôles")
    void userTokenCarriesTypeAndRoles() {
        String token = jwtService.generateTokenForUser(
                "user-1", "proj-1", "user@example.com", "Jane Doe",
                List.of("ROLE_USER", "ROLE_DRIVER"),
                Map.of("phone", "+22300000000", "primaryRole", "ROLE_USER"));

        Claims claims = parse(token).getPayload();
        assertThat(claims.get("type")).isEqualTo("user");
        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.get("projectId")).isEqualTo("proj-1");
        assertThat(claims.get("roles")).isEqualTo(List.of("ROLE_USER", "ROLE_DRIVER"));
        assertThat(claims.get("phone")).isEqualTo("+22300000000");
        assertThat(claims.get("primaryRole")).isEqualTo("ROLE_USER");
    }

    @Test
    @DisplayName("un refresh token est distingué par son type")
    void refreshTokenIsTyped() {
        assertThat(parse(jwtService.generateRefreshToken(developer())).getPayload().get("type"))
                .isEqualTo("refresh");
        assertThat(parse(jwtService.generateRefreshTokenForUser("user-1", "proj-1"))
                .getPayload().get("type"))
                .isEqualTo("refresh_user");
    }

    @Test
    @DisplayName("chaque token reçoit un jti unique (clé de la blacklist)")
    void eachTokenHasUniqueJti() {
        String first = parse(jwtService.generateToken(developer())).getPayload().getId();
        String second = parse(jwtService.generateToken(developer())).getPayload().getId();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    // ==========================================================
    // Validation
    // ==========================================================

    @Test
    @DisplayName("un token fraîchement émis est valide")
    void freshTokenIsValid() {
        StepVerifier.create(jwtService.validateTokenReactive(jwtService.generateToken(developer())))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("un token révoqué est refusé (blacklist par jti)")
    void revokedTokenIsRejected() {
        when(tokenBlacklistService.isTokenRevoked(anyString())).thenReturn(Mono.just(true));

        StepVerifier.create(jwtService.validateTokenReactive(jwtService.generateToken(developer())))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("un token d'un développeur globalement révoqué est refusé")
    void tokenOfGloballyRevokedDeveloperIsRejected() {
        when(tokenBlacklistService.hasDeveloperRevokedTokens(anyString())).thenReturn(Mono.just(true));

        StepVerifier.create(jwtService.validateTokenReactive(jwtService.generateToken(developer())))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("un token signé par une autre clé est refusé")
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair intruder = generator.generateKeyPair();

        String forged = Jwts.builder()
                .header().keyId(CURRENT_KID).and()
                .subject("attaquant")
                .issuer(ISSUER)
                .signWith(intruder.getPrivate())
                .compact();

        StepVerifier.create(jwtService.validateTokenReactive(forged))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    @DisplayName("un token d'un autre émetteur est refusé")
    void tokenFromAnotherIssuerIsRejected() {
        String foreign = Jwts.builder()
                .header().keyId(CURRENT_KID).and()
                .subject("user-1")
                .issuer("un-autre-service")
                .signWith(keyPair.getPrivate())
                .compact();

        StepVerifier.create(jwtService.validateTokenReactive(foreign))
                .expectNext(false)
                .verifyComplete();
    }

    // ==========================================================
    // Rotation de clés (§8 du contrat)
    // ==========================================================

    @Test
    @DisplayName("pendant la période de grâce, un token signé par l'ancienne clé reste valide")
    void tokenFromPreviousKeyStaysValidDuringGracePeriod() throws Exception {
        // Token émis avant rotation, avec l'ancienne clé et son kid
        String oldToken = jwtService.generateToken(developer());

        // Rotation : nouvelle clé courante, l'ancienne reste résolvable par son kid
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair rotated = generator.generateKeyPair();
        PublicKey previousPublicKey = keyPair.getPublic();

        when(keyRotationService.getCurrentKeyId()).thenReturn("kid-nouveau");
        when(keyRotationService.getCurrentPrivateKey()).thenReturn(rotated.getPrivate());
        when(keyRotationService.getCurrentPublicKey()).thenReturn(rotated.getPublic());
        when(keyRotationService.getPublicKeyById(CURRENT_KID)).thenReturn(previousPublicKey);
        when(keyRotationService.getPublicKeyById("kid-nouveau")).thenReturn(rotated.getPublic());

        // C'est tout l'intérêt du kid en en-tête : sans lui, la validation prendrait
        // la clé courante et échouerait sur tous les tokens émis avant la rotation.
        StepVerifier.create(jwtService.validateTokenReactive(oldToken))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("un kid inconnu ne fait pas passer un token forgé")
    void unknownKidDoesNotBypassValidation() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair intruder = generator.generateKeyPair();

        String forged = Jwts.builder()
                .header().keyId("kid-inexistant").and()
                .subject("attaquant")
                .issuer(ISSUER)
                .signWith(intruder.getPrivate())
                .compact();

        // kid inconnu -> repli sur la clé courante -> la signature ne correspond pas
        when(keyRotationService.getPublicKeyById("kid-inexistant")).thenReturn(null);

        StepVerifier.create(jwtService.validateTokenReactive(forged))
                .expectNext(false)
                .verifyComplete();
    }

    // ==========================================================
    // Extraction de claims
    // ==========================================================

    @Test
    @DisplayName("les accesseurs de claims lisent le token signé")
    void claimAccessorsReadSignedToken() {
        User user = developer();
        String token = jwtService.generateToken(user);

        assertThat(jwtService.getDeveloperIdFromToken(token)).isEqualTo(user.getId().toString());
        assertThat(jwtService.getEmailFromToken(token)).isEqualTo("dev@example.com");
        assertThat(jwtService.getRoleFromToken(token)).isEqualTo("DEVELOPER");
        assertThat(jwtService.getTokenIdFromToken(token)).isNotBlank();
        assertThat(jwtService.extractAllClaims(token)).containsKey("type");
    }

    @Test
    @DisplayName("extractClaim lit un claim personnalisé du token utilisateur")
    void extractClaimReadsCustomClaim() {
        String token = jwtService.generateTokenForUser(
                "user-1", "proj-42", null, null, null, Map.of());

        assertThat(jwtService.extractUserId(token)).isEqualTo("user-1");
        assertThat(jwtService.extractClaim(token, "projectId")).isEqualTo("proj-42");
    }

    @Test
    @DisplayName("les customClaims ne peuvent PAS réécrire les invariants de l'autorité")
    void customClaimsNeReecriventPasLesInvariants() {
        // LE DEFAUT STRUCTUREL CORRIGE.
        //
        // `claims.putAll(customClaims)` etait applique EN DERNIER, si bien qu'un
        // appelant pouvait reecrire `type`, `userId` et `projectId` en les
        // glissant dans sa carte de claims personnalises.
        //
        // `type` est le plus grave des trois : c'est lui, et lui seul, qui
        // distingue un jeton d'ACCES d'un jeton de RAFRAICHISSEMENT. Les deux
        // portent la meme signature et la meme audience. Laisser le client
        // fixer ce claim revient a lui laisser decider de la duree de sa
        // session — et c'est exactement la confusion que
        // JwtClaimsReader.ACCESS_TOKEN_TYPES existe pour empecher.
        //
        // Une autorite de signature ne doit jamais accepter d'un appelant la
        // valeur d'un claim dont elle est elle-meme garante.
        Map<String, Object> claimsHostiles = new HashMap<>();
        claimsHostiles.put("type", "refresh_user");
        claimsHostiles.put("userId", "quelqu-un-d-autre");
        claimsHostiles.put("projectId", "autre-projet");
        claimsHostiles.put("telephone", "+22300000000");

        String token = jwtService.generateTokenForUser(
                "utilisateur-legitime", "projet-legitime",
                "a@exemple.test", "nom", java.util.List.of("ROLE_USER"),
                claimsHostiles);

        Claims claims = parse(token).getPayload();

        assertThat(claims.get("type"))
                .withFailMessage("le client a réécrit `type` : il décide donc si "
                        + "son jeton est un jeton d'accès ou de rafraîchissement")
                .isEqualTo("user");
        assertThat(claims.get("userId"))
                .withFailMessage("le client a réécrit `userId` : le jeton désigne "
                        + "quelqu'un d'autre que le sujet signé")
                .isEqualTo("utilisateur-legitime");
        assertThat(claims.get("projectId")).isEqualTo("projet-legitime");

        // Les claims qui ne sont PAS des invariants passent toujours : c'est
        // l'usage legitime de ce parametre, et auth-service s'en sert pour le
        // telephone et le role principal.
        assertThat(claims.get("telephone"))
                .withFailMessage("les claims personnalisés légitimes ne passent "
                        + "plus : la correction a trop serré")
                .isEqualTo("+22300000000");
    }

    @Test
    @DisplayName("le jeton de maintenance porte le type attendu par son filtre")
    void typeDeMaintenanceAccordeAuFiltre() {
        // MaintenanceController obtenait ce jeton en glissant
        // type: "maintenance" dans les customClaims — donc en exploitant le
        // defaut corrige ci-dessus. Il passe desormais par une methode dediee,
        // et ce test verifie que le type produit est bien celui que
        // MaintenanceJwtFilter exige. Les deux cotes declarent la chaine
        // separement ; sans ce test, la correction aurait pu casser la
        // maintenance en silence.
        String token = jwtService.generateMaintenanceToken(
                "admin-1", "admin@exemple.test", "+22300000000");

        Claims claims = parse(token).getPayload();

        assertThat(claims.get("type"))
                .withFailMessage("le type ne correspond plus à celui exigé par "
                        + "MaintenanceJwtFilter : l'accès maintenance est cassé")
                .isEqualTo("maintenance");
        assertThat(claims.getSubject()).isEqualTo("admin-1");
    }

    @Test
    @DisplayName("un token illisible fait échouer l'extraction plutôt que de renvoyer du vide")
    void malformedTokenFailsLoudly() {
        assertThatThrownBy(() -> jwtService.getDeveloperIdFromToken("pas.un.jwt"))
                .isInstanceOf(RuntimeException.class);
    }
}
