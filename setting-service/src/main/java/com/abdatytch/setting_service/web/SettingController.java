package com.abdatytch.setting_service.web;

import com.abdatytch.commons.security.ResourceOwnershipGuard;
import com.abdatytch.setting_service.exception.SettingNotFoundException;
import com.abdatytch.setting_service.constant.SettingsConstants;
import com.abdatytch.setting_service.dto.request.UpdateSettingRequest;
import com.abdatytch.setting_service.dto.response.SettingResponse;
import com.abdatytch.setting_service.dto.response.UserSettingResponse;
import com.abdatytch.setting_service.enums.SettingCategory;
import com.abdatytch.setting_service.service.ISettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Lecture des paramètres système et gestion des préférences utilisateur.
 *
 * <p>Exposé sous {@code /settings/**} ; le Gateway y route {@code /api/v1/settings/**}.
 * L'écriture des paramètres <b>système</b> est réservée aux administrateurs et vit
 * dans {@link AdminSettingController}.
 */
@RestController
@RequestMapping(SettingsConstants.BASE_PATH)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Settings", description = "Paramètres système (lecture) et préférences utilisateur")
public class SettingController {

    private final ISettingService settingService;

    @GetMapping("/system")
    @Operation(summary = "Tous les paramètres système",
            description = "Valeur courante, valeur par défaut et indicateur de surcharge")
    public Flux<SettingResponse> getSystemSettings(
            @RequestParam(required = false) SettingCategory category) {
        return category != null
                ? settingService.getSystemSettingsByCategory(category)
                : settingService.getAllSystemSettings();
    }

    @GetMapping("/system/{key}")
    @Operation(summary = "Un paramètre système")
    public Mono<SettingResponse> getSystemSetting(@PathVariable String key) {
        return settingService.getSystemSetting(key);
    }

    /**
     * Refus indistinguable d'une ressource absente.
     *
     * <p>Un 403 confirmerait l'existence du porteur : sur une suite
     * d'identifiants, cela permet d'enumerer les comptes sans lire une seule
     * preference.
     */
    private Throwable refus(String userId) {
        return new SettingNotFoundException("Aucune preference pour " + userId);
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Préférences d'un utilisateur")
    public Flux<UserSettingResponse> getUserSettings(@PathVariable String userId) {
        // L'identifiant vient du chemin. Sans verification, tout porteur d'un
        // jeton valide lisait les preferences de n'importe qui — et leur valeur
        // est une chaine libre, qui peut contenir n'importe quelle donnee
        // personnelle (voir docs/COMPLIANCE.md).
        return ResourceOwnershipGuard.guardMany(userId, refus(userId),
                settingService.getUserSettings(userId));
    }

    @GetMapping("/users/{userId}/{key}")
    @Operation(summary = "Une préférence utilisateur",
            description = "404 si l'utilisateur n'a pas défini cette préférence")
    public Mono<ResponseEntity<UserSettingResponse>> getUserSetting(
            @PathVariable String userId, @PathVariable String key) {
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                settingService.getUserSetting(userId, key)
                        .map(ResponseEntity::ok)
                        .defaultIfEmpty(ResponseEntity.notFound().build()));
    }

    @PutMapping("/users/{userId}/{key}")
    @Operation(summary = "Définir une préférence utilisateur")
    public Mono<UserSettingResponse> updateUserSetting(
            @PathVariable String userId,
            @PathVariable String key,
            @Valid @RequestBody UpdateSettingRequest request) {
        // Modifier les preferences d'autrui : la meme faille, en ecriture.
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                settingService.updateUserSetting(userId, key, request.getValue()));
    }

    @DeleteMapping("/users/{userId}/{key}")
    @Operation(summary = "Supprimer une préférence utilisateur")
    public Mono<ResponseEntity<Void>> deleteUserSetting(
            @PathVariable String userId, @PathVariable String key) {
        return ResourceOwnershipGuard.guard(userId, refus(userId),
                settingService.deleteUserSetting(userId, key)
                        .thenReturn(ResponseEntity.noContent().<Void>build()));
    }
}
