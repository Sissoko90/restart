package com.abdatytch.user_service.web;

import com.abdatytch.user_service.constants.WebConstants;
import com.abdatytch.user_service.dto.request.UserUpdateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.exception.FileStorageException;
import com.abdatytch.user_service.metrics.UserMetrics;
import com.abdatytch.user_service.service.FileStorageService;
import com.abdatytch.user_service.service.UserProfileUpdateService;
import com.abdatytch.user_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Surface <b>cliente</b> : le compte de l'appelant, et rien d'autre.
 *
 * <h2>Pourquoi il n'y a plus d'accès par identifiant</h2>
 *
 * Ce contrôleur portait aussi {@code GET /users}, {@code GET /users/search},
 * {@code GET /users/{id}}, {@code PUT /users/{id}} et
 * {@code DELETE /users/{id}} — cinq endpoints d'administration sur une surface
 * cliente, dont trois réservés aux administrateurs par une annotation. La
 * console d'administration avait donc <b>deux</b> chemins pour lister, chercher,
 * lire, modifier et supprimer un compte, l'un sous {@code /users} et l'autre
 * sous {@code /admin}. Ils ne se comportaient pas pareil.
 *
 * <p>{@code GET /users/{id}} avait aussi été la source d'un IDOR : l'annotation
 * {@code hasAnyRole('USER','ADMIN')} n'exige qu'un compte authentifié, et
 * n'importe qui lisait le profil complet d'un autre en faisant varier
 * l'identifiant du chemin. La vérification du propriétaire avait été ajoutée ;
 * l'endpoint supprimé fait mieux que la vérification, parce qu'il n'y a plus
 * rien à vérifier. Un client n'a aucun besoin légitime de lire un compte par
 * identifiant : il lit le sien par {@code /users/me}.
 *
 * <p>{@code AccessControlIdorTest} veille désormais à ce qu'aucun accès par
 * identifiant ne réapparaisse ici.
 *
 * <h2>Le contrôleur dépend de l'interface</h2>
 *
 * Il injectait {@code UserServiceReactiveImpl}, la classe concrète. Dépendre de
 * {@link UserService} n'est pas un ornement : l'implémentation porte des méthodes
 * publiques qui ne sont pas dans le contrat, et rien n'empêchait un contrôleur
 * de les appeler.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Mon compte",
        description = "Profil de l'utilisateur authentifié. "
                + "L'administration des comptes est sous /admin/users.")
@SecurityRequirement(name = "bearerAuth")
public class UserReactiveController {

    private final UserService userService;
    private final FileStorageService fileStorageService;
    private final UserProfileUpdateService profileUpdateService;
    private final UserMetrics userMetrics;

