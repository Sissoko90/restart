package com.abdatytch.interface_service.controller;

import com.abdatytch.interface_service.dto.request.CreateProjectRequest;
import com.abdatytch.interface_service.dto.response.ProjectResponse;
import com.abdatytch.interface_service.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le contrôleur de projets, exercé pour la première fois.
 *
 * <h2>Ce que ce fichier garde</h2>
 *
 * Une seule propriété, et c'est celle qui porte toute la sécurité de ces six
 * endpoints : <b>le propriétaire vient du jeton, jamais de la requête</b>. Le
 * corps de {@code CreateProjectRequest} contient un champ {@code projectId} et
 * les chemins portent un {@code projectId} ; si l'un ou l'autre pouvait décider
 * du propriétaire, n'importe quel porteur agirait sur les projets d'autrui.
 *
 * <h2>Le défaut corrigé</h2>
 *
 * {@code UUID.fromString(jwt.getSubject())} était écrit <b>six fois</b>, une par
 * endpoint, sans rattrapage. Un sujet qui n'est pas un UUID lève
 * {@code IllegalArgumentException}, que le conseil global rend en <b>500</b> —
 * donc « panne de notre côté » pour un jeton que nous n'avons pas émis. C'est
 * désormais un seul extracteur, et un 401.
 *
 * <h2>Pourquoi des appels directs et non WebTestClient</h2>
 *
 * Parce que {@code @AuthenticationPrincipal Jwt} exige un résolveur d'argument
 * et un contexte de sécurité réactif, qu'un montage autonome ne fournit pas —
 * et aucun test de ce dépôt n'avait de précédent à suivre. Appeler les méthodes
 * avec un vrai {@code Jwt} exerce exactement ce qui était en cause : la
 * dérivation du propriétaire et la délégation.
 *
 * <p>Ce que cela ne couvre donc pas : les chemins, les statuts et la sécurité
 * de la couche HTTP. Les statuts sont gardés dans
 * {@code GlobalExceptionHandlerTest}, qui enregistre le conseil ; la chaîne de
 * filtres l'est dans {@code ChaineReelleTest}.
 */
@DisplayName("ProjectController - le proprietaire vient du jeton")
class ProjectControllerTest {

