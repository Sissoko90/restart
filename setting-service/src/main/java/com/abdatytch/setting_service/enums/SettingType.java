package com.abdatytch.setting_service.enums;

/**
 * Type d'un paramètre, utilisé pour valider la valeur avant enregistrement.
 *
 * <p>Les valeurs sont stockées en texte : ce type indique comment les interpréter
 * et rejette une saisie incohérente (ex. « abc » pour un paramètre entier).
 */
public enum SettingType {

    BOOLEAN,
    INTEGER,
    STRING;

    /**
     * Vérifie qu'une valeur textuelle est compatible avec ce type.
     */
    public boolean accepts(String value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case BOOLEAN -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
            case INTEGER -> {
                try {
                    Integer.parseInt(value);
                    yield true;
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            case STRING -> true;
        };
    }
}
