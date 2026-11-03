package com.abdatytch.setting_service.service.impl;

import com.abdatytch.setting_service.config.SettingsProperties;
import com.abdatytch.setting_service.constant.SettingsConstants;
import com.abdatytch.setting_service.dto.response.SettingResponse;
import com.abdatytch.setting_service.dto.response.UserSettingResponse;
import com.abdatytch.setting_service.entities.SystemSetting;
import com.abdatytch.setting_service.entities.UserSetting;
import com.abdatytch.setting_service.enums.SettingCategory;
import com.abdatytch.setting_service.exception.InvalidSettingValueException;
import com.abdatytch.setting_service.exception.SettingNotFoundException;
import com.abdatytch.setting_service.repositories.SystemSettingRepository;
import com.abdatytch.setting_service.repositories.UserSettingRepository;
import com.abdatytch.setting_service.service.ISettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**

 * Paramètres système et préférences utilisateur.
 *
 * <p>Le catalogue ({@link SettingsProperties}) fait office de <b>liste blanche</b> :
 * une clé système inconnue est rejetée, ce qui empêche d'injecter des paramètres
 * arbitraires dans la configuration de la plateforme.
 *
 * <p>La base ne contient que les surcharges. Une lecture combine donc le défaut de
 * configuration et l'éventuelle ligne en base ; une réinitialisation supprime la
 * ligne au lieu de réécrire la valeur d'origine — ainsi, changer un défaut en
 * configuration prend effet immédiatement pour tous les paramètres non surchargés.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingServiceImpl implements ISettingService {

    private final SystemSettingRepository systemSettingRepository;
    private final UserSettingRepository userSettingRepository;
    private final SettingsProperties settingsProperties;

    // ==========================================================
    // Paramètres système
    // ==========================================================

    @Override
    public Flux<SettingResponse> getAllSystemSettings() {
        return overrides().flatMapMany(overrides -> Flux.fromIterable(
                settingsProperties.catalog().values())
                .map(definition -> toResponse(definition, overrides.get(definition.key()))));
    }

    @Override
    public Flux<SettingResponse> getSystemSettingsByCategory(SettingCategory category) {
        return getAllSystemSettings().filter(setting -> setting.getCategory() == category);
    }

    @Override
    public Mono<SettingResponse> getSystemSetting(String key) {
        SettingsProperties.Definition definition = requireDefinition(key);

        return systemSettingRepository.findById(key)
                .map(override -> toResponse(definition, override))
                .defaultIfEmpty(toResponse(definition, null));
    }

    @Override
    @Transactional
    public Mono<SettingResponse> updateSystemSetting(String key, String value, String updatedBy) {
        SettingsProperties.Definition definition = requireDefinition(key);

        if (!definition.type().accepts(value)) {
            return Mono.error(new InvalidSettingValueException(key, value, definition.type()));
        }

        return systemSettingRepository.findById(key)
                .doOnNext(existing -> logChange(key, existing.getSettingValue(), value, updatedBy))
                .map(existing -> {
                    existing.setSettingValue(value);
                    existing.setUpdatedBy(updatedBy);
                    existing.setUpdatedAt(Instant.now());
                    return existing;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    logChange(key, definition.defaultValue(), value, updatedBy);
                    return SystemSetting.builder()
                            .settingKey(key)
                            .settingValue(value)
                            .category(definition.category())
                            .updatedBy(updatedBy)
                            .updatedAt(Instant.now())
                            .newEntity(true)
                            .build();
                }))
                .flatMap(systemSettingRepository::save)
                .map(saved -> toResponse(definition, saved));
    }

    /**

     * Journalise tout changement.
     *
     * <p>Le cas particulier du mode maintenance a été <b>retiré</b> avec le
     * paramètre lui-même. Il journalisait {@code MODE MAINTENANCE ACTIVÉ} en
     * {@code WARN} — une trace rassurante pour une décision qui ne coupait
     * rien : aucun code ne lisait {@code system.maintenance-mode}, et le trafic
     * passait.
     *
     * <p>La coupure est désormais portée par les fenêtres de maintenance de
     * security-service, et appliquée par {@code MaintenanceWebFilter} dans le
     * gateway. La trace y est au même niveau, et elle correspond à un effet réel.
     */
    private void logChange(String key, String previous, String value, String updatedBy) {
        log.info(SettingsConstants.LOG_SYSTEM_UPDATED, key, previous, value, updatedBy);
    }

    @Override
    @Transactional
    public Mono<SettingResponse> resetSystemSetting(String key) {
        SettingsProperties.Definition definition = requireDefinition(key);

        // Supprimer la surcharge suffit : la valeur de configuration reprend la main.
        return systemSettingRepository.deleteBySettingKey(key)
                .doOnSuccess(unused -> log.info(SettingsConstants.LOG_SYSTEM_RESET,
                        key, definition.defaultValue()))
                .thenReturn(toResponse(definition, null));
    }

    private SettingsProperties.Definition requireDefinition(String key) {
        SettingsProperties.Definition definition = settingsProperties.definitionOf(key);
        if (definition == null) {
            throw new SettingNotFoundException(key);
        }
        return definition;
    }

    /** Surcharges indexées par clé, en une seule lecture. */
    private Mono<Map<String, SystemSetting>> overrides() {
        return systemSettingRepository.findAll()
                .collectMap(SystemSetting::getSettingKey, setting -> setting);
    }

    /**

     * Combine la définition (défaut, type, catégorie) et l'éventuelle surcharge.
     */
    private SettingResponse toResponse(SettingsProperties.Definition definition, SystemSetting override) {
        SettingResponse.SettingResponseBuilder builder = SettingResponse.builder()
                .key(definition.key())
                .type(definition.type())
                .category(definition.category())
                .defaultValue(definition.defaultValue());

        if (override == null) {
            return builder
                    .value(definition.defaultValue())
                    .overridden(false)
                    .build();
        }

        return builder
                .value(override.getSettingValue())
                .overridden(true)
                .updatedBy(override.getUpdatedBy())
                .updatedAt(override.getUpdatedAt())
                .build();
    }

    // ==========================================================
    // Préférences utilisateur
    // ==========================================================

    @Override
    public Flux<UserSettingResponse> getUserSettings(String userId) {
        return userSettingRepository.findByUserId(userId).map(this::toResponse);
    }

    @Override
    public Mono<UserSettingResponse> getUserSetting(String userId, String key) {
        return userSettingRepository.findByUserIdAndSettingKey(userId, key).map(this::toResponse);
    }

    @Override
    @Transactional
    public Mono<UserSettingResponse> updateUserSetting(String userId, String key, String value) {
        return userSettingRepository.findByUserIdAndSettingKey(userId, key)
                .map(existing -> {
                    existing.setSettingValue(value);
                    existing.setUpdatedAt(Instant.now());
                    return existing;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    UserSetting setting = UserSetting.builder()
                            .userId(userId)
                            .settingKey(key)
                            .settingValue(value)
                            .updatedAt(Instant.now())
                            .newEntity(true)
                            .build();
                    setting.generateIdIfMissing();
                    return setting;
                }))
                .flatMap(userSettingRepository::save)
                .doOnSuccess(saved -> log.info(SettingsConstants.LOG_USER_UPDATED, key, userId, value))
                .map(this::toResponse);
    }

    @Override
    @Transactional
    public Mono<Void> deleteUserSetting(String userId, String key) {
        return userSettingRepository.deleteByUserIdAndSettingKey(userId, key)
                .doOnSuccess(unused -> log.info(SettingsConstants.LOG_USER_DELETED, key, userId));
    }

    @Override
    @Transactional
    public Mono<Void> deleteAllUserSettings(String userId) {
        return userSettingRepository.deleteByUserId(userId)
                .doOnSuccess(unused -> log.info(" Toutes les préférences de l'utilisateur {} supprimées", userId));
    }

    private UserSettingResponse toResponse(UserSetting setting) {
        return UserSettingResponse.builder()
                .userId(setting.getUserId())
                .key(setting.getSettingKey())
                .value(setting.getSettingValue())
                .updatedAt(setting.getUpdatedAt())
                .build();
    }
}
