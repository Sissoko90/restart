package com.abdatytch.quotas_service.exception;

import com.abdatytch.quotas_service.constant.QuotasConstants;

/**
 * Levée quand aucun quota n'est enregistré pour un utilisateur et qu'aucun
 * palier par défaut ne doit être appliqué (consultation stricte).
 */
public class QuotaNotFoundException extends RuntimeException {

    public QuotaNotFoundException(String userId) {
        super(String.format(QuotasConstants.MSG_QUOTA_NOT_FOUND, userId));
    }
}
