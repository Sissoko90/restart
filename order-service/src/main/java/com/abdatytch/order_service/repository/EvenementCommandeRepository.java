package com.abdatytch.order_service.repository;

import com.abdatytch.order_service.entities.EvenementCommande;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Le registre des transitions.
 *
 * <p>AUCUNE methode de modification ni de suppression n'est declaree ici, et
 * c'est le point : le registre est en append seul. {@code ReactiveCrudRepository}
 * en herite malgre tout ({@code save} sur une entite existante, {@code delete}),
 * et c'est {@code RegistreEnAppendSeulTest} qui refuse qu'un appel de ce genre
 * apparaisse dans le code de ce module.
 */
@Repository
public interface EvenementCommandeRepository
        extends ReactiveCrudRepository<EvenementCommande, UUID> {

    Flux<EvenementCommande> findByCommandeIdOrderByCreatedAtAsc(UUID commandeId);
}
