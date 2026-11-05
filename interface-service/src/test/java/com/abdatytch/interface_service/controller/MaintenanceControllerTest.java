package com.abdatytch.interface_service.controller;

import com.abdatytch.commons.totp.TotpService;
import com.abdatytch.interface_service.entity.User;
import com.abdatytch.interface_service.exception.GlobalExceptionHandler;
import com.abdatytch.interface_service.repository.UserRepository;
import com.abdatytch.interface_service.service.IDevJwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La maintenance admin, exercée pour la première fois.
 *
 * <h2>Ce qu'est cet endpoint</h2>
 *
 * {@code /maintenance/auth} délivre le jeton qui autorise à changer les
 * identifiants admin, à régénérer son TOTP et à débloquer son compte. C'est la
 * dernière couche de la plateforme : ce qui se passe mal ici ne se rattrape
 * nulle part.
 *
 * <h2>Les défauts trouvés ici</h2>
 *
 * <ol>
 *   <li><b>Un oracle à cinq réponses.</b> Les cinq façons d'échouer donnaient
 *       cinq réponses distinctes — 403 « Secret admin invalide », 403 « Clé de
 *       maintenance invalide », 401 « Utilisateur maintenance non trouvé », 401
 *       « Mot de passe incorrect », 401 « Code TOTP incorrect ». Un attaquant
 *       apprenait donc lequel des cinq facteurs il avait trouvé, et pouvait les
 *       chercher un par un au lieu d'avoir à les combiner.</li>
 *   <li><b>Le secret admin n'arrivait jamais.</b> Le contrôleur lisait
 *       {@code System.getenv("ADMIN_SECRET_KEY")}, que {@code DotenvConfig} ne
 *       peut pas alimenter — il dépose ses valeurs dans l'{@code Environment}
 *       Spring, et {@code System.getenv} est immuable en Java. Renseigner la
 *       variable dans {@code .env}, ce que {@code .env.example} demande,
 *       n'avait aucun effet : {@code getenv} rendait {@code null} et l'endpoint
 *       répondait 403 à tout le monde.</li>
 *   <li><b>Une panne de base se déguisait en refus.</b> Le
 *       {@code onErrorResume} final rendait 401 avec {@code e.getMessage()}
 *       dans le corps. Un incident de base devenait donc un refus
 *       d'authentification — faisant réessayer le client avec d'autres
 *       identifiants — et publiait au passage un message interne.</li>
 *   <li><b>Deux définitions de {@code totpEnabled} dans le même
 *       contrôleur.</b> {@code /totp-config} lisait le drapeau
 *       {@code user.getTotpEnabled()} ; {@code /security-audit} publiait sous
 *       le même nom le résultat de « un secret existe ». Les deux pouvaient se
 *       contredire, et l'écart entre eux — enrôlement commencé, jamais
 *       confirmé — est précisément ce qu'un audit doit voir.</li>
 * </ol>
 *
 * <h2>Le conseil global est enregistré</h2>
 *
 * {@code .controllerAdvice(new GlobalExceptionHandler())}, parce que c'est le
 * montage qui existe en production. L'omettre est ce qui a laissé passer, dans
 * {@code AuthControllerTest}, un 401 qui sortait en 500.
 */
@DisplayName("MaintenanceController - la derniere couche")
class MaintenanceControllerTest {

    private static final String SECRET_ADMIN = "secret-admin-de-test";
    private static final String CLE_MAINTENANCE = "cle-dure-de-maintenance-de-test";
    private static final String COURRIEL_ADMIN = "admin@example.test";
    private static final String TELEPHONE = "+22300000000";
    private static final String MOT_DE_PASSE = "mot-de-passe-correct";
    private static final String CODE_TOTP = "123456";

    private UserRepository depot;
    private PasswordEncoder encodeur;
    private TotpService totp;
    private IDevJwtService jwt;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        depot = Mockito.mock(UserRepository.class);
        encodeur = Mockito.mock(PasswordEncoder.class);
        totp = Mockito.mock(TotpService.class);
        jwt = Mockito.mock(IDevJwtService.class);

