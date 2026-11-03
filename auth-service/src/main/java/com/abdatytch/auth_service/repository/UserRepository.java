package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface UserRepository extends ReactiveCrudRepository<User, String> {

    Mono<User> findByPhoneNumber(String phoneNumber);

    Mono<User> findByEmail(String email);

    Mono<Boolean> existsByPhoneNumber(String phoneNumber);

    Mono<Boolean> existsByEmail(String email);
}
