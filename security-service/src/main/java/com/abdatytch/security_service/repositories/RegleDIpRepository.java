package com.abdatytch.security_service.repositories;

import com.abdatytch.security_service.entities.RegleDIp;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface RegleDIpRepository extends ReactiveCrudRepository<RegleDIp, String> {

    /**
     * Regles non levees, historique exclu.
     *
     * <p>L'index partiel {@code idx_regle_ip_actives} sert cette requete : le
     * rechargement du cache n'a aucune raison de parcourir des regles levees il
     * y a six mois.
     *
     * <p>Les regles <b>expirees</b> sont incluses : l'expiration se juge sur
     * l'horloge, et la filtrer en SQL ferait dependre le resultat de l'heure du
     * serveur de base plutot que de celle du service. Le tri est fait en Java,
     * par {@code RegleDIp.estActive}.
     */
    Flux<RegleDIp> findByLeveeLeIsNull();

    /** Regles non levees portant cette adresse, pour eviter un doublon. */
    Flux<RegleDIp> findByAdresseAndLeveeLeIsNull(String adresse);

    Mono<Long> countByLeveeLeIsNull();
}
