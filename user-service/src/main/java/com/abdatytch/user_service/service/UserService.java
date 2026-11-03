package com.abdatytch.user_service.service;

import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.dto.request.UserUpdateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.dto.response.UserResponseDTO;
import com.abdatytch.user_service.enums.UserStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service réactif pour la gestion des utilisateurs
 * Toutes les méthodes retournent Mono/Flux pour accès non-bloquant
 */
public interface UserService {
    
    Mono<UserResponseDTO> createUser(UserCreationDTO dto);
    
    Mono<UserDetailDTO> getUserById(UUID id);
    
    Mono<UserDetailDTO> getUserByAuthUserId(String authUserId);
    
    // Note: getUserByEmail et getUserByPhone retirés car email/phone sont dans auth-service
    // Pour rechercher par email/phone, utiliser auth-service puis getUserByAuthUserId
    
    Mono<UserDetailDTO> updateUser(UUID id, UserUpdateDTO dto);
    
    Mono<Void> deleteUser(UUID id);
    
    Flux<UserDetailDTO> searchUsers(String query, int limit, int offset);
    
    Flux<UserDetailDTO> getUsersByStatus(UserStatus status, int limit, int offset);
    
    // Note: getUsersByRole retiré car role est dans auth-service
    
    Flux<UserDetailDTO> getAllUsers(int limit, int offset);
}