    /** Identifiant du compte authentifié, tel qu'il figure dans le jeton. */
    private static Mono<String> appelant() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getName);
    }

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mon profil",
            description = "Retourne le profil complet de l'utilisateur authentifié.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profil récupéré",
                    content = @Content(schema = @Schema(implementation = UserDetailDTO.class))),
            @ApiResponse(responseCode = "401", description = "Non authentifié"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<UserDetailDTO>> getCurrentUser() {
        return appelant()
                .flatMap(authUserId -> {
                    log.info(WebConstants.LOG_GET_CURRENT_USER, authUserId);
                    return userService.getUserByAuthUserId(authUserId);
                })
                .map(ResponseEntity::ok);
    }

    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Modifier mon profil",
            description = "Met à jour le profil de l'utilisateur authentifié. "
                    + "Tous les champs sont optionnels. Le nom, l'adresse de courriel "
                    + "et le numéro se modifient via auth-service.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profil mis à jour",
                    content = @Content(schema = @Schema(implementation = UserDetailDTO.class))),
            @ApiResponse(responseCode = "400", description = "Données invalides"),
            @ApiResponse(responseCode = "401", description = "Non authentifié"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<UserDetailDTO>> updateCurrentUser(
            @Parameter(description = "Champs à modifier", required = true)
            @Valid @RequestBody UserUpdateDTO dto) {
        return appelant()
                .flatMap(authUserId -> {
                    log.info(WebConstants.LOG_PUT_CURRENT_USER, authUserId);
                    return userService.getUserByAuthUserId(authUserId)
                            .flatMap(compte -> userService.updateUser(compte.id(), dto))
                            .doOnNext(modifie -> log.info(
                                    WebConstants.LOG_CURRENT_USER_UPDATED, modifie.id()));
                })
                .map(ResponseEntity::ok);
    }

    @GetMapping("/me/profile-picture")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Ma photo de profil",
            description = "Retourne l'URL de la photo de profil, ou une URL vide "
                    + "accompagnée d'un message si aucune n'est définie.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "URL récupérée"),
            @ApiResponse(responseCode = "401", description = "Non authentifié")
    })
    public Mono<ResponseEntity<Map<String, String>>> getProfilePicture() {
        return appelant()
                .flatMap(authUserId -> {
                    log.info(WebConstants.LOG_GET_PROFILE_PICTURE, authUserId);
                    return userService.getUserByAuthUserId(authUserId);
                })
                .map(compte -> {
                    String url = compte.profilePictureUrl();
                    if (url != null && !url.isEmpty()) {
                        return ResponseEntity.ok(Map.of(
                                WebConstants.KEY_PROFILE_PICTURE_URL, url));
                    }
                    return ResponseEntity.ok(Map.of(
                            WebConstants.KEY_PROFILE_PICTURE_URL, "",
                            WebConstants.KEY_MESSAGE, WebConstants.MSG_NO_PROFILE_PICTURE));
                });
    }

    /**
     * Retire ma photo de profil.
     *
     * <p>Ce verbe manquait : on pouvait envoyer une photo et jamais la retirer.
     * La seule façon de s'en débarrasser était d'en envoyer une autre — et
     * l'ancienne restait sur le disque.
     *
     * <p>204 même quand il n'y avait pas de photo : une suppression est
     * idempotente, et refuser « il n'y en avait pas » n'apprend rien à
     * l'appelant qui voulait justement qu'il n'y en ait plus.
     */
    @DeleteMapping("/me/profile-picture")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Retirer ma photo de profil",
            description = "Efface l'URL, supprime le fichier du disque et invalide le "
                    + "cache. Idempotent : 204 même sans photo existante.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Photo retirée"),
            @ApiResponse(responseCode = "401", description = "Non authentifié"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<Void>> deleteProfilePicture() {
        return appelant()
                .flatMap(authUserId -> {
                    log.info("DELETE /users/me/profile-picture pour {}", authUserId);
                    return userService.getUserByAuthUserId(authUserId)
                            .flatMap(compte ->
                                    profileUpdateService.supprimerPhotoDeProfil(compte.id()));
                })
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @PostMapping(value = "/me/profile-picture", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Envoyer ma photo de profil",
            description = "Enregistre une image et la rattache au profil de l'appelant.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Photo enregistrée"),
            @ApiResponse(responseCode = "400", description = "Fichier absent ou invalide"),
            @ApiResponse(responseCode = "401", description = "Non authentifié")
    })
    public Mono<ResponseEntity<Map<String, String>>> uploadProfilePicture(
            @RequestPart("file") Mono<FilePart> filePart) {
        return appelant()
                .flatMap(authUserId -> {
                    log.info(WebConstants.LOG_UPLOAD_PROFILE_PICTURE, authUserId);
                    return filePart
                            .flatMap(fichier -> enregistrer(fichier, authUserId))
                            // Sans ce repli, une requete multipart sans partie
                            // « file » ne produisait AUCUN element : la reponse
                            // etait un 200 au corps vide, et le client croyait
                            // l'envoi reussi.
                            .switchIfEmpty(Mono.fromSupplier(() -> {
                                userMetrics.envoiDePhotoEnEchec();
                                return ResponseEntity.badRequest().body(Map.of(
                                        WebConstants.KEY_ERROR, "Aucun fichier recu"));
                            }));
                });
    }

    private Mono<ResponseEntity<Map<String, String>>> enregistrer(
            FilePart fichier, String authUserId) {

        MediaType type = fichier.headers().getContentType();
        if (type == null || !"image".equals(type.getType())) {
            log.warn("Type de fichier refuse : {}", type);
            userMetrics.envoiDePhotoEnEchec();
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    WebConstants.KEY_ERROR, "Le fichier doit être une image")));
        }

        return fileStorageService.storeFile(fichier, authUserId)
                .flatMap(url -> userService.getUserByAuthUserId(authUserId)
                        .flatMap(compte -> profileUpdateService
                                .updateProfilePicture(compte.id(), url)
                                .thenReturn(ResponseEntity.ok(Map.of(
                                        WebConstants.KEY_MESSAGE,
                                        WebConstants.MSG_PROFILE_PICTURE_UPLOADED,
                                        WebConstants.KEY_PROFILE_PICTURE_URL, url)))))
                .doOnNext(reponse -> userMetrics.photoEnvoyee())
                .onErrorResume(FileStorageException.class, erreur -> {
                    // Refus metier : le message vient de nous, il est destine a
                    // l'appelant (type non autorise, taille depassee) et ne
                    // revele rien de l'infrastructure.
                    log.warn("Envoi de photo de profil refuse : {}", erreur.getMessage());
                    userMetrics.envoiDePhotoEnEchec();
                    return Mono.just(ResponseEntity.badRequest().body(Map.of(
                            WebConstants.KEY_ERROR, erreur.getMessage())));
                })
                .onErrorResume(erreur -> {
                    // Toute autre erreur : message GENERIQUE.
                    //
                    // La version precedente renvoyait erreur.getMessage() au
                    // client. Sur une erreur de stockage, ce message porte le
                    // chemin du systeme de fichiers, le nom du volume ou le
                    // detail du pilote — de quoi cartographier l'infrastructure
                    // depuis un simple envoi rate. Le detail reste dans les
                    // journaux, ou il sert.
                    log.error("Echec de l'envoi de photo de profil", erreur);
                    userMetrics.envoiDePhotoEnEchec();
                    return Mono.just(ResponseEntity.status(500).body(Map.of(
                            WebConstants.KEY_ERROR, "Erreur lors de l'envoi du fichier")));
                });
    }
}
