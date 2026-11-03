package com.abdatytch.setting_service.service;

import com.abdatytch.setting_service.dto.response.SettingResponse;
import com.abdatytch.setting_service.dto.response.UserSettingResponse;
import com.abdatytch.setting_service.enums.SettingCategory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Paramètres de la plateforme.
 *
 * <p>Deux niveaux :
 * <ul>
 *   <li><b>système</b> : catalogue fermé (liste blanche), valeurs par défaut en
 *       configuration, surcharges en base, écriture réservée aux administrateurs ;</li>
 *   <li><b>utilisateur</b> : clés libres, propres à chaque utilisateur.</li>
 * </ul>
 */
public interface ISettingService {

    /** Tous les paramètres système, valeur courante et défaut. */
    Flux<SettingResponse> getAllSystemSettings();

    Flux<SettingResponse> getSystemSettingsByCategory(SettingCategory category);

    /**
     * Un paramètre système.
     * @throws com.abdatytch.setting_service.exception.SettingNotFoundException si la clé est hors catalogue
     */
    Mono<SettingResponse> getSystemSetting(String key);

    /**
     * Surcharge un paramètre système.
     * @throws com.abdatytch.setting_service.exception.InvalidSettingValueException si la valeur ne correspond pas au type
     */
    Mono<SettingResponse> updateSystemSetting(String key, String value, String updatedBy);

    /** Supprime la surcharge : le paramètre reprend sa valeur de configuration. */
    Mono<SettingResponse> resetSystemSetting(String key);

    Flux<UserSettingResponse> getUserSettings(String userId);

    /** Mono vide si l'utilisateur n'a pas défini cette préférence. */
    Mono<UserSettingResponse> getUserSetting(String userId, String key);

    Mono<UserSettingResponse> updateUserSetting(String userId, String key, String value);

    Mono<Void> deleteUserSetting(String userId, String key);

    /** Supprime toutes les préférences d'un utilisateur (suppression de compte). */
    Mono<Void> deleteAllUserSettings(String userId);
}
