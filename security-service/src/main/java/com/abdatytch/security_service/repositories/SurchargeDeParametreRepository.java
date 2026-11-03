package com.abdatytch.security_service.repositories;

import com.abdatytch.security_service.entities.SurchargeDeParametre;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Surcharges de la politique.
 *
 * <p>Aucune methode ajoutee : la cle primaire est la cle du catalogue, et
 * {@code findAll()} rend la politique complete telle qu'elle differe des
 * defauts. C'est exactement ce dont le publicateur a besoin.
 */
@Repository
public interface SurchargeDeParametreRepository
        extends ReactiveCrudRepository<SurchargeDeParametre, String> {
}
