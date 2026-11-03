package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.response.EtatEffacementResponse;
import com.abdatytch.auth_service.service.IAccountErasureService;
import com.abdatytch.auth_service.entities.ErasureAcknowledgement;
import com.abdatytch.auth_service.entities.ErasureRequest;
import com.abdatytch.auth_service.repository.ErasureAcknowledgementRepository;
import com.abdatytch.auth_service.repository.ErasureRequestRepository;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoints d'effacement (RGPD art. 17).
 *
 * <h2>Pourquoi ces endpoints meritent une attention particuliere</h2>
 *
 * Ce sont les seuls du service dont l'effet est <b>irreversible</b>. Et c'est ici
 * que se trouvait la faille la plus grave du depot : {@code POST /auth/erasure/{subjectId}}
 * etait couvert par la regle generale sur {@code /auth/**}, declaree avant la
 * sienne, si bien que n'importe quel porteur de jeton pouvait effacer les donnees
 * de n'importe qui. L'endpoint lui-meme etait correct — c'est l'ordre des regles
 * qui ne l'etait pas.
 *
 * <p>La matrice d'autorisation est figee par {@code SecurityConfigTest}. Ces
 * tests-ci portent sur ce que le controleur <b>fait</b> une fois l'appel
 * autorise : d'ou vient l'identifiant de la personne effacee, quel motif est
 * enregistre, et quel code de reponse est rendu.
 */
@DisplayName("ErasureController - effacement des donnees")
class ErasureControllerTest {

    private static final String APPELANT = "11111111-1111-1111-1111-111111111111";
    private static final String TIERS = "22222222-2222-2222-2222-222222222222";

    private IAccountErasureService erasureService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        erasureService = Mockito.mock(IAccountErasureService.class);

        var controleur = new ErasureController(erasureService);

        // L'identite est ecrite dans le contexte Reactor, comme le fait le vrai
        // JwtAuthenticationWebFilter : le controleur la lit par
        // ReactiveSecurityContextHolder, et non depuis un parametre.
        var identite = new UsernamePasswordAuthenticationToken(
                APPELANT, null, List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));

        client = WebTestClient.bindToController(controleur)
                .webFilter((echange, suite) -> suite.filter(echange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(identite)))
                .build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Effacement de ses propres donnees")
    class SesPropresDonnees {

        @Test
        @DisplayName("l'identifiant efface est celui du jeton, jamais un parametre")
        void identifiantVientDuJeton() {
            // La protection contre l'IDOR est structurelle : l'endpoint
            // /auth/erasure/me ne prend AUCUN parametre. Il n'y a rien a
            // falsifier. Les deux arguments transmis au service sont le meme
            // identifiant — celui de l'appelant, a la fois sujet et demandeur.
            UUID demande = UUID.randomUUID();
            when(erasureService.requestErasure(anyString(), anyString(), any()))
                    .thenReturn(Mono.just(demande));

            client.post().uri("/auth/erasure/me")
                    .exchange()
                    .expectStatus().isAccepted();

            ArgumentCaptor<String> sujet = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> demandeur = ArgumentCaptor.forClass(String.class);
            verify(erasureService).requestErasure(
                    sujet.capture(), demandeur.capture(), any());

            assertThat(sujet.getValue()).isEqualTo(APPELANT);
            assertThat(demandeur.getValue())
                    .withFailMessage("le demandeur devrait etre la personne elle-meme")
                    .isEqualTo(APPELANT);
        }

        @Test
        @DisplayName("le motif enregistre est la demande de la personne")
        void motifDemandeDeLaPersonne() {
            // Le motif n'est pas cosmetique : l'article 5.2 impose de pouvoir
            // demontrer la conformite, et un effacement demande par la personne
            // n'engage pas l'organisation de la meme facon qu'une decision
            // administrative.
            when(erasureService.requestErasure(anyString(), anyString(), any()))
                    .thenReturn(Mono.just(UUID.randomUUID()));

            client.post().uri("/auth/erasure/me").exchange().expectStatus().isAccepted();

            ArgumentCaptor<ErasureReason> motif = ArgumentCaptor.forClass(ErasureReason.class);
            verify(erasureService).requestErasure(anyString(), anyString(), motif.capture());
            assertThat(motif.getValue()).isEqualTo(ErasureReason.DEMANDE_DE_LA_PERSONNE);
        }

        @Test
        @DisplayName("la reponse est 202, pas 200")
        void reponse202() {
            // L'effacement local est fait, celui des autres services est en
            // cours. Repondre 200 laisserait croire que tout est termine, ce qui
            // serait faux — et l'identifiant de demande rendu n'aurait aucune
            // raison d'exister.
            UUID demande = UUID.randomUUID();
            when(erasureService.requestErasure(anyString(), anyString(), any()))
                    .thenReturn(Mono.just(demande));

            client.post().uri("/auth/erasure/me")
                    .exchange()
                    .expectStatus().isAccepted()
                    .expectBody()
                    .jsonPath("$.requestId").isEqualTo(demande.toString());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Effacement administratif")
    class Administratif {

        @Test
        @DisplayName("le sujet vient du chemin, le demandeur du jeton")
        void sujetEtDemandeurDistincts() {
            // La distinction est ce qui rend la trace exploitable : le registre
            // doit conserver QUI a declenche l'effacement, pas seulement qui l'a
            // subi. Confondre les deux rendrait un effacement administratif
            // indiscernable d'une demande de la personne.
            when(erasureService.requestErasure(anyString(), anyString(), any()))
                    .thenReturn(Mono.just(UUID.randomUUID()));

            client.post().uri("/auth/erasure/" + TIERS)
                    .exchange()
                    .expectStatus().isAccepted();

            ArgumentCaptor<String> sujet = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> demandeur = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<ErasureReason> motif = ArgumentCaptor.forClass(ErasureReason.class);
            verify(erasureService).requestErasure(
                    sujet.capture(), demandeur.capture(), motif.capture());

            assertThat(sujet.getValue()).isEqualTo(TIERS);
            assertThat(demandeur.getValue()).isEqualTo(APPELANT);
            assertThat(motif.getValue()).isEqualTo(ErasureReason.DECISION_ADMINISTRATIVE);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Preuve d'execution")
    class Preuve {

        @Test
        @DisplayName("l'etat rassemble les accuses de reception de chaque service")
        void etatAvecAccuses() {
            // C'est la preuve exigee par l'article 5.2 : affirmer avoir efface
            // sans pouvoir le demontrer ne vaut rien.
            String id = UUID.randomUUID().toString();

            ErasureRequest demande = new ErasureRequest();
            demande.setId(id);
            demande.setRequestedAt(LocalDateTime.now());
            demande.setReason(ErasureReason.DEMANDE_DE_LA_PERSONNE);

            ErasureAcknowledgement accuse = new ErasureAcknowledgement();
            accuse.setServiceName("user-service");
            accuse.setCompletedAt(LocalDateTime.now());
            accuse.setRecordsErased(3);

            // La reponse est construite par le VRAI mappeur du DTO, a partir des
            // memes entites : les assertions jsonPath ci-dessous verifient donc
            // la forme reellement produite, et non une forme reecrite dans le
            // test. C'est ce qui valait quand le controleur assemblait la Map, et
            // qui doit continuer de valoir maintenant qu'un DTO s'en charge.
            when(erasureService.etatDeLaDemande(id)).thenReturn(Mono.just(
                    EtatEffacementResponse.de(demande, List.of(accuse))));

            client.get().uri("/auth/erasure/" + id + "/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.requestId").isEqualTo(id)
                    .jsonPath("$.services[0].service").isEqualTo("user-service")
                    .jsonPath("$.services[0].enregistrements").isEqualTo(3);
        }

        @Test
        @DisplayName("une demande inconnue rend 404")
        void demandeInconnue() {
            String id = UUID.randomUUID().toString();
            when(erasureService.etatDeLaDemande(id)).thenReturn(Mono.empty());

            // Le defaut a eviter : un Mono vide traduit en 200 a corps vide.
            // C'est exactement ce qui s'est produit ailleurs dans ce depot, et
            // sur une preuve d'effacement l'appelant conclurait qu'aucun service
            // n'a efface — au lieu de « cette demande n'existe pas ».
            client.get().uri("/auth/erasure/" + id + "/status")
                    .exchange()
                    .expectStatus().isNotFound();
        }

        @Test
        @DisplayName("une demande sans aucun accuse reste consultable")
        void demandeSansAccuse() {
            // Une demande dont aucun service n'a encore accuse reception doit
            // rendre 200 avec une liste vide, et non 404 : c'est l'etat normal
            // juste apres la demande, et c'est precisement ce qu'on veut pouvoir
            // observer.
            String id = UUID.randomUUID().toString();
            ErasureRequest demande = new ErasureRequest();
            demande.setId(id);
            demande.setRequestedAt(LocalDateTime.now());
            demande.setReason(ErasureReason.DEMANDE_DE_LA_PERSONNE);
            when(erasureService.etatDeLaDemande(id)).thenReturn(Mono.just(
                    EtatEffacementResponse.de(demande, List.of())));

            client.get().uri("/auth/erasure/" + id + "/status")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.services").isArray();
        }
    }
}
