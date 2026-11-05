package com.abdatytch.notification_service.repositories;

import com.abdatytch.notification_service.entities.Notification;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * La boite de reception, et le verrou d'idempotence.
 *
 * <h2>Chaque lecture filtre sur le destinataire</h2>
 *
 * Aucune methode ne lit une notification par son seul identifiant. Un
 * {@code findById(notificationId)} suffirait a lire la notification de
 * n'importe qui en devinant un UUID — et les notifications contiennent des
 * codes de verification et des alertes de securite.
 *
 * <p>Meme regle que {@code ProjectRepository} dans interface-service, et pour
 * la meme raison : la propriete doit etre DANS la requete, pas verifiee apres.
 */
@Repository
public interface NotificationRepository extends ReactiveCrudRepository<Notification, UUID> {

    /**
     * Les notifications d'une personne, les plus recentes d'abord.
     *
     * <p>Pagination par {@code LIMIT/OFFSET} : une boite de reception se lit par
     * le debut, et la profondeur reste faible. Un curseur serait plus solide
     * sur des millions de lignes, mais ajouterait une complexite que rien ne
     * justifie encore ici.
     */
    @Query("SELECT * FROM notification WHERE destinataire_id = :destinataireId "
            + "ORDER BY creee_le DESC LIMIT :taille OFFSET :decalage")
    Flux<Notification> parDestinataire(String destinataireId, int taille, int decalage);

    /** Les non lues seulement. */
    @Query("SELECT * FROM notification WHERE destinataire_id = :destinataireId "
            + "AND lue_le IS NULL ORDER BY creee_le DESC LIMIT :taille OFFSET :decalage")
    Flux<Notification> nonLuesParDestinataire(String destinataireId, int taille, int decalage);

    @Query("SELECT COUNT(*) FROM notification WHERE destinataire_id = :destinataireId "
            + "AND lue_le IS NULL")
    Mono<Long> compterNonLues(String destinataireId);

    /**
     * Une notification, mais SEULEMENT si elle appartient au demandeur.
     *
     * <p>Les deux identifiants dans la requete. Rendre vide couvre deux cas que
     * l'appelant ne doit pas distinguer : la notification n'existe pas, ou elle
     * est a quelqu'un d'autre. Repondre differemment permettrait d'enumerer les
     * notifications des autres en lisant le seul code de statut.
     */
    @Query("SELECT * FROM notification WHERE id = :id AND destinataire_id = :destinataireId")
    Mono<Notification> parIdEtDestinataire(UUID id, String destinataireId);

    /**
     * LE VERROU D'IDEMPOTENCE.
     *
     * <p>Kafka garantit « au moins une fois » : un meme message peut etre
     * consomme deux fois apres un redemarrage ou un reequilibrage de groupe.
     * Sans ce controle, l'utilisateur verrait la notification en double, et un
     * code de verification partirait deux fois.
     *
     * <p>La contrainte d'unicite en base est le vrai garde-fou — ce controle
     * evite seulement de lever une erreur sur le cas normal.
     */
    @Query("SELECT * FROM notification WHERE demande_id = :demandeId")
    Mono<Notification> parDemandeId(UUID demandeId);

    /**
     * Toutes les notifications d'une personne, pour l'effacement RGPD.
     *
     * <p>Rend les lignes et non un {@code DELETE} : effacer n'est pas supprimer
     * la ligne. Voir {@code Notification.anonymiser()}.
     */
    @Query("SELECT * FROM notification WHERE destinataire_id = :destinataireId")
    Flux<Notification> toutesPourEffacement(String destinataireId);
}
