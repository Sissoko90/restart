package com.abdatytch.quotas_service.repositories;

import com.abdatytch.quotas_service.entities.UserConsumption;
import org.springframework.data.cassandra.repository.ReactiveCassandraRepository;
import org.springframework.stereotype.Repository;

/**
 * Accès réactif à la consommation des utilisateurs.
 */
@Repository
public interface UserConsumptionRepository extends ReactiveCassandraRepository<UserConsumption, String> {
}
