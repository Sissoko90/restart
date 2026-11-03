package com.abdatytch.user_service.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EntryPoint supprimé - Migration vers WebFlux
 * La gestion des erreurs d'authentification est maintenant dans GlobalExceptionHandler réactif
 * et dans les WebFilters (ServiceTokenFilter, GatewayAccessFilter)
 */
@Component
@Slf4j
public class JwtAuthenticationEntryPoint {
    // Classe vide - conservée pour compatibilité mais non utilisée
}
