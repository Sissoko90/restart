package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.MessageTemplate;
import com.abdatytch.auth_service.enums.TemplateType;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface MessageTemplateRepository extends ReactiveCrudRepository<MessageTemplate, String> {

    Mono<MessageTemplate> findByTemplateTypeAndIsActiveTrue(TemplateType templateType);

    Mono<Boolean> existsByTemplateType(TemplateType templateType);
}
