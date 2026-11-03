package com.abdatytch.user_service.enums;

/**
 * Types d'actions administratives pour l'audit
 */
public enum ActionType {
    CREATE,
    UPDATE,
    DELETE,
    BLOCK,
    UNBLOCK,
    SUSPEND,
    UNSUSPEND,
    BAN,
    UNBAN,
    REVOKE,
    RESTORE,
    ROLE_CHANGE,
    STATUS_CHANGE,
    PROFILE_UPDATE
}
