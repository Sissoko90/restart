package com.abdatytch.user_service.repository;

import com.abdatytch.user_service.entities.UserProfile;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/**
 * Repository réactif pour l'entité UserProfile
 * Utilise R2DBC pour accès base de données non-bloquant
 */
@Repository
public interface UserProfileRepository extends ReactiveCrudRepository<UserProfile, UUID> {
    
    Mono<UserProfile> findByUserId(UUID userId);

    /**
     * Profils de plusieurs comptes, en <b>une</b> requête.
     *
     * <h3>Le N+1 que cette méthode ferme</h3>
     *
     * {@code UserMapper} charge le profil de chaque compte pour composer sa
     * réponse. Sur une liste, il appelait {@code findByUserId} une fois par
     * ligne : afficher vingt comptes dans la console d'administration
     * déclenchait <b>vingt</b> requêtes, cent comptes en déclenchaient cent.
     *
     * <p>Le coût d'une page dépendait donc de sa taille en nombre
     * d'allers-retours, et chacun prend une connexion du pool. C'est le genre de
     * défaut invisible sur une base locale à trois comptes, et qui sature le
     * pool en production.
     *
     * <p>{@code IN (:userIds)} les rend d'un coup. La liste d'identifiants vient
     * d'une page déjà bornée par {@code LIMIT} : sa taille est plafonnée par la
     * taille de page, jamais par le volume de la table.
     */
    Flux<UserProfile> findAllByUserIdIn(Collection<UUID> userIds);
}
