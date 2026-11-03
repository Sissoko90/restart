package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.constants.ConfigConstants;
import com.abdatytch.auth_service.service.ServiceTokenProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Intercepteur Feign pour ajouter des tokens de service aux appels internes
 * Utilise maintenant IJwtService qui délègue à interface-service
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeignServiceTokenInterceptor implements RequestInterceptor {
    
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
        if (template.url().contains("/internal/")) {
            try {
                String token = serviceTokenProvider.generateServiceToken();
                template.header("X-Service-Token", token);
                log.info(ConfigConstants.LOG_INFO_FEIGN_INTERCEPTOR, template.url());
            } catch (Exception e) {
                log.error("Erreur lors de la génération du service token pour Feign", e);
            }
        }
    }
}
