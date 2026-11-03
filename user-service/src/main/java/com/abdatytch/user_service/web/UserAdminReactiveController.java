package com.abdatytch.user_service.web;

import com.abdatytch.user_service.constants.AdminConstants;
import com.abdatytch.user_service.constants.WebConstants;
import com.abdatytch.user_service.dto.request.AdminActionDTO;
import com.abdatytch.user_service.dto.request.AdminUserUpdateDTO;
import com.abdatytch.user_service.dto.response.AuditLogDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.enums.ActionType;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.service.AuditLogService;
import com.abdatytch.user_service.service.UserAdminService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * Surface d'<b>administration</b> des comptes — préfixe unique {@code /admin/users}.
 *
 * <h2>Ce qui a été réuni ici</h2>
 *
 * Trois contrôleurs se partageaient l'administration des comptes :
 * {@code /admin/users} (décisions), {@code /admin/management/users} (liste,
 * recherche, consultation, modification, suppression) et
 * {@code /users} lui-même, qui portait cinq endpoints d'administration sur la
 * surface cliente. Lister, chercher, lire, modifier et supprimer un compte
 * existaient donc chacun <b>deux fois</b>, et les deux versions divergeaient :
 * l'une incluait les comptes révoqués, l'autre non ; l'une faisait un effacement
 * logique, l'autre supprimait la ligne.
 *
 * <p>{@code GET /admin/management/users/{userId}} méritait une mention : son
 * corps entier était {@code Mono.error(new UnsupportedOperationException("Use
 * GET /users/{id} instead"))}. Un endpoint publié, routé, documenté, dont la
 * seule fonction était de renvoyer vers un autre.
 *
 * <h2>Les réversions</h2>
 *
 * {@code unblock}, {@code unban} et {@code unsuspend} n'existent plus :
 * {@code restore} les remplace. Ils ramenaient tous les trois le compte à
 * ACTIVE, en n'effaçant chacun que ses propres traces — voir
 * {@link UserAdminService}.
 *
 * <h2>Le rôle n'est plus vérifié à la main</h2>
 *
 * Chacun des deux anciens contrôleurs portait sa copie d'un
 * {@code checkAdminRole()} qui lisait le contexte de sécurité et levait une
 * exception. C'était la <b>troisième</b> vérification du même fait : le Gateway
 * exige déjà un jeton d'administrateur, et la chaîne de filtres de ce service
 * réserve {@code /admin/**} au rôle ADMIN.
 *
 * <p>{@code @PreAuthorize} au niveau de la classe reste, et ce n'est pas un
 * ornement : il tient si le gabarit de chemin de {@code SecurityConfig} change,
 * et il déclare l'exigence à l'endroit où on la cherche. Mais c'est une
 * annotation, pas trente lignes recopiées.
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Administration des comptes",
        description = "Réservé aux administrateurs. Décisions, listes et modifications.")
@SecurityRequirement(name = "bearerAuth")
public class UserAdminReactiveController {

    private final UserService userService;
    private final UserAdminService adminService;
    private final AuditLogService auditLogService;

    // ==================================================================
    // Consultation
    // ==================================================================

    @GetMapping
    @Operation(summary = "Lister les comptes",
            description = "Comptes non révoqués, du plus récent au plus ancien.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste récupérée"),
            @ApiResponse(responseCode = "401", description = "Non authentifié"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis")
    })
    public ResponseEntity<Flux<UserDetailDTO>> getAllUsers(
            @Parameter(description = "Numéro de page, à partir de 0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Taille de page")
            @RequestParam(defaultValue = "20") int size) {
        log.info(AdminConstants.LOG_GET_ALL_USERS, page, size);
        return ResponseEntity.ok(userService.getAllUsers(size, page * size));
    }

    @GetMapping("/search")
    @Operation(summary = "Rechercher des comptes",
            description = "Cherche dans l'identifiant d'authentification et dans le "
                    + "profil déclaratif (ville, pays, adresse). Le nom, le courriel "
                    + "et le numéro vivent dans auth-service et ne sont pas cherchés ici.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Résultats de recherche"),
            @ApiResponse(responseCode = "401", description = "Non authentifié"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis")
    })
    public ResponseEntity<Flux<UserDetailDTO>> searchUsers(
            @Parameter(description = "Terme recherché", required = true)
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info(AdminConstants.LOG_SEARCH_USERS, q);
        return ResponseEntity.ok(userService.searchUsers(q, size, page * size));
    }

    /**
     * Liste par statut.
     *
     * <p>Déclaré avant {@code /{id}} dans ce fichier par convention de lecture ;
     * l'ordre réel vient du comparateur de gabarits de Spring, qui préfère un
     * segment littéral à une variable.
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "Lister les comptes par statut",
            description = "ACTIVE, BLOCKED, SUSPENDED, BANNED ou DELETED.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Liste récupérée"),
            @ApiResponse(responseCode = "400", description = "Statut inconnu"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis")
    })
    public ResponseEntity<Flux<UserDetailDTO>> getUsersByStatus(
            @PathVariable String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info(AdminConstants.LOG_GET_USERS_BY_STATUS, status);

        UserStatus statut = statutOuNull(status);
        if (statut == null) {
            // `valueOf` levait une IllegalArgumentException, qui remontait en
            // 500 : une faute de frappe dans l'URL passait pour une panne du
            // service.
            log.warn(AdminConstants.ERROR_STATUT_INCONNU + "{}", status);
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(userService.getUsersByStatus(statut, size, page * size));
    }

    private static UserStatus statutOuNull(String valeur) {
        try {
            return UserStatus.valueOf(valeur.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException inconnu) {
            return null;
        }
    }

    /**
     * Journal des décisions, filtrable.
     *
     * <p>Déclaré avant {@code /{userId}} : le segment est littéral, donc le
     * comparateur de gabarits de Spring le préfère de toute façon.
     *
     * <p>Ce journal était <b>écrit et illisible</b> : aucun endpoint ne
     * l'exposait, et les quatre méthodes de lecture du service n'étaient
     * appelées par personne. Il accumulait la réponse à « qui a décidé quoi »
     * sans que quiconque puisse poser la question.
     */
    @GetMapping("/audit-logs")
    @Operation(summary = "Journal des décisions d'administration",
            description = "Du plus récent au plus ancien. Filtrable par administrateur "
                    + "ou par nature de décision ; les deux filtres ne se combinent pas, "
                    + "l'administrateur est pris en premier.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Journal récupéré"),
            @ApiResponse(responseCode = "400", description = "Nature de décision inconnue"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis")
    })
    public ResponseEntity<Flux<AuditLogDTO>> getAuditLogs(
            @Parameter(description = "Restreint aux décisions de cet administrateur "
                    + "(son authUserId)")
            @RequestParam(required = false) String adminId,
            @Parameter(description = "Restreint à cette nature de décision")
            @RequestParam(required = false) String actionType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info(AdminConstants.LOG_GET_AUDIT_LOGS, adminId, actionType);
        int decalage = page * size;

        if (adminId != null && !adminId.isBlank()) {
            return ResponseEntity.ok(
                    auditLogService.journalDunAdministrateur(adminId, size, decalage));
        }

        ActionType nature = null;
        if (actionType != null && !actionType.isBlank()) {
            nature = natureOuNull(actionType);
            if (nature == null) {
                log.warn(AdminConstants.ERROR_ACTION_INCONNUE + "{}", actionType);
                return ResponseEntity.badRequest().build();
            }
        }
        return ResponseEntity.ok(auditLogService.journalComplet(nature, size, decalage));
    }

    private static ActionType natureOuNull(String valeur) {
        try {
            return ActionType.valueOf(valeur.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException inconnue) {
            return null;
        }
    }

    @GetMapping("/{userId}/audit-logs")
    @Operation(summary = "Journal des décisions prises sur un compte",
            description = "« Qu'a-t-on décidé sur ce compte, et qui » — y compris pour un "
                    + "compte révoqué, dont la ligne de journal survit.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Journal récupéré"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis")
    })
    public ResponseEntity<Flux<AuditLogDTO>> getAuditLogsOfUser(
            @PathVariable UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info(AdminConstants.LOG_GET_AUDIT_LOGS_OF_USER, userId);
        return ResponseEntity.ok(
                auditLogService.journalDuCompte(userId, size, page * size));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Consulter un compte",
            description = "Profil complet, y compris les comptes révoqués.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compte trouvé",
                    content = @Content(schema = @Schema(implementation = UserDetailDTO.class))),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<UserDetailDTO>> getUserById(@PathVariable UUID userId) {
        log.info(AdminConstants.LOG_GET_USER_BY_ID, userId);
        return userService.getUserById(userId).map(ResponseEntity::ok);
    }

    // ==================================================================
    // Modification
    // ==================================================================

    @PutMapping("/{userId}")
    @Operation(summary = "Modifier un compte",
            description = "Ne modifie que ce que ce service détient : date de naissance "
                    + "et profil déclaratif. Le nom, le courriel, le numéro et le rôle "
                    + "se modifient via auth-service.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compte modifié"),
            @ApiResponse(responseCode = "400", description = "Données invalides"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<UserDetailDTO>> updateUser(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminUserUpdateDTO dto) {
        log.info(AdminConstants.LOG_UPDATE_USER, userId);
        return adminService.updateUserByAdmin(userId, dto).map(ResponseEntity::ok);
    }

    /**
     * Ferme le compte : révocation locale, propagation à auth-service, audit.
     *
     * <p>C'est ce qui le distingue de {@code POST /{id}/revoke}, qui ne vaut que
     * dans ce service. Dans les deux cas la <b>ligne est conservée</b> : la
     * version précédente de cet endpoint faisait un {@code DELETE} de la ligne,
     * puis écrivait un journal d'audit qui pointait vers un compte dont il ne
     * restait rien.
     */
    @DeleteMapping("/{userId}")
    @Operation(summary = "Fermer un compte",
            description = "Révocation dans ce service ET dans auth-service, consignée "
                    + "au journal d'audit. La ligne est conservée : effacer n'est pas "
                    + "supprimer la ligne.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Compte fermé"),
            @ApiResponse(responseCode = "403", description = "Rôle ADMIN requis"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<Void>> closeAccount(@PathVariable UUID userId) {
        log.warn(AdminConstants.LOG_DELETE_USER, userId);
        return adminService.closeAccount(userId)
                .thenReturn(ResponseEntity.noContent().build());
    }

    // ==================================================================
    // Les cinq décisions
    // ==================================================================

    @PostMapping("/{userId}/block")
    @Operation(summary = "Bloquer un compte",
            description = "Retrait d'accès réversible, sans échéance. "
                    + "Se lève par /restore.")
    public Mono<ResponseEntity<UserDetailDTO>> blockUser(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminActionDTO action) {
        log.warn(WebConstants.LOG_ADMIN_BLOCK_USER, userId, action.reason());
        return adminService.blockUser(userId, action).map(ResponseEntity::ok);
    }

    @PostMapping("/{userId}/ban")
    @Operation(summary = "Bannir un compte",
            description = "Retrait d'accès présenté comme définitif. Distinct d'un "
                    + "blocage, qui est temporaire.")
    public Mono<ResponseEntity<UserDetailDTO>> banUser(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminActionDTO action) {
        log.error(WebConstants.LOG_ADMIN_BAN_USER, userId, action.reason());
        return adminService.banUser(userId, action).map(ResponseEntity::ok);
    }

    @PostMapping("/{userId}/suspend")
    @Operation(summary = "Suspendre un compte",
            description = "Retrait d'accès avec échéance. Le champ 'until' est exigé : "
                    + "c'est lui qui distingue une suspension d'un blocage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Compte suspendu"),
            @ApiResponse(responseCode = "400", description = "Échéance absente"),
            @ApiResponse(responseCode = "404", description = "Compte introuvable")
    })
    public Mono<ResponseEntity<UserDetailDTO>> suspendUser(
            @PathVariable UUID userId,
            @Valid @RequestBody AdminActionDTO action) {
        log.warn(WebConstants.LOG_ADMIN_SUSPEND_USER, userId, action.until());
        return adminService.suspendUser(userId, action).map(ResponseEntity::ok);
    }

    @PostMapping("/{userId}/revoke")
    @Operation(summary = "Révoquer un compte",
            description = "Effacement logique dans ce service seulement. La ligne est "
                    + "conservée. Pour fermer aussi le compte d'authentification, "
                    + "utiliser DELETE /admin/users/{userId}.")
    public Mono<ResponseEntity<UserDetailDTO>> revokeUser(@PathVariable UUID userId) {
        log.error(WebConstants.LOG_ADMIN_REVOKE_USER, userId);
        return adminService.revokeUser(userId).map(ResponseEntity::ok);
    }

    @PostMapping("/{userId}/restore")
    @Operation(summary = "Rétablir un compte",
            description = "Retour à ACTIVE depuis un blocage, un bannissement, une "
                    + "suspension ou une révocation. Remplace unblock, unban et "
                    + "unsuspend, qui n'effaçaient chacun qu'une partie de l'état.")
    public Mono<ResponseEntity<UserDetailDTO>> restoreUser(@PathVariable UUID userId) {
        log.warn(WebConstants.LOG_ADMIN_RESTORE_USER, userId);
        return adminService.restoreUser(userId).map(ResponseEntity::ok);
    }
}
