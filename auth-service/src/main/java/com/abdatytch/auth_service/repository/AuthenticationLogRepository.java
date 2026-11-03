package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.AuthenticationLog;
import com.abdatytch.auth_service.enums.EventType;
import com.abdatytch.auth_service.enums.Status;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface AuthenticationLogRepository extends ReactiveCrudRepository<AuthenticationLog, String> {

    Flux<AuthenticationLog> findByUserIdOrderByTimestampDesc(String userId);

    Flux<AuthenticationLog> findByPhoneNumberOrderByTimestampDesc(String phoneNumber);

    Flux<AuthenticationLog> findByEventTypeAndStatusOrderByTimestampDesc(EventType eventType, Status status);

    Mono<Long> countByPhoneNumberAndStatusAndTimestampAfter(
            String phoneNumber, Status status, LocalDateTime after);

    Mono<Void> deleteByTimestampBefore(LocalDateTime dateTime);
}
