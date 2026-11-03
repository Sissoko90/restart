package com.abdatytch.auth_service.client.config;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.service.ServiceTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Intercepteur Feign pour ajouter automatiquement le token de service
 * aux appels inter-services
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeignClientInterceptor implements RequestInterceptor {
    
    /**
     * Générateur local (HMAC-SHA256, aucune I/O).
     *
     * <p>Auparavant, l'intercepteur appelait {@code IJwtService.generateServiceToken()},
     * qui faisait un appel HTTP <b>bloquant</b> vers interface-service pour obtenir un
     * token — depuis un intercepteur Feign, donc potentiellement sur un thread réactif.
     * {@link ServiceTokenProvider} produit le même token localement, sans réseau.
     */
    private final ServiceTokenProvider serviceTokenProvider;
    
    @Override
    public void apply(RequestTemplate template) {
        try {
            String serviceToken = serviceTokenProvider.generateServiceToken();
            template.header("Authorization", "Bearer " + serviceToken);
            log.debug(AppConstants.LOG_FEIGN_CLIENT_INTERCEPTOR, template.method(), template.url());
        } catch (Exception e) {
            log.error(AppConstants.LOG_FEIGN_CLIENT_INTERCEPTOR_ERROR, e);
        }
    }
}
