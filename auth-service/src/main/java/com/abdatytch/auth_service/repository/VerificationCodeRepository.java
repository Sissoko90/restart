package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.VerificationCode;
import com.abdatytch.auth_service.enums.VerificationCodeType;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface VerificationCodeRepository extends ReactiveCrudRepository<VerificationCode, String> {

    Mono<VerificationCode> findByPhoneNumberAndCodeAndType(
            String phoneNumber, String code, VerificationCodeType type);

    Mono<VerificationCode> findByEmailAndCodeAndType(
            String email, String code, VerificationCodeType type);

    Mono<VerificationCode> findByPhoneNumberAndType(
            String phoneNumber, VerificationCodeType type);

    Mono<VerificationCode> findTopByPhoneNumberAndTypeOrderByCreatedAtDesc(
            String phoneNumber, VerificationCodeType type);

    Mono<Integer> deleteByExpiresAtBefore(LocalDateTime dateTime);

    Mono<Void> deleteByPhoneNumberAndType(String phoneNumber, VerificationCodeType type);
}
