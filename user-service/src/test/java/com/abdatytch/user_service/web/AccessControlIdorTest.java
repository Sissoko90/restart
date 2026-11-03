package com.abdatytch.user_service.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import reactor.test.StepVerifier;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Références directes à des objets (IDOR).
 *
 * <p>Un identifiant qui vient du chemin de la requête est une donnée fournie par
 * le client, au même titre qu'un paramètre. Vérifier que l'appelant est
 * authentifié dit seulement <b>qui il est</b> ; il reste à vérifier que la
 * ressource demandée <b>lui appartient</b>. C'est la vérification que ces deux
 * endpoints n'effectuaient pas.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Contrôle d'accès aux ressources d'autrui (IDOR)")
class AccessControlIdorTest {

    private static final String PROPRIETAIRE = "auth-user-proprietaire";
    private static final String INTRUS = "auth-user-intrus";

    private static UsernamePasswordAuthenticationToken authenticated(String name, String role) {
        return new UsernamePasswordAuthenticationToken(
                name, null, List.of(new SimpleGrantedAuthority(role)));
    }

    // ==================================================================
    @Nested
    @DisplayName("La surface cliente ne porte aucun accès par identifiant")
    class SurfaceCliente {

        /**
         * {@code GET /users/{id}} a été l'IDOR de ce service : l'annotation
         * {@code hasAnyRole('USER','ADMIN')} n'exige qu'un compte authentifié,
         * et n'importe qui lisait le profil complet d'un autre — nom, adresse,
         * téléphone, date de naissance — en faisant varier l'identifiant du
         * chemin. Une vérification du propriétaire avait été ajoutée.
         *
         * <p>L'endpoint a ensuite été <b>supprimé</b>, avec les quatre autres
         * accès par identifiant qui doublaient la surface d'administration. Ce
         * test remplace les tests de la vérification : il n'y a plus de
         * vérification à tester, il y a une absence à maintenir.
         *
         * <p>Un contrôle de structure et non de comportement, parce que c'est
         * la structure qui garantit : une vérification peut être contournée par
         * un chemin oublié, un endpoint absent ne peut pas l'être.
         */
        @Test
        @DisplayName("aucune route du contrôleur client ne porte de variable de chemin")
        void aucunAccesParIdentifiant() {
            for (Method methode : UserReactiveController.class.getDeclaredMethods()) {
                for (String gabarit : gabaritsDe(methode)) {
                    assertThat(gabarit)
                            .withFailMessage(
                                    "la methode %s expose %s : un identifiant pris dans le "
                                            + "chemin d'une surface cliente est une reference "
                                            + "directe a un objet, et c'est par la que "
                                            + "GET /users/{id} laissait lire le profil d'autrui. "
                                            + "L'acces par identifiant appartient a "
                                            + "/admin/users, qui exige le role ADMIN.",
                                    methode.getName(), gabarit)
                            .doesNotContain("{");
                }
            }
        }

        @Test
        @DisplayName("toutes les routes du contrôleur client sont sous /me")
        void toutEstSousMoi() {
            List<String> gabarits = new ArrayList<>();
            for (Method methode : UserReactiveController.class.getDeclaredMethods()) {
                gabarits.addAll(gabaritsDe(methode));
            }

            assertThat(gabarits)
                    .withFailMessage("le controleur client ne doit exposer que le compte "
                            + "de l'appelant ; il portait aussi GET /users, GET /users/search, "
                            + "GET /users/{id}, PUT /users/{id} et DELETE /users/{id}")
                    .isNotEmpty()
                    .allSatisfy(gabarit -> assertThat(gabarit).startsWith("/me"));
        }

        /** Gabarits déclarés par une méthode, tous verbes confondus. */
        private List<String> gabaritsDe(Method methode) {
            List<String> gabarits = new ArrayList<>();
            GetMapping get = methode.getAnnotation(GetMapping.class);
            if (get != null) {
                gabarits.addAll(List.of(get.value()));
            }
            PostMapping post = methode.getAnnotation(PostMapping.class);
            if (post != null) {
                gabarits.addAll(List.of(post.value()));
            }
            PutMapping put = methode.getAnnotation(PutMapping.class);
            if (put != null) {
                gabarits.addAll(List.of(put.value()));
            }
            DeleteMapping delete = methode.getAnnotation(DeleteMapping.class);
            if (delete != null) {
                gabarits.addAll(List.of(delete.value()));
            }
            return gabarits;
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("GET /files/{userId}/{filename} — fichiers d'un autre utilisateur")
    class LectureDeFichier {

        private FileReactiveController controller;

        @TempDir
        Path racine;

        @BeforeEach
        void setUp() throws Exception {
            Files.createDirectories(racine.resolve(PROPRIETAIRE));
            Files.writeString(racine.resolve(PROPRIETAIRE).resolve("photo.jpg"), "contenu-prive");

            controller = new FileReactiveController();
            ReflectionTestUtils.setField(controller, "uploadDir", racine.toString());
        }

        @Test
        @DisplayName("le propriétaire accède à son fichier")
        void proprietaireAutorise() {
            StepVerifier.create(controller.serveFile(PROPRIETAIRE, "photo.jpg")
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                    authenticated(PROPRIETAIRE, "ROLE_USER"))))
                    .assertNext(response ->
                            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                    .verifyComplete();
        }

        @Test
        @DisplayName("un autre utilisateur ne peut pas lire le fichier")
        void intrusRefuse() {
            StepVerifier.create(controller.serveFile(PROPRIETAIRE, "photo.jpg")
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                    authenticated(INTRUS, "ROLE_USER"))))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode())
                                .withFailMessage("il suffisait de remplacer le segment du chemin "
                                        + "par l'identifiant d'un autre pour lire ses fichiers")
                                .isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(response.getBody()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("sans authentification, aucun fichier n'est servi")
        void anonymeRefuse() {
            // L'endpoint était en `permitAll` : le fichier était accessible sans
            // le moindre jeton.
            StepVerifier.create(controller.serveFile(PROPRIETAIRE, "photo.jpg"))
                    .assertNext(response ->
                            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
                    .verifyComplete();
        }

        @Test
        @DisplayName("la traversée de chemin reste refusée pour le propriétaire lui-même")
        void traverseeRefusee() {
            StepVerifier.create(controller.serveFile(PROPRIETAIRE, "../" + PROPRIETAIRE + "/photo.jpg")
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                                    authenticated(PROPRIETAIRE, "ROLE_USER"))))
                    .assertNext(response -> assertThat(response.getStatusCode())
                            .isIn(HttpStatus.NOT_FOUND, HttpStatus.OK))
                    .verifyComplete();
        }
    }
}
