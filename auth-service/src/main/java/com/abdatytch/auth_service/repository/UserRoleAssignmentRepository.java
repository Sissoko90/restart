package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.UserRoleAssignment;
import com.abdatytch.auth_service.enums.UserRole;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface UserRoleAssignmentRepository extends ReactiveCrudRepository<UserRoleAssignment, String> {

    Flux<UserRoleAssignment> findByUserId(String userId);

    Flux<UserRoleAssignment> findByEmail(String email);

    Flux<UserRoleAssignment> findByPhoneNumber(String phoneNumber);

    Mono<UserRoleAssignment> findByEmailAndRole(String email, UserRole role);

    Mono<UserRoleAssignment> findByPhoneNumberAndRole(String phoneNumber, UserRole role);

    Mono<Boolean> existsByEmailAndRole(String email, UserRole role);

    Mono<Boolean> existsByPhoneNumberAndRole(String phoneNumber, UserRole role);

    // Ces requêtes étaient en JPQL ; en R2DBC elles s'expriment en SQL natif.
    @Query("SELECT * FROM user_role_assignments WHERE user_id = :userId AND is_active = true")
    Flux<UserRoleAssignment> findActiveRolesByUserId(String userId);

    @Query("SELECT * FROM user_role_assignments WHERE email = :email AND role = :role AND is_active = true")
    Mono<UserRoleAssignment> findActiveByEmailAndRole(String email, String role);

    @Query("SELECT * FROM user_role_assignments WHERE phone_number = :phoneNumber AND role = :role AND is_active = true")
    Mono<UserRoleAssignment> findActiveByPhoneAndRole(String phoneNumber, String role);
}
