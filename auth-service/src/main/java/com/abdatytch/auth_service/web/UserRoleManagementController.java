package com.abdatytch.auth_service.web;

import com.abdatytch.auth_service.dto.request.CreateUserWithRolesRequest;
import com.abdatytch.auth_service.dto.response.UserWithRolesResponse;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.service.IUserRoleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Contrôleur pour la gestion des utilisateurs avec rôles multiples
 * Permet de créer des utilisateurs avec plusieurs rôles
 * Gère l'unicité : email/téléphone + rôle
 */
@RestController
@RequestMapping("/auth-users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Gestion des Utilisateurs et Rôles", description = "APIs pour gérer les utilisateurs avec rôles multiples")
public class UserRoleManagementController {
    
    private final IUserRoleManagementService userRoleManagementService;
    
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Créer un utilisateur avec plusieurs rôles",
        description = "Permet de créer un utilisateur et de lui affecter un ou plusieurs rôles. Le même email/téléphone peut être utilisé pour des rôles différents.",
        security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "201", description = "Utilisateur créé avec succès"),
        @ApiResponse(responseCode = "400", description = "Données invalides"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Non autorisé - Rôle ADMIN requis"),
        @ApiResponse(responseCode = "409", description = "Utilisateur existe déjà avec ce rôle")
    })
    public Mono<ResponseEntity<UserWithRolesResponse>> createUserWithRoles(
            @Valid @RequestBody CreateUserWithRolesRequest request) {
        log.info("Création d'un utilisateur avec rôles: {}", request.getEmail());
        return Mono.defer(() -> userRoleManagementService.createUserWithRoles(request))
            .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }
    
    @GetMapping("/{userId}")
    // `authentication.principal` et NON `authentication.principal.id`.
    //
    // JwtAuthenticationWebFilter construit le jeton d'authentification avec
    // l'identifiant comme principal : c'est une String. Lire `.id` dessus fait
    // echouer l'evaluation SpEL — « Property or field 'id' cannot be found on
    // java.lang.String ».
    //
    // Le `or` de SpEL evalue en court-circuit, ce qui masquait le defaut : un
    // ADMIN passait sur la premiere condition sans que la seconde soit jamais
    // evaluee. Seul un appelant NON administrateur declenchait l'erreur — donc
    // la clause censee lui permettre de lire ses propres roles ne lui donnait
    // pas l'acces, elle produisait une erreur.
    @PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal")
    @Operation(
        summary = "Récupérer un utilisateur avec ses rôles",
        description = "Récupère les informations d'un utilisateur avec tous ses rôles actifs",
        security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Utilisateur trouvé"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Non autorisé"),
        @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé")
    })
    public Mono<ResponseEntity<UserWithRolesResponse>> getUserWithRoles(
            @Parameter(description = "ID de l'utilisateur") @PathVariable String userId) {
        log.info("Récupération de l'utilisateur: {}", userId);
        return Mono.defer(() -> userRoleManagementService.getUserWithRoles(userId))
            .map(ResponseEntity::ok);
    }
    
    @PostMapping("/{userId}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Ajouter un rôle à un utilisateur",
        description = "Ajoute un nouveau rôle à un utilisateur existant",
        security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Rôle ajouté avec succès"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Non autorisé - Rôle ADMIN requis"),
        @ApiResponse(responseCode = "404", description = "Utilisateur non trouvé"),
        @ApiResponse(responseCode = "409", description = "L'utilisateur possède déjà ce rôle")
    })
    public Mono<ResponseEntity<UserWithRolesResponse>> addRoleToUser(
            @Parameter(description = "ID de l'utilisateur") @PathVariable String userId,
            @Parameter(description = "Rôle à ajouter") @PathVariable UserRole role) {
        log.info("Ajout du rôle {} à l'utilisateur {}", role, userId);
        return Mono.defer(() -> userRoleManagementService.addRoleToUser(userId, role))
            .map(ResponseEntity::ok);
    }
    
    @DeleteMapping("/{userId}/roles/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Retirer un rôle d'un utilisateur",
        description = "Retire un rôle d'un utilisateur. Le rôle principal ne peut pas être retiré s'il est le seul rôle.",
        security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Rôle retiré avec succès"),
        @ApiResponse(responseCode = "400", description = "Impossible de retirer le dernier rôle ou le rôle principal"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Non autorisé - Rôle ADMIN requis"),
        @ApiResponse(responseCode = "404", description = "Utilisateur ou rôle non trouvé")
    })
    public Mono<ResponseEntity<UserWithRolesResponse>> removeRoleFromUser(
            @Parameter(description = "ID de l'utilisateur") @PathVariable String userId,
            @Parameter(description = "Rôle à retirer") @PathVariable UserRole role) {
        log.info("Suppression du rôle {} de l'utilisateur {}", role, userId);
        return Mono.defer(() -> userRoleManagementService.removeRoleFromUser(userId, role))
            .map(ResponseEntity::ok);
    }
    
    @PutMapping("/{userId}/primary-role/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Changer le rôle principal d'un utilisateur",
        description = "Définit un nouveau rôle principal pour l'utilisateur. Le rôle doit déjà être affecté à l'utilisateur.",
        security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Rôle principal changé avec succès"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Non autorisé - Rôle ADMIN requis"),
        @ApiResponse(responseCode = "404", description = "Utilisateur ou rôle non trouvé")
    })
    public Mono<ResponseEntity<UserWithRolesResponse>> changePrimaryRole(
            @Parameter(description = "ID de l'utilisateur") @PathVariable String userId,
            @Parameter(description = "Nouveau rôle principal") @PathVariable UserRole role) {
        log.info("Changement du rôle principal de l'utilisateur {} vers {}", userId, role);
        return Mono.defer(() -> userRoleManagementService.changePrimaryRole(userId, role))
            .map(ResponseEntity::ok);
    }
    
    @GetMapping("/by-role/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Récupérer tous les utilisateurs ayant un rôle spécifique",
        description = "Liste tous les utilisateurs qui possèdent un rôle donné",
        security = @SecurityRequirement(name = "Bearer Authentication")
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Liste des utilisateurs récupérée"),
        @ApiResponse(responseCode = "401", description = "Non authentifié"),
        @ApiResponse(responseCode = "403", description = "Non autorisé - Rôle ADMIN requis")
    })
    public Mono<ResponseEntity<List<UserWithRolesResponse>>> getUsersByRole(
            @Parameter(description = "Rôle à rechercher") @PathVariable UserRole role) {
        log.info("Récupération des utilisateurs avec le rôle {}", role);
        return userRoleManagementService.getUsersByRole(role)
            .collectList()
            .map(ResponseEntity::ok);
    }
}
