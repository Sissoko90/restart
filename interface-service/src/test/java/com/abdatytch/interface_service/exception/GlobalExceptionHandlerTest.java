package com.abdatytch.interface_service.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Le conseil global, exercé avec le montage qui existe en production.
 *
 * <h2>Le défaut trouvé ici, et il annulait un correctif précédent</h2>
 *
 * {@code handleGenericException} est déclaré sur {@code Exception.class}. Comme
 * {@code ResponseStatusException} est une {@code RuntimeException}, le conseil
 * l'attrapait et <b>tout refus devenait un 500</b>. Mesuré avant correction :
 *
 * <pre>
 * ResponseStatusException(UNAUTHORIZED) -&gt; 500
 * ResponseStatusException(NOT_FOUND)    -&gt; 500
 * </pre>
 *
 * <p>Conséquence directe : {@code AuthController} rend son refus de
 * rafraîchissement par {@code Mono.error(new ResponseStatusException(
 * UNAUTHORIZED, ...))}. Ce 401 — ajouté précisément pour qu'un jeton périmé ne
 * passe pas pour une panne — sortait en 500 pour l'appelant réel.
 *
 * <h2>Pourquoi aucun test ne l'avait vu</h2>
 *
 * {@code AuthControllerTest} lie le contrôleur par
 * {@code WebTestClient.bindToController(...)} <b>sans</b>
 * {@code .controllerAdvice(...)}. Il exerce donc un assemblage qui n'existe pas
 * en production : le contrôleur seul, sans le conseil qui, en vrai, réécrit sa
 * réponse. Son assertion {@code expectStatus().isUnauthorized()} passait, et la
 * plateforme rendait 500.
 *
 * <p>C'est la même leçon que le préfixe de liste noire, où le test posait
 * lui-même la valeur qu'il allait contrôler : un test qui n'exerce pas le
 * montage réel n'atteste que de ce qu'il a lui-même construit. D'où ce
 * fichier-ci, qui enregistre le conseil.
 *
 * <h2>Ce que ce test ne couvre pas</h2>
 *
 * Le contrôleur d'essai ci-dessous n'est pas un vrai contrôleur de ce module :
 * il n'y a donc aucune garantie sur les <b>chemins</b> ni sur la sécurité. Ce
 * fichier garde une seule chose, et c'est celle qui était cassée : la
 * correspondance entre le type d'exception levé et le statut HTTP rendu, en
 * présence du conseil.
 */
@DisplayName("GlobalExceptionHandler - le statut rendu en presence du conseil")
class GlobalExceptionHandlerTest {

    @RestController
    static class ControleurDEssai {

