package com.abdatytch.notification_service.repositories;

import com.abdatytch.notification_service.entities.RemiseParCanal;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/** Le registre de ce qui est arrive a chaque canal. */
@Repository
public interface RemiseParCanalRepository extends ReactiveCrudRepository<RemiseParCanal, UUID> {

    @Query("SELECT * FROM remise_par_canal WHERE notification_id = :notificationId")
    Flux<RemiseParCanal> parNotification(UUID notificationId);

    /**
     * La ligne d'un canal donne, pour incrementer ses tentatives.
     *
     * <p>Sans cette lecture, un reessai creerait une seconde ligne et violerait
     * la contrainte d'unicite (notification, canal).
     */
    @Query("SELECT * FROM remise_par_canal WHERE notification_id = :notificationId "
            + "AND canal = :canal")
    Mono<RemiseParCanal> parNotificationEtCanal(UUID notificationId, String canal);

    /**
     * Les remises a reprendre : etat {@code ECHOUE} seulement, et au-dela d'un
     * delai.
     *
     * <p>{@code REFUSE} en est exclu volontairement — canal desactive, aucune
     * coordonnee, consentement absent : rien de cela ne changera en reessayant,
     * et les inclure produirait une boucle de reessais perpetuels sur des cas
     * definitivement perdus.
     *
     * <p>{@code SANS_DESTINATAIRE} en est exclu aussi : une session WebSocket
     * absente est le fonctionnement normal, pas une panne.
     */
    @Query("SELECT * FROM remise_par_canal WHERE etat = 'ECHOUE' "
            + "AND essayee_le < :avant AND tentatives < :maxTentatives "
            + "ORDER BY essayee_le LIMIT :taille")
    Flux<RemiseParCanal> aReprendre(LocalDateTime avant, short maxTentatives, int taille);
}
