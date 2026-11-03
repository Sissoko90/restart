package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.request.UpdateProfileRequest;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.VerificationResponse;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.service.IAuthService;
import com.abdatytch.auth_service.service.IJwtService;
import com.abdatytch.auth_service.service.IOAuth2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoints d'{@link AuthController}.
 *
 * <h2>Perimetre</h2>
 *
 * Ces tests portent sur la <b>couche web</b> : liaison du corps de requete,
 * validation, codes de reponse, et surtout <b>d'ou viennent les valeurs</b> que
 * le controleur transmet au service. La logique metier a ses propres tests, et
 * les regles d'autorisation sont figees par {@code SecurityConfigTest}.
 *
 * <p>Ce decoupage importe pour une raison precise : un controleur reactif peut
 * etre correct ligne par ligne et faux a l'assemblage. Trois pieges y sont
 * verifies nommement.
 */
@DisplayName("AuthController - endpoints")
class AuthControllerTest {

    private static final String JETON = "Bearer jeton-de-test";
    private static final String ID_UTILISATEUR = "11111111-1111-1111-1111-111111111111";

    private WebTestClient client;
    private WebTestClient clientSansIdentite;

    private IAuthService authService;
    private IOAuth2Service oauth2Service;
    private IJwtService jwtService;

    /**
     * Le controleur est lie directement, sans contexte Spring.
     *
     * <p>{@code @WebFluxTest} chargeait une tranche d'application qui embarque
     * les {@code WebFilter} annotes {@code @Component} — dont
     * {@code GatewayAccessWebFilter}, qui exige un {@code HMACSignatureUtil}
     * absent de la tranche. Le fournir par un simulacre n'aurait pas suffi : le
     * filtre aurait alors tourne et refuse toute requete sans signature du
     * Gateway, ce qui n'est pas ce qu'on mesure ici. La signature est verifiee
     * par ses propres tests, et les regles d'autorisation par
     * {@code SecurityConfigTest}.
     *
     * <p>Lier le controleur seul exerce exactement la couche visee — liaison du
     * corps, validation, codes de reponse — et le fait en quelques
     * millisecondes au lieu de quelques secondes.
     */
    @BeforeEach
    void setUp() {
        authService = Mockito.mock(IAuthService.class);
        oauth2Service = Mockito.mock(IOAuth2Service.class);

        // ObjectMapper reel et non simulacre : il ne sert qu'a lire le champ
        // « user » du rappel Apple, et un vrai lecteur JSON est plus fidele
        // qu'un simulacre pour cet usage.
        AuthController controleur = new AuthController(authService, oauth2Service,
                new com.fasterxml.jackson.databind.ObjectMapper());

        // L'identite est ecrite dans le contexte Reactor, comme le fait le vrai
        // JwtAuthenticationWebFilter.
        //
        // Le controleur ne decode plus le jeton lui-meme : il lisait l'en-tete
        // Authorization et redemandait l'identifiant a IJwtService, alors que
        // le filtre venait de le faire. Deux sources pour une meme identite,
        // dont la seconde ne consultait pas la revocation que le filtre lit.
        var identite = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(ID_UTILISATEUR, null,
                java.util.List.of(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_USER")));

        // Le VRAI gestionnaire d'exceptions est fourni a la tranche.
        //
        // Sans lui, l'AuthException(AUTH_1010) levee faute de contexte de
        // securite remontait en 500, alors qu'en production la regle
        // « tout code AUTH_ vaut 401 » s'applique. Le test aurait mesure
        // l'absence du gestionnaire, pas le comportement de l'endpoint.
        var gestionnaire = new com.abdatytch.auth_service.exception.GlobalExceptionHandler();

        client = WebTestClient.bindToController(controleur)
                .controllerAdvice(gestionnaire)
                .webFilter((echange, suite) -> suite.filter(echange)
                        .contextWrite(org.springframework.security.core.context
                                .ReactiveSecurityContextHolder.withAuthentication(identite)))
                .build();

        // Client SANS identite : la chaine de securite refuse ces chemins avant
        // le controleur, mais il faut verifier que le controleur ne se replie
        // pas silencieusement sur un identifiant nul.
        clientSansIdentite = WebTestClient.bindToController(controleur)
                .controllerAdvice(gestionnaire)
                .build();
    }

    private AuthResponse reponseAuth() {
        AuthResponse r = new AuthResponse();
        r.setAccessToken("jeton-acces");
        r.setRefreshToken("jeton-rafraichissement");
        return r;
    }

    // ==================================================================
    @Nested
    @DisplayName("Connexion")
    class Connexion {

        @Test
        @DisplayName("POST /auth/login rend 200 et les jetons")
        void connexionReussie() {
            when(authService.login(any(), any(), any())).thenReturn(Mono.just(reponseAuth()));

            client.post().uri("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"phoneNumber":"+22374427759","password":"MotDePasse123!"}""")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.accessToken").isEqualTo("jeton-acces");
        }

        @Test
        @DisplayName("l'adresse IP transmise au service vient de l'en-tete du Gateway")
        void ipVientDuGateway() {
            // Le service journalise cette adresse dans le registre
            // d'authentification, et c'est elle qui sert a reperer une attaque.
            // Elle doit venir de X-Client-Ip, pose par le Gateway apres
            // resolution — jamais d'un en-tete que le client controle.
            when(authService.login(any(), any(), any())).thenReturn(Mono.just(reponseAuth()));

            client.post().uri("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Ip", "203.0.113.7")
                    .header("X-Forwarded-For", "10.0.0.1")
                    .bodyValue("""
                            {"phoneNumber":"+22374427759","password":"MotDePasse123!"}""")
                    .exchange()
                    .expectStatus().isOk();

            ArgumentCaptor<String> ip = ArgumentCaptor.forClass(String.class);
            verify(authService).login(any(), ip.capture(), any());

            assertThat(ip.getValue())
                    .withFailMessage("l'IP transmise au service ne vient pas de X-Client-Ip")
                    .isEqualTo("203.0.113.7");
        }

        @Test
        @DisplayName("un corps invalide rend 400, sans appeler le service")
        void corpsInvalide() {
            // @Valid doit arreter la requete avant la couche metier : un numero
            // mal forme ne doit pas atteindre la base.
            client.post().uri("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"phoneNumber":"pas-un-numero","password":"x"}""")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(authService, never()).login(any(), any(), any());
        }

        @Test
        @DisplayName("le service n'est appele qu'a la souscription, pas a l'assemblage")
        void appelDiffere() {
            // Le corps de la methode est enveloppe dans Mono.defer. Sans cela,
            // ReactiveRequestUtil.getClientIp(exchange) serait evalue a
            // l'assemblage de la chaine — c'est l'un des pieges Reactor
            // repertories dans ce depot. Ici on verifie seulement que le resultat
            // est correct de bout en bout ; l'absence d'evaluation anticipee se
            // lit a l'IP capturee par le test precedent.
            when(authService.login(any(), any(), any())).thenReturn(Mono.just(reponseAuth()));

            client.post().uri("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"phoneNumber":"+22374427759","password":"MotDePasse123!"}""")
                    .exchange()
                    .expectStatus().isOk();

            verify(authService).login(any(), any(), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Deconnexion")
    class Deconnexion {

        @Test
        @DisplayName("POST /auth/logout revoque le jeton de rafraichissement fourni")
        void deconnexionAvecCorps() {
            when(authService.logout(any(), any(), any(), any())).thenReturn(Mono.empty());

            client.post().uri("/auth/logout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"refreshToken":"jeton-rafraichissement"}""")
                    .exchange()
                    .expectStatus().isOk();

            verify(authService).logout(
                    eq("jeton-rafraichissement"), eq(ID_UTILISATEUR), any(), any());
        }

        /**
         * La tolerance « deconnexion sans jeton valide » a ete RETIREE.
         *
         * <p>Le test precedent simulait un jeton invalide et verifiait que le
         * service recevait {@code null} comme identifiant. Cette branche etait
         * <b>inatteignable en production</b> : {@code /auth/logout} releve de
         * {@code .pathMatchers("/auth/**").authenticated()}, donc un appel sans
         * jeton valide est refuse en 401 par la chaine et n'arrive jamais au
         * controleur.
         *
         * <p>Le test passait parce qu'il liait le controleur seul, sans la
         * chaine. Il verrouillait donc un comportement qui n'existait que dans
         * le test — la forme la plus trompeuse d'un test vert.
         */
        @Test
        @DisplayName("sans identite, la deconnexion est refusee et ne revoque RIEN")
        void deconnexionSansIdentite() {
            clientSansIdentite.post().uri("/auth/logout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"refreshToken":"jeton-rafraichissement"}""")
                    .exchange()
                    .expectStatus().isUnauthorized();

            verify(authService, never()).logout(any(), any(), any(), any());
        }

        @Test
        @DisplayName("POST /auth/logout aboutit sans corps du tout")
        void deconnexionSansCorps() {
            // @RequestBody(required = false) : un client qui ne garde pas le
            // jeton de rafraichissement doit pouvoir se deconnecter.
            when(authService.logout(any(), any(), any(), any())).thenReturn(Mono.empty());

            client.post().uri("/auth/logout")
                    .exchange()
                    .expectStatus().isOk();

            verify(authService).logout(eq(null), eq(ID_UTILISATEUR), any(), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Profil - l'identifiant vient du jeton")
    class Profil {

        @Test
        @DisplayName("GET /auth/profile lit l'identifiant dans le jeton, pas dans la requete")
        void profilLuDepuisLeJeton() {
            // C'est la protection contre l'IDOR, et elle est structurelle : il
            // n'y a aucun parametre d'identifiant a falsifier. Le jour ou
            // quelqu'un ajoutera /auth/profile/{userId} par commodite, ce test
            // restera vert — d'ou le test suivant, qui verifie que le service
            // recoit bien l'identifiant du jeton.
            AuthResponse profil = reponseAuth();
            when(authService.getUserProfile(ID_UTILISATEUR)).thenReturn(Mono.just(profil));

            client.get().uri("/auth/profile")
                    .exchange()
                    .expectStatus().isOk();

            verify(authService).getUserProfile(ID_UTILISATEUR);
        }

        /**
         * Remplace deux tests qui simulaient un jeton invalide ou un en-tete mal
         * forme.
         *
         * <p>Ces deux cas relevent desormais de la chaine de securite, qui les
         * refuse avant le controleur — et ils sont deja verifies par
         * {@code SecurityConfigTest} et {@code JwtAuthenticationWebFilterTest}.
         * Ce qui reste a verifier ICI est la seule chose dont le controleur
         * repond : qu'il ne se replie pas sur un identifiant nul quand le
         * contexte est vide.
         */
        @Test
        @DisplayName("sans identite, le profil est refuse et le service n'est pas appele")
        void profilSansIdentite() {
            clientSansIdentite.get().uri("/auth/profile")
                    .exchange()
                    .expectStatus().isUnauthorized();

            verify(authService, never()).getUserProfile(anyString());
        }

        @Test
        @DisplayName("PUT /auth/profile met a jour le proprietaire du jeton")
        void miseAJourDuProfil() {
            User modifie = new User();
            modifie.setId(ID_UTILISATEUR);
            when(authService.updateProfile(eq(ID_UTILISATEUR), any(UpdateProfileRequest.class),
                    any(com.abdatytch.auth_service.dto.ContexteDeSecurite.class)))
                    .thenReturn(Mono.just(modifie));

            client.put().uri("/auth/profile")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"firstName":"Awa","lastName":"Traore"}""")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    // Le couple message + success du contrat d'origine est
                    // CONSERVE : MessageResponse porte `success` en Boolean
                    // avec @JsonInclude(NON_NULL), donc les autres reponses qui
                    // n'avaient que `message` n'en gagnent pas un.
                    .jsonPath("$.success").isEqualTo(true)
                    .jsonPath("$.message").exists();

            verify(authService).updateProfile(eq(ID_UTILISATEUR), any(), any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Renvoi de code - le defaut de robustesse corrige")
    class RenvoiDeCode {

        @ParameterizedTest(name = "numero court {0} : reponse metier, pas 500")
        @ValueSource(strings = {"12", "+22", "223"})
        @DisplayName("un numero valide mais plus court que quatre caracteres ne provoque plus d'erreur serveur")
        void numeroCourtNeCassePlus(String numero) {
            // LE defaut. Le motif E.164 applique par @Pattern est
            // ^\+?[1-9]\d{1,14}$ : il accepte deux caracteres. Le controleur
            // faisait ensuite substring(0, 4) pour deduire l'indicatif, ce qui
            // levait une StringIndexOutOfBoundsException sur ces trois valeurs —
            // toutes recevables. L'endpoint rendait 500 sur une entree que le
            // client controle.
            VerificationResponse reponse = new VerificationResponse();
            when(authService.renvoyerLeCode(any())).thenReturn(Mono.just(reponse));

            client.post().uri("/auth/resend-code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"phoneNumber\":\"" + numero + "\"}")
                    .exchange()
                    .expectStatus().isOk();
        }

        /**
         * L'endpoint sert aussi l'inscription par adresse.
         *
         * <p>Il prenait un {@code ForgotPasswordRequest}, qui ne porte qu'un
         * numero et l'exige : qui s'etait inscrit par adresse n'avait aucun
         * moyen de se faire renvoyer son code, et recommencer butait sur
         * {@code EMAIL_ALREADY_EXISTS}.
         */
        @Test
        @DisplayName("une demande par adresse est acceptee, et transmise telle quelle")
        void demandeParAdresse() {
            when(authService.renvoyerLeCode(any()))
                    .thenReturn(Mono.just(new VerificationResponse()));

            client.post().uri("/auth/resend-code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"email":"awa@exemple.test"}""")
                    .exchange()
                    .expectStatus().isOk();

            var requete = ArgumentCaptor.forClass(
                    com.abdatytch.auth_service.dto.request.ResendCodeRequest.class);
            verify(authService).renvoyerLeCode(requete.capture());
            assertThat(requete.getValue().getEmail()).isEqualTo("awa@exemple.test");
            assertThat(requete.getValue().getPhoneNumber()).isNull();
        }

        @Test
        @DisplayName("un numero vraiment invalide est toujours rejete en 400")
        void numeroInvalideToujoursRejete() {
            // Le changement de DTO ne doit pas avoir relache la validation :
            // le motif E.164 a ete REPRIS de ForgotPasswordRequest. Sans lui,
            // un numero quelconque atteindrait le service, qui en deduit un
            // indicatif — d'ou venait le 500 corrige plus haut.
            client.post().uri("/auth/resend-code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"phoneNumber":"1"}""")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(authService, never()).renvoyerLeCode(any());
        }

        /**
         * Un canal, et un seul.
         *
         * <p>Les deux renseignes ne disent pas lequel la personne attend, et
         * choisir a sa place enverrait le code la ou elle ne regarde pas.
         * Aucun des deux ne designe personne.
         */
        @Test
        @DisplayName("les deux canaux a la fois, ou aucun, sont rejetes en 400")
        void canalAmbiguRejete() {
            for (String corps : new String[]{
                    "{\"phoneNumber\":\"+22374427759\",\"email\":\"a@b.test\"}",
                    "{}"}) {
                client.post().uri("/auth/resend-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(corps)
                        .exchange()
                        .expectStatus().isBadRequest();
            }

            verify(authService, never()).renvoyerLeCode(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Mot de passe oublie")
    class MotDePasseOublie {

        @Test
        @DisplayName("POST /auth/forgot-password rend 200")
        void demandeAcceptee() {
            when(authService.initiateForgotPassword(anyString()))
                    .thenReturn(Mono.just(new VerificationResponse()));

            client.post().uri("/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"phoneNumber":"+22374427759"}""")
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("POST /auth/reset-password rend 200 et un message")
        void reinitialisation() {
            when(authService.resetPassword(any(), any())).thenReturn(Mono.empty());

            client.post().uri("/auth/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    // confirmPassword est @NotBlank : l'omettre rend 400. Ma
                    // premiere version du test l'oubliait et accusait le code.
                    .bodyValue("""
                            {"phoneNumber":"+22374427759","code":"123456",\
                            "newPassword":"NouveauMotDePasse123",\
                            "confirmPassword":"NouveauMotDePasse123"}""")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.message").exists();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Rafraichissement de jeton")
    class Rafraichissement {

        @Test
        @DisplayName("POST /auth/refresh rend de nouveaux jetons")
        void rafraichissementReussi() {
            when(authService.refreshToken(eq("ancien-jeton"), any(), any()))
                    .thenReturn(Mono.just(reponseAuth()));

            client.post().uri("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"refreshToken":"ancien-jeton"}""")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.accessToken").isEqualTo("jeton-acces");
        }

        @Test
        @DisplayName("un corps sans jeton de rafraichissement rend 400")
        void jetonManquant() {
            client.post().uri("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(authService, never()).refreshToken(any(), any(), any());
        }
    }
}
