package com.abdatytch.quotas_service.repositories;

import com.abdatytch.quotas_service.entities.UserQuota;
import org.springframework.data.cassandra.repository.ReactiveCassandraRepository;
import org.springframework.stereotype.Repository;

/**
 * Accès réactif aux paliers utilisateurs.
 */
@Repository
public interface UserQuotaRepository extends ReactiveCassandraRepository<UserQuota, String> {
}
