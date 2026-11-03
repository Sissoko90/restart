package com.abdatytch.security_service.repositories;

import com.abdatytch.security_service.entities.PaysBloque;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface PaysBloqueRepository extends ReactiveCrudRepository<PaysBloque, String> {

    /** Pays effectivement refuses, levees exclues. */
    Flux<PaysBloque> findByLeveeLeIsNull();
}
