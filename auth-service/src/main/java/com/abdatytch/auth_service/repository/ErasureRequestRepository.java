package com.abdatytch.auth_service.repository;

import com.abdatytch.auth_service.entities.ErasureRequest;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ErasureRequestRepository extends ReactiveCrudRepository<ErasureRequest, String> {

    Flux<ErasureRequest> findBySubjectIdOrderByRequestedAtDesc(String subjectId);

    /**
     * Demandes qu'aucun service n'a terminé de traiter. Une demande qui traîne
     * ici est un effacement incomplet : la personne a exercé son droit, et une
     * partie de ses données subsiste.
     */
    @Query("SELECT * FROM erasure_requests WHERE completed_at IS NULL ORDER BY requested_at")
    Flux<ErasureRequest> findPending();
}
