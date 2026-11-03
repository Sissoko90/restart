package com.abdatytch.user_service.repository;

import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.enums.UserStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Dépôt réactif de l'entité {@link User}.
 *
 * <h2>La pagination se fait en SQL, pas en mémoire</h2>
 *
 * Les trois listes paginées portent {@code LIMIT} et {@code OFFSET} dans la
 * requête. {@code findAllActive} et {@code searchUsers} ne les portaient pas :
 * le service rendait la table entière puis jetait les lignes en trop par
 * {@code skip(offset).take(limit)}. Demander la deuxième page de vingt comptes
 * faisait traverser au pilote, au réseau et au convertisseur l'intégralité des
 * comptes, pour en garder vingt.
 *
 * <p>C'est invisible sur une base de développement et proportionnel au nombre de
 * comptes en production : le coût d'une page ne dépend pas de sa taille mais du
 * volume total.
 *
 * <h2>Un ORDER BY, sinon la pagination ne pagine rien</h2>
 *
 * Sans tri, PostgreSQL n'est tenu à aucun ordre : deux appels consécutifs
 * peuvent rendre les lignes différemment, donc {@code OFFSET 20} peut sauter des
 * comptes déjà vus et en répéter d'autres. {@code created_at DESC} fixe l'ordre.
 */
@Repository
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    Mono<User> findByAuthUserId(String authUserId);

    Mono<Boolean> existsByAuthUserId(String authUserId);

    // Note: email et phoneNumber sont dans auth-service (source de verite).
    // Les recherches par email/phone doivent passer par auth-service.

    Flux<User> findByStatus(UserStatus status);

    @Query("SELECT * FROM users WHERE status = :status "
            + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> findByStatusWithPagination(UserStatus status, int limit, int offset);

    /**
     * Comptes non révoqués, par ordre de création décroissant.
     *
     * <p>{@code deleted_at IS NULL} et non {@code status <> 'DELETED'} : c'est
     * l'horodatage qui porte l'effacement logique, et l'index partiel
     * {@code idx_users_actifs} est posé sur cette condition exacte.
     */
    @Query("SELECT * FROM users WHERE deleted_at IS NULL "
            + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> findAllActive(int limit, int offset);

    /**
     * Recherche sur les données que ce service DÉTIENT.
     *
     * <p>La requête précédente ne comparait que {@code auth_user_id}, qui est un
     * UUID : la recherche ne pouvait aboutir qu'en collant un fragment d'UUID,
     * et l'endpoint était donc inutilisable en pratique. Le nom, l'adresse de
     * courriel et le numéro vivent dans auth-service et ne sont pas ici ; ce qui
     * est ici, c'est le profil déclaratif — ville, pays, adresse postale.
     *
     * <p>{@code LEFT JOIN} et non {@code JOIN} : un compte sans profil doit
     * rester trouvable par son {@code auth_user_id}. Une jointure interne
     * l'aurait fait disparaître des résultats.
     *
     * <p>{@code ILIKE} pour ignorer la casse. Le motif est construit par
     * l'appelant, qui doit neutraliser {@code %} et {@code _} — sans quoi une
     * recherche sur {@code %} rendrait tous les comptes.
     *
     * @param motif motif {@code ILIKE} déjà encadré de {@code %}
     */
    @Query("SELECT u.* FROM users u "
            + "LEFT JOIN user_profiles p ON p.user_id = u.id "
            + "WHERE u.deleted_at IS NULL "
            + "AND (u.auth_user_id ILIKE :motif "
            + "  OR p.city ILIKE :motif "
            + "  OR p.country ILIKE :motif "
            + "  OR p.address ILIKE :motif) "
            + "ORDER BY u.created_at DESC LIMIT :limit OFFSET :offset")
    Flux<User> searchUsers(String motif, int limit, int offset);

    /**
     * Compte les lignes par statut, pour les jauges d'observabilité.
     *
     * <p>Les comptes révoqués sont exclus : {@code users_active_count} doit dire
     * combien de comptes sont vivants, et un compte effacé garde sa ligne.
     */
    @Query("SELECT COUNT(*) FROM users WHERE status = :status AND deleted_at IS NULL")
    Mono<Long> compterParStatut(UserStatus status);
}
