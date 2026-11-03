package com.abdatytch.setting_service.exception;

import com.abdatytch.setting_service.constant.SettingsConstants;
import com.abdatytch.setting_service.enums.SettingType;

/**
 * Levée quand la valeur fournie est incompatible avec le type du paramètre.
 */
public class InvalidSettingValueException extends RuntimeException {

    public InvalidSettingValueException(String key, String value, SettingType type) {
        super(String.format(SettingsConstants.MSG_INVALID_VALUE, value, type, key));
    }
}
