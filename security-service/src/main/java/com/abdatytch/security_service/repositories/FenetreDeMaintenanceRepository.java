package com.abdatytch.security_service.repositories;

import com.abdatytch.security_service.entities.FenetreDeMaintenance;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface FenetreDeMaintenanceRepository
        extends ReactiveCrudRepository<FenetreDeMaintenance, String> {

    /**
     * Fenetres non levees.
     *
     * <p>Celles qui n'ont pas encore debute sont incluses : c'est ce qui permet
     * d'annoncer une maintenance a l'avance. Le tri sur l'horloge est fait par
     * {@code FenetreDeMaintenance.estActive}.
     */
    Flux<FenetreDeMaintenance> findByLeveeLeIsNull();

    Flux<FenetreDeMaintenance> findByServiceAndLeveeLeIsNull(String service);
}
