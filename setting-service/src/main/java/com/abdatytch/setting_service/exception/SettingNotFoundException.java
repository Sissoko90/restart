package com.abdatytch.setting_service.exception;

import com.abdatytch.setting_service.constant.SettingsConstants;

/**
 * Levée quand une clé ne figure pas au catalogue des paramètres système.
 */
public class SettingNotFoundException extends RuntimeException {

    public SettingNotFoundException(String key) {
        super(String.format(SettingsConstants.MSG_SETTING_NOT_FOUND, key));
    }
}