        @GetMapping("/essai/refus-401")
        Mono<String> refus401() {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "jeton de rafraichissement inconnu"));
        }

        @GetMapping("/essai/refus-401-autre-cause")
        Mono<String> refus401AutreCause() {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "compte desactive"));
        }

        @GetMapping("/essai/introuvable-par-statut")
        Mono<String> introuvableParStatut() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "absent"));
        }

        @GetMapping("/essai/introuvable-par-type")
        Mono<String> introuvableParType() {
            return Mono.error(new RessourceIntrouvableException(
                    "Projet 11111111-1111-1111-1111-111111111111 introuvable"));
        }

        @GetMapping("/essai/metier")
        Mono<String> metier() {
            return Mono.error(new BusinessException("Un projet avec ce nom existe deja",
                    "PROJECT_NAME_EXISTS"));
        }

        @GetMapping("/essai/quota")
        Mono<String> quota() {
            return Mono.error(new ProjectLimitExceededException(10));
        }

        @GetMapping("/essai/panne")
        Mono<String> panne() {
            return Mono.error(new IllegalStateException("connexion R2DBC perdue"));
        }
    }

    private final WebTestClient client = WebTestClient
            .bindToController(new ControleurDEssai())
            // LE POINT DE CE FICHIER. Sans cette ligne, le test mesure le
            // controleur seul et rate exactement le defaut corrige.
            .controllerAdvice(new GlobalExceptionHandler())
            .build();

    // ==================================================================
    @Nested
    @DisplayName("Un refus qui porte son statut le CONSERVE")
    class StatutConserve {

        /**
         * LE TEST QUI A TROUVÉ LE DÉFAUT.
         *
         * <p>Avant correction : 500. Un client recevant 500 conclut à une panne
         * passagère et réessaie — avec un jeton qui ne sera jamais accepté.
         */
        @Test
        @DisplayName("ResponseStatusException(401) rend 401, et non 500")
        void refusRend401() {
            client.get().uri("/essai/refus-401").exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(401);
        }

        @Test
        @DisplayName("ResponseStatusException(404) rend 404, et non 500")
        void introuvableRend404() {
            client.get().uri("/essai/introuvable-par-statut").exchange()
                    .expectStatus().isNotFound()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(404);
        }

        /**
         * Le motif ne franchit pas la frontière HTTP.
         *
         * <p>Deux causes différentes doivent donner la même réponse, corps
         * compris : distinguer « jeton inconnu » de « compte désactivé »
         * renseignerait l'appelant sur l'existence du jeton et l'état du compte.
         *
         * <p>Contrairement au cas documenté dans {@code AuthControllerTest} —
         * où {@code server.error.include-message} valant {@code never} rendait
         * l'assertion inopérante — ici le corps est construit par le conseil
         * lui-même. Rendre {@code ex.getReason()} le publierait donc vraiment,
         * et cette assertion le détecterait.
         */
        @Test
        @DisplayName("Deux causes differentes donnent le MEME corps")
        void motifNonDivulgue() {
            String premier = corps("/essai/refus-401");
            String second = corps("/essai/refus-401-autre-cause");

            org.assertj.core.api.Assertions.assertThat(premier)
                    .as("le motif du refus franchit la frontiere HTTP : deux causes "
                            + "distinctes sont distinguables par l'appelant")
                    .doesNotContain("inconnu")
                    .doesNotContain("desactive")
                    .isEqualTo(second);
        }

        /**
         * Le corps, horodatage retiré.
         *
         * <p>Ma première version comparait les corps bruts et échouait sur le
         * {@code timestamp}, qui diffère de quelques millisecondes entre les
         * deux appels. L'invariant porte sur ce que le corps <b>dit du refus</b>,
         * pas sur l'instant où il a été produit.
         */
        private String corps(String chemin) {
            byte[] brut = client.get().uri(chemin).exchange()
                    .expectBody().returnResult().getResponseBody();
            String texte = brut == null
                    ? "" : new String(brut, java.nio.charset.StandardCharsets.UTF_8);
            return texte.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une ressource introuvable rend 404")
    class Introuvable {

        /**
         * Les trois accès par identifiant de {@code ProjectServiceImpl} levaient
         * {@code new RuntimeException("Projet non trouvé")}, donc un 500.
         *
         * <p>Deux coûts : un client ne distinguait pas « ce projet n'existe
         * pas » d'une panne, et le trafic normal — un lien périmé, un projet
         * supprimé — alimentait le taux d'erreurs 5xx sur lequel reposent les
         * alertes.
         */
        @Test
        @DisplayName("RessourceIntrouvableException rend 404, et non 500")
        void typeDedieRend404() {
            client.get().uri("/essai/introuvable-par-type").exchange()
                    .expectStatus().isNotFound()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(404)
                    .jsonPath("$.errorCode").isEqualTo("RESSOURCE_INTROUVABLE");
        }

        /**
         * Le gestionnaire le plus SPÉCIFIQUE gagne.
         *
         * <p>{@code RessourceIntrouvableException} dérive de
         * {@code BusinessException}, qui rend 400. Si Spring choisissait le
         * premier gestionnaire applicable plutôt que le plus spécifique, ce
         * refus sortirait en 400 — et le test ci-dessus serait la seule chose
         * qui l'aurait signalé. Ce test nomme l'invariant pour qu'il ne repose
         * pas sur une coïncidence de déclaration.
         */
        @Test
        @DisplayName("l'heritage de BusinessException ne degrade pas le 404 en 400")
        void specificiteAvantHeritage() {
            client.get().uri("/essai/introuvable-par-type").exchange()
                    .expectStatus().isNotFound();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Les mappages deja en place, gardes")
    class MappagesExistants {

        @Test
        @DisplayName("BusinessException rend 400 avec son code metier")
        void metierRend400() {
            client.get().uri("/essai/metier").exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.errorCode").isEqualTo("PROJECT_NAME_EXISTS")
                    .jsonPath("$.message").isEqualTo("Un projet avec ce nom existe deja");
        }

        @Test
        @DisplayName("ProjectLimitExceededException rend 403, et non le 400 de son parent")
        void quotaRend403() {
            client.get().uri("/essai/quota").exchange()
                    .expectStatus().isForbidden()
                    .expectBody()
                    .jsonPath("$.errorCode").isEqualTo("PROJECT_LIMIT_EXCEEDED");
        }

        /**
         * Une panne reste une panne : 500, et le message interne n'est pas
         * publié.
         */
        @Test
        @DisplayName("une exception inattendue rend 500 SANS divulguer son message")
        void panneRend500() {
            client.get().uri("/essai/panne").exchange()
                    .expectStatus().is5xxServerError()
                    .expectBody()
                    .jsonPath("$.errorCode").isEqualTo("INTERNAL_ERROR")
                    .jsonPath("$.message").isEqualTo("Une erreur interne s'est produite");
        }
    }
}
