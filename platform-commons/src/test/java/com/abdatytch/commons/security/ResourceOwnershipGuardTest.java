package com.abdatytch.commons.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde d'appartenance, partage par les services.
 *
 * <p>Le schema qu'il empeche a ete trouve QUATRE fois dans ce depot : profil et
 * fichiers dans user-service, puis quotas-service et setting-service. Chaque fois
 * la meme cause — un role confondu avec une autorisation — et chaque fois une
 * correction locale. Ces tests verrouillent la regle a un seul endroit.
 */
@DisplayName("ResourceOwnershipGuard - agir sur ses propres donnees")
class ResourceOwnershipGuardTest {

    private static final String PORTEUR = "utilisateur-1";
    private static final String AUTRUI = "utilisateur-2";

    private static final RuntimeException REFUS = new RuntimeException("introuvable");

    private static Authentication authentification(String sujet, String... roles) {
        return new UsernamePasswordAuthenticationToken(sujet, "n/a",
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    private static <T> Mono<T> avec(Authentication authentication, Mono<T> chaine) {
        return chaine.contextWrite(
                ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    // ==================================================================
    @Nested
    @DisplayName("Acces a ses propres donnees")
    class SesPropresDonnees {

        @Test
        @DisplayName("le porteur accede a ses donnees")
        void porteurAutorise() {
            StepVerifier.create(avec(authentification(PORTEUR, "ROLE_USER"),
                            ResourceOwnershipGuard.guard(PORTEUR, REFUS, Mono.just("donnees"))))
                    .expectNext("donnees")
                    .verifyComplete();
        }

        @Test
        @DisplayName("un role USER ne suffit PAS pour acceder aux donnees d'autrui")
        void roleNEstPasUneAutorisation() {
            // C'est le coeur du probleme : `hasRole('USER')` sur un endpoint a
            // identifiant de chemin ne protege rien.
            StepVerifier.create(avec(authentification(PORTEUR, "ROLE_USER"),
                            ResourceOwnershipGuard.guard(AUTRUI, REFUS, Mono.just("donnees"))))
                    .verifyErrorMessage("introuvable");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Roles autorises a agir pour autrui")
    class RolesPrivilegies {

        @ParameterizedTest(name = "{0} peut agir pour autrui")
        @ValueSource(strings = {"ROLE_ADMIN", "ROLE_SUPER_ADMIN", "ROLE_SERVICE"})
        void rolesAutorises(String role) {
            StepVerifier.create(avec(authentification(PORTEUR, role),
                            ResourceOwnershipGuard.guard(AUTRUI, REFUS, Mono.just("donnees"))))
                    .expectNext("donnees")
                    .verifyComplete();
        }

        @ParameterizedTest(name = "{0} ne peut PAS agir pour autrui")
        @ValueSource(strings = {"ROLE_USER", "ROLE_DEVELOPER", "ROLE_MERCHANT", "ROLE_DRIVER"})
        void rolesRefuses(String role) {
            // DEVELOPER est un droit de consommation d'API, pas d'administration :
            // le confondre serait la faute la plus couteuse de ce modele.
            StepVerifier.create(avec(authentification(PORTEUR, role),
                            ResourceOwnershipGuard.guard(AUTRUI, REFUS, Mono.just("donnees"))))
                    .verifyErrorMessage("introuvable");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Absence d'authentification")
    class SansAuthentification {

        @Test
        @DisplayName("sans contexte de securite, l'acces est refuse")
        void refusSansContexte() {
            // Refuser plutot que laisser passer faute d'information : c'est la
            // seule position defendable quand on ne sait pas qui appelle.
            StepVerifier.create(ResourceOwnershipGuard.guard(PORTEUR, REFUS, Mono.just("x")))
                    .verifyErrorMessage("introuvable");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La verification precede l'acces aux donnees")
    class OrdreDExecution {

        @Test
        @DisplayName("la suite n'est PAS executee quand l'acces est refuse")
        void suiteNonExecuteeSiRefus() {
            // Sans `Mono.defer`, la suite serait evaluee a l'assemblage : la
            // requete partirait en base alors que l'acces est refuse. Rien ne
            // serait renvoye a l'appelant, mais la charge serait bien reelle —
            // et un compteur, un journal ou un effet de bord s'appliquerait.
            AtomicBoolean executee = new AtomicBoolean(false);
            Mono<String> suite = Mono.fromCallable(() -> {
                executee.set(true);
                return "donnees";
            });

            StepVerifier.create(avec(authentification(PORTEUR, "ROLE_USER"),
                            ResourceOwnershipGuard.guard(AUTRUI, REFUS, suite)))
                    .verifyError();

            assertThat(executee)
                    .withFailMessage("la suite a ete executee malgre le refus : la "
                            + "verification ne protege pas l'acces aux donnees")
                    .isFalse();
        }

        @Test
        @DisplayName("la variante Flux protege aussi")
        void variantFlux() {
            AtomicBoolean executee = new AtomicBoolean(false);
            Flux<String> suite = Flux.defer(() -> {
                executee.set(true);
                return Flux.just("a", "b");
            });

            StepVerifier.create(avec(authentification(PORTEUR, "ROLE_USER"),
                            ResourceOwnershipGuard.guardMany(AUTRUI, REFUS, suite).collectList()))
                    .verifyError();

            assertThat(executee).isFalse();
        }

        @Test
        @DisplayName("la suite EST executee quand l'acces est legitime")
        void suiteExecuteeSiAutorise() {
            StepVerifier.create(avec(authentification(PORTEUR, "ROLE_USER"),
                            ResourceOwnershipGuard.guardMany(PORTEUR, REFUS,
                                    Flux.just("a", "b")).collectList()))
                    .assertNext(liste -> assertThat(liste).containsExactly("a", "b"))
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Cas limites")
    class CasLimites {

        @Test
        @DisplayName("un identifiant cible nul est refuse")
        void identifiantNul() {
            StepVerifier.create(avec(authentification(PORTEUR, "ROLE_USER"),
                            ResourceOwnershipGuard.guard(null, REFUS, Mono.just("x"))))
                    .verifyErrorMessage("introuvable");
        }

        @Test
        @DisplayName("un porteur sans aucun role accede a ses propres donnees")
        void aucunRole() {
            // L'appartenance suffit : elle ne depend d'aucun role.
            StepVerifier.create(avec(new UsernamePasswordAuthenticationToken(PORTEUR, "n/a", List.of()),
                            ResourceOwnershipGuard.guard(PORTEUR, REFUS, Mono.just("donnees"))))
                    .expectNext("donnees")
                    .verifyComplete();
        }
    }
}
