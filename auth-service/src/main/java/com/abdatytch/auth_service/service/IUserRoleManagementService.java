package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.CreateUserWithRolesRequest;
import com.abdatytch.auth_service.dto.response.UserWithRolesResponse;
import com.abdatytch.auth_service.enums.UserRole;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface pour la gestion des utilisateurs avec rôles multiples
 */
public interface IUserRoleManagementService {
    
    /**
     * Crée un utilisateur avec plusieurs rôles
     */
    Mono<UserWithRolesResponse> createUserWithRoles(CreateUserWithRolesRequest request);
    
    /**
     * Ajoute un rôle à un utilisateur existant
     */
    Mono<UserWithRolesResponse> addRoleToUser(String userId, UserRole role);
    
    /**
     * Supprime un rôle d'un utilisateur
     */
    Mono<UserWithRolesResponse> removeRoleFromUser(String userId, UserRole role);
    
    /**
     * Récupère un utilisateur avec tous ses rôles
     */
    Mono<UserWithRolesResponse> getUserWithRoles(String userId);
    
    /**
     * Récupère tous les utilisateurs ayant un rôle spécifique
     */
    Flux<UserWithRolesResponse> getUsersByRole(UserRole role);
    
    /**
     * Change le rôle principal d'un utilisateur
     */
    Mono<UserWithRolesResponse> changePrimaryRole(String userId, UserRole newPrimaryRole);
}
