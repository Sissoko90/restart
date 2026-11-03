package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.Role;
import com.abdatytch.auth_service.enums.UserRole;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface RoleRepository extends ReactiveCrudRepository<Role, String> {

    Mono<Role> findByName(UserRole name);

    Mono<Boolean> existsByName(UserRole name);
}
