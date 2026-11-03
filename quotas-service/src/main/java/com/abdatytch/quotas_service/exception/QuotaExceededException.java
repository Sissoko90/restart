package com.abdatytch.quotas_service.exception;

import com.abdatytch.quotas_service.constant.QuotasConstants;
import com.abdatytch.quotas_service.enums.QuotaResource;
import lombok.Getter;

/**
 * Levée quand une consommation ferait dépasser le quota de l'utilisateur.
 * Traduite en HTTP 429 par le {@link GlobalExceptionHandler}.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

    private final QuotaResource resource;
    private final long used;
    private final long limit;
    private final long requested;

    public QuotaExceededException(QuotaResource resource, long used, long limit, long requested) {
        super(String.format(QuotasConstants.MSG_QUOTA_EXCEEDED, resource, used, limit, requested));
        this.resource = resource;
        this.used = used;
        this.limit = limit;
        this.requested = requested;
    }
}
