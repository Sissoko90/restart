package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.entities.MessageTemplate;
import com.abdatytch.auth_service.service.IMessageTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Endpoints de gestion des modeles de message.
 *
 * <h2>Pourquoi ces endpoints meritent des tests malgre leur simplicite</h2>
 *
 * Les modeles servent a composer les messages envoyes par SMS et par courriel —
 * dont les codes de verification. Qui peut les modifier peut changer le texte
 * qu'une personne recoit au moment ou elle s'authentifie, donc fabriquer un
 * message d'hameconnage expedie par la plateforme elle-meme, depuis ses propres
 * numeros et adresses.
 *
 * <p>C'est pourquoi le {@code @PreAuthorize("hasRole('ADMIN')")} est pose sur la
 * <b>classe</b> et non sur chaque methode : un endpoint ajoute plus tard est
 * couvert sans que personne n'ait a y penser. La regle elle-meme est figee par
 * {@code SecurityConfigTest}, qui verifie que {@code /admin/**} exige ADMIN.
 */
@DisplayName("MessageTemplateController - modeles de message")
class MessageTemplateControllerTest {

    private static final String CHEMIN = "/admin/templates";

    private IMessageTemplateService templateService;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        templateService = Mockito.mock(IMessageTemplateService.class);
        client = WebTestClient.bindToController(
                new MessageTemplateController(templateService)).build();
    }

    private MessageTemplate modele() {
        MessageTemplate m = new MessageTemplate();
        m.setId("modele-1");
        return m;
    }

    // ==================================================================
    @Nested
    @DisplayName("Lecture")
    class Lecture {

        @Test
        @DisplayName("GET rend la liste des modeles")
        void listeDesModeles() {
            when(templateService.getAllTemplates()).thenReturn(Flux.just(modele()));

            client.get().uri(CHEMIN)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$[0].id").isEqualTo("modele-1");
        }

        @Test
        @DisplayName("aucun modele rend une liste vide, pas une reponse sans corps")
        void aucunModele() {
            // Un Flux vide collecte en liste donne une liste vide. Si le
            // controleur rendait un Mono vide, WebFlux repondrait 200 sans corps
            // et l'appelant ne pourrait pas distinguer l'absence d'une panne.
            when(templateService.getAllTemplates()).thenReturn(Flux.empty());

            client.get().uri(CHEMIN)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$").isArray();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Creation et modification")
    class Ecriture {

        @Test
        @DisplayName("POST rend 201 CREATED, et non 200")
        void creation() {
            // 201 est la bonne reponse a une creation : elle indique qu'une
            // ressource est apparue, ce que 200 ne dit pas. Ma premiere version
            // de ce test attendait 200 et accusait le code.
            when(templateService.createTemplate(any())).thenReturn(Mono.just(modele()));

            client.post().uri(CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"templateType":"SMS_VERIFICATION",\
                            "content":"Votre code est {code}","isActive":true}""")
                    .exchange()
                    .expectStatus().isCreated();

            verify(templateService).createTemplate(any());
        }

        @Test
        @DisplayName("PUT modifie le modele designe par le chemin")
        void modification() {
            when(templateService.updateTemplate(eq("modele-1"), any()))
                    .thenReturn(Mono.just(modele()));

            client.put().uri(CHEMIN + "/modele-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""
                            {"templateType":"SMS_VERIFICATION",\
                            "content":"Nouveau texte {code}","isActive":true}""")
                    .exchange()
                    .expectStatus().isOk();

            // L'identifiant vient du chemin, le contenu du corps : le service ne
            // doit pas pouvoir etre amene a modifier un autre modele que celui
            // designe.
            verify(templateService).updateTemplate(eq("modele-1"), any());
        }

        @Test
        @DisplayName("un corps invalide rend 400 sans atteindre le service")
        void corpsInvalide() {
            client.post().uri(CHEMIN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isBadRequest();

            verify(templateService, never()).createTemplate(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Suppression")
    class Suppression {

        @Test
        @DisplayName("DELETE supprime le modele designe")
        void suppression() {
            when(templateService.deleteTemplate("modele-1")).thenReturn(Mono.empty());

            client.delete().uri(CHEMIN + "/modele-1")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.message").exists();

            verify(templateService).deleteTemplate("modele-1");
        }
    }
}