    private static final UUID PORTEUR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJET = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ProjectService service;
    private ProjectController controleur;
    private CreateProjectRequest requete;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ProjectService.class);
        controleur = new ProjectController(service);

        requete = new CreateProjectRequest();
        requete.setProjectName("mon-projet");
        requete.setDescription("une description");
    }

    /** Un jeton dont le sujet est la valeur donnée. */
    private static Jwt jeton(String sujet) {
        return Jwt.withTokenValue("jeton-de-test")
                .header("alg", "RS256")
                .subject(sujet)
                .claim("roles", "ROLE_DEVELOPER")
                .build();
    }

    private static ProjectResponse reponse() {
        ProjectResponse r = new ProjectResponse();
        r.setId(PROJET);
        r.setName("mon-projet");
        r.setUserId(PORTEUR);
        return r;
    }

    // ==================================================================
    @Nested
    @DisplayName("Les six endpoints derivent le proprietaire du sujet du jeton")
    class ProprietaireDuJeton {

        @Test
        @DisplayName("creation")
        void creation() {
            when(service.createProject(any(), any())).thenReturn(Mono.just(reponse()));

            controleur.createProject(requete, jeton(PORTEUR.toString())).block();

            verify(service).createProject(requete, PORTEUR);
        }

        @Test
        @DisplayName("liste")
        void liste() {
            when(service.findUserProjects(any())).thenReturn(Flux.just(reponse()));

            controleur.getUserProjects(jeton(PORTEUR.toString())).collectList().block();

            verify(service).findUserProjects(PORTEUR);
        }

        @Test
        @DisplayName("lecture par identifiant")
        void lecture() {
            when(service.findProjectById(any(), any())).thenReturn(Mono.just(reponse()));

            controleur.getProject(PROJET, jeton(PORTEUR.toString())).block();

            // Les DEUX identifiants sont transmis : le projet vient du chemin, le
            // proprietaire du jeton. C'est le service qui refuse si le projet
            // n'appartient pas au porteur.
            verify(service).findProjectById(PROJET, PORTEUR);
        }

        @Test
        @DisplayName("mise a jour")
        void miseAJour() {
            when(service.updateProject(any(), any(), any())).thenReturn(Mono.just(reponse()));

            controleur.updateProject(PROJET, requete, jeton(PORTEUR.toString())).block();

            verify(service).updateProject(PROJET, PORTEUR, requete);
        }

        @Test
        @DisplayName("suppression")
        void suppression() {
            when(service.deleteProject(any(), any())).thenReturn(Mono.empty());

            controleur.deleteProject(PROJET, jeton(PORTEUR.toString())).block();

            verify(service).deleteProject(PROJET, PORTEUR);
        }

        @Test
        @DisplayName("droit de creer")
        void droitDeCreer() {
            when(service.canCreateProject(any())).thenReturn(Mono.just(true));

            controleur.canCreateProject(jeton(PORTEUR.toString())).block();

            verify(service).canCreateProject(PORTEUR);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le corps de la requete ne decide pas du proprietaire")
    class LeCorpsNeDecidePas {

        /**
         * {@code CreateProjectRequest} porte un champ {@code projectId}.
         *
         * <p>Il n'est pas utilisé : {@code ProjectMapper.toEntity} appelle
         * {@code project.generateId()}. Si un jour il l'était, un client
         * choisirait la clé primaire de son projet — donc pourrait viser celle
         * d'un autre. Ce test ne garde pas le mappeur, mais il garde le fait que
         * le contrôleur transmet la requête telle quelle et n'en tire aucun
         * identifiant.
         */
        @Test
        @DisplayName("un projectId pose dans le corps n'est pas lu par le controleur")
        void projectIdDuCorpsIgnore() {
            requete.setProjectId("99999999-9999-9999-9999-999999999999");
            when(service.createProject(any(), any())).thenReturn(Mono.just(reponse()));

            controleur.createProject(requete, jeton(PORTEUR.toString())).block();

            // Le proprietaire transmis est celui du JETON, et rien du corps n'a
            // pu s'y substituer.
            verify(service).createProject(requete, PORTEUR);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un sujet non conforme rend 401, et non 500")
    class SujetNonConforme {

        /**
         * LE DÉFAUT GARDÉ ICI.
         *
         * <p>{@code UUID.fromString} lève {@code IllegalArgumentException} sur
         * un sujet qui n'est pas un UUID, et rien ne la rattrapait : le conseil
         * global l'aurait rendue en 500. Or un sujet non conforme signifie que
         * le jeton ne vient pas de cette plateforme —
         * {@code DevJwtServiceImpl} pose toujours
         * {@code developer.getId().toString()}, et {@code User.id} est un
         * {@code UUID}. C'est un défaut d'authentification, donc 401 : le
         * client doit se réauthentifier, pas réessayer.
         */
        @ParameterizedTest(name = "sujet = [{0}]")
        @ValueSource(strings = {
                "pas-un-uuid", "   ",
                "dev@example.test",
                // Sans tiret : UUID.fromString refuse, mesure.
                "11111111111111111111111111111111"
        })
        @DisplayName("la lecture refuse en 401")
        void lectureRefuse(String sujet) {
            assertThatThrownBy(() -> controleur.getProject(PROJET, jeton(sujet)).block())
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(erreur -> assertThat(
                            ((ResponseStatusException) erreur).getStatusCode().value())
                            .as("un sujet non conforme sort en 500 : la plateforme "
                                    + "s'accuse d'une panne pour un jeton qu'elle n'a "
                                    + "pas emis")
                            .isEqualTo(HttpStatus.UNAUTHORIZED.value()));
        }

        /**
         * Et le service n'est pas appelé.
         *
         * <p>Si l'extraction échouait après la délégation, un sujet non conforme
         * aurait déjà touché la base avant d'être refusé.
         */
        @Test
        @DisplayName("le service n'est jamais atteint")
        void serviceNonAtteint() {
            for (Runnable appel : new Runnable[]{
                    () -> controleur.getProject(PROJET, jeton("pas-un-uuid")).block(),
                    () -> controleur.createProject(requete, jeton("pas-un-uuid")).block(),
                    () -> controleur.deleteProject(PROJET, jeton("pas-un-uuid")).block(),
                    () -> controleur.canCreateProject(jeton("pas-un-uuid")).block()}) {
                try {
                    appel.run();
                } catch (ResponseStatusException attendu) {
                    // C'est le refus attendu.
                }
            }

            verifyNoInteractions(service);
        }

        /**
         * LE CAS QUI SE SERAIT ÉCHAPPÉ.
         *
         * <p>{@code UUID.fromString(null)} lève {@code NullPointerException} et
         * <b>non</b> {@code IllegalArgumentException} — mesuré. Un
         * {@code catch} sur la seconde ne l'attrape donc pas, et le conseil
         * global aurait rendu un 500.
         *
         * <p>Le cas est atteignable : le décodeur de ce module n'exige pas le
         * claim {@code sub}, pas plus qu'il ne vérifie l'émetteur ou
         * l'audience.
         */
        @Test
        @DisplayName("un jeton SANS sujet refuse en 401, et non en 500")
        void sujetAbsent() {
            Jwt sansSujet = Jwt.withTokenValue("jeton-de-test")
                    .header("alg", "RS256")
                    .claim("roles", "ROLE_DEVELOPER")
                    .build();

            assertThatThrownBy(() -> controleur.getProject(PROJET, sansSujet).block())
                    .as("un jeton sans sujet doit etre refuse en 401 ; une "
                            + "NullPointerException non rattrapee sortirait en 500")
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(erreur -> assertThat(
                            ((ResponseStatusException) erreur).getStatusCode().value())
                            .isEqualTo(HttpStatus.UNAUTHORIZED.value()));

            verifyNoInteractions(service);
        }
    }

    // ==================================================================
    /**
     * Ce que {@code UUID.fromString} accepte, consigné.
     *
     * <h2>Pourquoi ce test existe</h2>
     *
     * Ma première version de {@code lectureRefuse} comptait
     * {@code 11111111-1111-1111-1111-11111111111} — un chiffre trop court —
     * parmi les sujets non conformes. Le test a échoué : Java l'<b>accepte</b>.
     *
     * <p>Mesuré sur ce JDK :
     *
     * <pre>
     * 11111111-1111-1111-1111-11111111111 -&gt; 11111111-1111-1111-1111-011111111111
     * 1-1-1-1-1                           -&gt; 00000001-0001-0001-0001-000000000001
     * </pre>
     *
     * Les groupes trop courts sont complétés par des zéros : deux chaînes
     * distinctes peuvent donc désigner le même identifiant.
     *
     * <h2>Pourquoi ce n'est pas corrigé</h2>
     *
     * Parce que ce n'est pas atteignable : le sujet vient d'un jeton signé par
     * cette plateforme, et {@code DevJwtServiceImpl} n'y pose jamais que
     * {@code developer.getId().toString()}, déjà canonique. Rendre l'analyse
     * stricte ajouterait du code pour un chemin que seul le détenteur de la clé
     * privée pourrait emprunter.
     *
     * <p>Ce test est donc là pour <b>dire</b> la tolérance, pas pour la
     * combattre : il empêche que quelqu'un déduise du code qu'un sujet accepté
     * est forcément bien formé.
     */
    @Nested
    @DisplayName("La tolerance de UUID.fromString, consignee")
    class ToleranceConsignee {

        @Test
        @DisplayName("un groupe trop court est complete par des zeros, pas refuse")
        void groupeTropCourtAccepte() {
            when(service.findProjectById(any(), any())).thenReturn(Mono.just(reponse()));

            controleur.getProject(PROJET,
                    jeton("11111111-1111-1111-1111-11111111111")).block();

            verify(service).findProjectById(PROJET,
                    UUID.fromString("11111111-1111-1111-1111-011111111111"));
        }
    }
}