        client = construire(SECRET_ADMIN);
    }

    /**
     * Les champs {@code @Value} sont posés APRÈS {@code build()}.
     *
     * <p>Ma première version les posait avant, et ils étaient <b>écrasés</b> :
     * {@code bindToController} enregistre l'instance comme bean et lui applique
     * le traitement d'injection, qui remet les {@code @Value} à leur valeur
     * résolue — nulle ou vide, faute de source de propriétés dans ce montage.
     *
     * <p>Le symptôme était trompeur : {@code findByEmail(null)} ne
     * correspondait à aucun stub et rendait {@code null}, donc une
     * {@code NullPointerException} que le conseil global rendait en 500. Et
     * surtout, les deux tests « secret non configuré » <b>passaient pour la
     * mauvaise raison</b> : ils attendaient un 401, et le secret était nul de
     * toute façon. Ils ne pouvaient pas échouer.
     */
    private WebTestClient construire(String secretAdmin) {
        MaintenanceController controleur =
                new MaintenanceController(depot, encodeur, totp, jwt);

        WebTestClient construit = WebTestClient.bindToController(controleur)
                .controllerAdvice(new GlobalExceptionHandler())
                .build();

        ReflectionTestUtils.setField(controleur, "adminEmail", COURRIEL_ADMIN);
        ReflectionTestUtils.setField(controleur, "maintenanceSecretKey", CLE_MAINTENANCE);
        ReflectionTestUtils.setField(controleur, "adminSecretKey", secretAdmin);

        return construit;
    }

    private User admin() {
        User u = new User();
        u.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        u.setEmail(COURRIEL_ADMIN);
        u.setPhoneNumber(TELEPHONE);
        u.setPasswordHash("empreinte-du-mot-de-passe");
        u.setRecoverySecretHash("empreinte-du-secret-de-recuperation");
        u.setTotpSecret("SECRETTOTPBASE32");
        u.setTotpEnabled(true);
        return u;
    }

    /** Pose le chemin heureux : admin trouvé, mot de passe bon, TOTP bon. */
    private void cheminHeureux() {
        when(depot.findByPhoneNumber(TELEPHONE)).thenReturn(Mono.just(admin()));
        when(depot.findByEmail(COURRIEL_ADMIN)).thenReturn(Mono.just(admin()));
        when(encodeur.matches(anyString(), anyString())).thenReturn(false);
        when(encodeur.matches(MOT_DE_PASSE, "empreinte-du-mot-de-passe")).thenReturn(true);
        when(totp.validateTotpCode(anyString(), anyString())).thenReturn(Mono.just(false));
        when(totp.validateTotpCode("SECRETTOTPBASE32", CODE_TOTP)).thenReturn(Mono.just(true));
        when(jwt.generateMaintenanceToken(anyString(), anyString(), anyString()))
                .thenReturn("jeton-de-maintenance");
    }

    private record Reponse(int statut, String corps) {
    }

    private Reponse auth(String secretAdmin, String cleMaintenance,
                         String telephone, String motDePasse, String code) {
        var resultat = client.post().uri("/maintenance/auth")
                .header("X-Admin-Secret", secretAdmin)
                .header("X-Maintenance-Identifier", cleMaintenance)
                .bodyValue(Map.of(
                        "phoneNumber", telephone,
                        "password", motDePasse,
                        "totpCode", code))
                .exchange()
                .expectBody().returnResult();

        byte[] brut = resultat.getResponseBody();
        return new Reponse(resultat.getStatus().value(),
                brut == null ? "" : new String(brut, StandardCharsets.UTF_8));
    }

    // ==================================================================
    @Nested
    @DisplayName("Les cinq facons d'echouer sont INDISTINGUABLES")
    class PasDOracle {

        /**
         * LE TEST LE PLUS IMPORTANT DE CE FICHIER.
         *
         * <p>Avant correction, ces cinq appels donnaient cinq couples (statut,
         * corps) différents. L'attaquant pouvait donc chercher le secret admin
         * seul, puis la clé de maintenance seule, puis énumérer les numéros,
         * puis le mot de passe, puis le TOTP : cinq recherches indépendantes au
         * lieu d'une combinaison de cinq facteurs.
         *
         * <p>Le test compare les réponses entre elles plutôt qu'à une valeur
         * attendue : c'est l'<b>indistinguabilité</b> qui est la propriété, et
         * elle resterait vraie si l'on changeait un jour le libellé.
         */
        @Test
        @DisplayName("secret, cle, numero, mot de passe et TOTP donnent la MEME reponse")
        void memeReponsePourLesCinq() {
            cheminHeureux();
            when(depot.findByPhoneNumber("+22399999999")).thenReturn(Mono.empty());

            Set<String> distinctes = new LinkedHashSet<>();
            distinctes.add(auth("mauvais-secret", CLE_MAINTENANCE, TELEPHONE,
                    MOT_DE_PASSE, CODE_TOTP).toString());
            distinctes.add(auth(SECRET_ADMIN, "mauvaise-cle", TELEPHONE,
                    MOT_DE_PASSE, CODE_TOTP).toString());
            distinctes.add(auth(SECRET_ADMIN, CLE_MAINTENANCE, "+22399999999",
                    MOT_DE_PASSE, CODE_TOTP).toString());
            distinctes.add(auth(SECRET_ADMIN, CLE_MAINTENANCE, TELEPHONE,
                    "mauvais-mot-de-passe", CODE_TOTP).toString());
            distinctes.add(auth(SECRET_ADMIN, CLE_MAINTENANCE, TELEPHONE,
                    MOT_DE_PASSE, "000000").toString());

            assertThat(distinctes)
                    .as("les cinq echecs doivent donner UNE seule reponse ; sinon "
                            + "l'appelant apprend lequel des cinq facteurs il a "
                            + "trouve, et peut les chercher un par un. Reponses "
                            + "observees : %s", distinctes)
                    .hasSize(1);
        }

        @Test
        @DisplayName("et cette reponse unique est un 401")
        void reponseUniqueEst401() {
            cheminHeureux();

            assertThat(auth(SECRET_ADMIN, CLE_MAINTENANCE, TELEPHONE,
                    "mauvais-mot-de-passe", CODE_TOTP).statut()).isEqualTo(401);
        }

        /**
         * Le corps ne nomme ni le facteur ni l'utilisateur.
         *
         * <p>Le refus pour numéro inconnu renvoyait « Erreur
         * d'authentification: Utilisateur maintenance non trouvé » : de quoi
         * énumérer les numéros qui correspondent à un compte de maintenance.
         */
        @Test
        @DisplayName("le corps ne divulgue ni le facteur ni le numero")
        void corpsMuet() {
            cheminHeureux();
            when(depot.findByPhoneNumber("+22399999999")).thenReturn(Mono.empty());

            String corps = auth(SECRET_ADMIN, CLE_MAINTENANCE, "+22399999999",
                    MOT_DE_PASSE, CODE_TOTP).corps();

            assertThat(corps)
                    .doesNotContain("non trouv")
                    .doesNotContain("Mot de passe")
                    .doesNotContain("TOTP")
                    .doesNotContain("Secret admin")
                    .doesNotContain("+22399999999");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le chemin heureux delivre un jeton")
    class CheminHeureux {

        @Test
        @DisplayName("les cinq facteurs corrects rendent 200 et un jeton")
        void succes() {
            cheminHeureux();

            client.post().uri("/maintenance/auth")
                    .header("X-Admin-Secret", SECRET_ADMIN)
                    .header("X-Maintenance-Identifier", CLE_MAINTENANCE)
                    .bodyValue(Map.of("phoneNumber", TELEPHONE,
                            "password", MOT_DE_PASSE, "totpCode", CODE_TOTP))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.token").isEqualTo("jeton-de-maintenance")
                    .jsonPath("$.expiresIn").isEqualTo("3600");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un secret non configure FERME l'endpoint")
    class SecretNonConfigure {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>Le secret était lu par {@code System.getenv("ADMIN_SECRET_KEY")},
         * que {@code .env} ne peut pas alimenter : il valait donc {@code null}
         * en pratique, et cet endpoint refusait tout le monde. Désormais la
         * valeur vient de {@code admin.secret.key}, la même propriété que
         * {@code AdminSecretFilter}.
         *
         * <p>Ce test garde le sens du repli : une valeur absente <b>ferme</b>,
         * elle n'ouvre pas. C'est l'erreur qu'il faut rendre impossible, pas
         * l'inverse.
         */
        @Test
        @DisplayName("un secret vide refuse, meme avec les bons autres facteurs")
        void secretVideRefuse() {
            client = construire("");
            cheminHeureux();

            assertThat(auth("", CLE_MAINTENANCE, TELEPHONE, MOT_DE_PASSE, CODE_TOTP)
                    .statut())
                    .as("un secret admin non configure doit FERMER l'endpoint")
                    .isEqualTo(401);

            verify(jwt, never()).generateMaintenanceToken(anyString(), anyString(),
                    anyString());
        }

        @Test
        @DisplayName("un secret nul refuse aussi")
        void secretNulRefuse() {
            client = construire(null);
            cheminHeureux();

            assertThat(auth("peu-importe", CLE_MAINTENANCE, TELEPHONE,
                    MOT_DE_PASSE, CODE_TOTP).statut()).isEqualTo(401);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une panne reste une panne")
    class PanneNonDeguisee {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>Le {@code onErrorResume} final rendait 401 avec
         * {@code e.getMessage()}. Une erreur de base devenait donc un refus
         * d'authentification : le client réessayait avec d'autres identifiants
         * alors que rien chez lui n'était en cause, et le message interne était
         * publié.
         */
        @Test
        @DisplayName("une erreur de base rend 500, et non 401")
        void erreurDeBaseRend500() {
            cheminHeureux();
            when(depot.findByPhoneNumber(TELEPHONE)).thenReturn(
                    Mono.error(new IllegalStateException("connexion R2DBC perdue")));

            Reponse reponse = auth(SECRET_ADMIN, CLE_MAINTENANCE, TELEPHONE,
                    MOT_DE_PASSE, CODE_TOTP);

            assertThat(reponse.statut())
                    .as("une panne rendue en 401 fait chercher au client une faute "
                            + "d'identifiants qu'il n'a pas commise")
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("et le message interne n'est pas publie")
        void messageInterneNonPublie() {
            cheminHeureux();
            when(depot.findByPhoneNumber(TELEPHONE)).thenReturn(
                    Mono.error(new IllegalStateException("connexion R2DBC perdue")));

            assertThat(auth(SECRET_ADMIN, CLE_MAINTENANCE, TELEPHONE,
                    MOT_DE_PASSE, CODE_TOTP).corps())
                    .doesNotContain("R2DBC")
                    .doesNotContain("connexion");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'audit distingue le drapeau du secret")
    class Audit {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>{@code /security-audit} publiait « totpEnabled » en calculant
         * « un secret existe », alors que {@code /totp-config} publie le même
         * nom en lisant le drapeau. L'enrôlement TOTP se faisant en deux temps,
         * l'écart entre les deux signifie « commencé, jamais confirmé » — ce
         * qu'un audit doit voir, et ce que le champ unique masquait.
         */
        @Test
        @DisplayName("un secret present avec le drapeau a faux est VISIBLE")
        void enrolementNonConfirmeVisible() {
            User u = admin();
            u.setTotpEnabled(false);
            u.setTotpSecret("SECRETTOTPBASE32");
            when(depot.findByEmail(COURRIEL_ADMIN)).thenReturn(Mono.just(u));

            client.get().uri("/maintenance/security-audit").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.totpEnabled").isEqualTo(false)
                    .jsonPath("$.totpSecretPresent").isEqualTo(true);
        }

        @Test
        @DisplayName("un admin absent rend 404")
        void adminAbsent() {
            when(depot.findByEmail(COURRIEL_ADMIN)).thenReturn(Mono.empty());

            client.get().uri("/maintenance/security-audit").exchange()
                    .expectStatus().isNotFound();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les trois endpoints a mot de passe le verifient")
    class MotDePasseExige {

        @Test
        @DisplayName("regenerate-totp refuse un mauvais mot de passe sans toucher au secret")
        void regenerationRefusee() {
            cheminHeureux();

            client.post().uri("/maintenance/regenerate-totp")
                    .bodyValue(Map.of("password", "mauvais"))
                    .exchange()
                    .expectStatus().isUnauthorized();

            // Le secret ne doit pas avoir ete regenere : sinon un appelant sans
            // le mot de passe invaliderait le TOTP de l'admin.
            verify(totp, never()).generateTotpSecret();
            verify(depot, never()).save(any());
        }

        @Test
        @DisplayName("totp-config refuse un mauvais mot de passe sans rendre le secret")
        void configurationRefusee() {
            cheminHeureux();

            byte[] brut = client.post().uri("/maintenance/totp-config")
                    .bodyValue(Map.of("password", "mauvais"))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody().returnResult().getResponseBody();
            String corps = brut == null ? "" : new String(brut, StandardCharsets.UTF_8);

            // L'URL de provisionnement contient le secret TOTP EN CLAIR : la
            // posseder revient a pouvoir generer tous les codes du compte admin.
            assertThat(corps)
                    .doesNotContain("SECRETTOTPBASE32")
                    .doesNotContain("qrCodeUrl");
        }

        @Test
        @DisplayName("change-credentials refuse un mauvais secret de recuperation")
        void changementRefuse() {
            cheminHeureux();

            client.post().uri("/maintenance/change-credentials")
                    .bodyValue(Map.of("recoverySecret", "mauvais",
                            "newPassword", "nouveau-mot-de-passe"))
                    .exchange()
                    .expectStatus().isUnauthorized();

            verify(depot, never()).save(any());
        }
    }
}
