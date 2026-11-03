package com.abdatytch.auth_service.mappers;

import com.abdatytch.auth_service.dto.response.VerificationResponse;
import org.springframework.stereotype.Component;

/**
 * Mapper pour construire les VerificationResponse
 * Centralise la logique de construction des réponses de vérification
 */
@Component
public class VerificationResponseMapper {
    
    /**
     * Construit une VerificationResponse de succès
     * @param message Message de succès
     * @param expiresInMinutes Durée de validité du code en minutes
     * @return VerificationResponse
     */
    public VerificationResponse toSuccessResponse(String message, int expiresInMinutes) {
        return VerificationResponse.builder()
                .success(true)
                .message(message)
                .expiresInMinutes(expiresInMinutes)
                .build();
    }
    
    /**
     * Construit une VerificationResponse d'échec
     * @param message Message d'erreur
     * @return VerificationResponse
     */
    public VerificationResponse toFailureResponse(String message) {
        return VerificationResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}
