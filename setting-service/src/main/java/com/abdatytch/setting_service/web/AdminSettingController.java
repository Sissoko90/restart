package com.abdatytch.setting_service.web;

import com.abdatytch.setting_service.constant.SettingsConstants;
import com.abdatytch.setting_service.dto.request.UpdateSettingRequest;
import com.abdatytch.setting_service.dto.response.SettingResponse;
import com.abdatytch.setting_service.service.ISettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Écriture des paramètres système. Réservé au rôle ADMIN.
 *
 * <p>Chaque modification est tracée avec son auteur : ces paramètres pilotent le
 * comportement de toute la plateforme (mode maintenance, ouverture des inscriptions,
 * politique de mot de passe).
 */
@RestController
@RequestMapping(SettingsConstants.ADMIN_BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Settings Admin", description = "Modification des paramètres système")
public class AdminSettingController {

    private final ISettingService settingService;

    @PutMapping("/system/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Modifier un paramètre système",
            description = "La valeur est validée contre le type déclaré. Clé hors catalogue : 404.")
    public Mono<SettingResponse> updateSystemSetting(
            @PathVariable String key,
            @Valid @RequestBody UpdateSettingRequest request,
            @AuthenticationPrincipal String adminId) {
        return settingService.updateSystemSetting(key, request.getValue(),
                adminId != null ? adminId : "unknown");
    }

    @DeleteMapping("/system/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Réinitialiser un paramètre système",
            description = "Supprime la surcharge : le paramètre reprend sa valeur de configuration")
    public Mono<SettingResponse> resetSystemSetting(@PathVariable String key) {
        return settingService.resetSystemSetting(key);
    }

    @DeleteMapping("/users/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Supprimer toutes les préférences d'un utilisateur",
            description = "Utilisé lors de la suppression d'un compte")
    public Mono<ResponseEntity<Void>> deleteAllUserSettings(@PathVariable String userId) {
        return settingService.deleteAllUserSettings(userId)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }
}
