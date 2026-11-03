package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.BiometricCredential;
import com.abdatytch.auth_service.enums.BiometricType;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface BiometricCredentialRepository extends ReactiveCrudRepository<BiometricCredential, String> {

    Mono<BiometricCredential> findByCredentialId(String credentialId);

    Flux<BiometricCredential> findByUserId(String userId);

    Flux<BiometricCredential> findByUserIdAndIsActive(String userId, Boolean isActive);

    Mono<Long> countByUserIdAndIsActive(String userId, Boolean isActive);

    Mono<BiometricCredential> findByUserIdAndDeviceId(String userId, String deviceId);

    Flux<BiometricCredential> findByUserIdAndBiometricType(String userId, BiometricType biometricType);

    Mono<Boolean> existsByUserIdAndDeviceId(String userId, String deviceId);

    Mono<Void> deleteByUserIdAndCredentialId(String userId, String credentialId);
}
