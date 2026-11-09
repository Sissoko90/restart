package com.abdatytch.notification_service.repositories;

import com.abdatytch.notification_service.entities.JetonDAppareil;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/** Les appareils joignables par push. */
@Repository
public interface JetonDAppareilRepository
        extends ReactiveCrudRepository<JetonDAppareil, UUID> {

    /** Tous les appareils d'une personne. Le push va vers chacun. */
    @Query("SELECT * FROM jeton_d_appareil WHERE destinataire_id = :destinataireId "
            + "ORDER BY vu_le DESC")
    Flux<JetonDAppareil> parDestinataire(String destinataireId);

    /**
     * Enregistre un appareil, ou DEPLACE son jeton vers un nouveau proprietaire.
     *
     * <h2>Le conflit porte sur le JETON SEUL, et c'est une regle de securite</h2>
     *
     * Un jeton designe un APPAREIL. Quand une seconde personne se connecte sur
     * le meme telephone, FCM lui attribue le meme jeton — et sans ce
     * deplacement, l'appareil continuerait de recevoir les notifications du
     * PREMIER proprietaire : ses codes de verification, ses alertes de
     * securite, le contenu de sa boite.
     *
     * <p>{@code destinataire_id} figure donc dans le {@code DO UPDATE} : le
     * jeton change de main, et la ligne precedente n'existe plus.
     *
     * <h2>Pourquoi {@code ON CONFLICT} et non une lecture puis une ecriture</h2>
     *
     * Deux enregistrements simultanes du meme jeton — un client qui redemarre
     * deux fois — se verraient tous deux absents, et le second echouerait sur
     * la contrainte d'unicite. PostgreSQL tranche sous le verrou de l'index.
     *
     * @return le nombre de lignes touchees, toujours 1
     */
    @Query("""
            INSERT INTO jeton_d_appareil
                   (id, destinataire_id, jeton, plateforme, enregistre_le, vu_le)
            VALUES (:id, :destinataireId, :jeton, :plateforme,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (jeton) DO UPDATE
               SET destinataire_id = EXCLUDED.destinataire_id,
                   plateforme = EXCLUDED.plateforme,
                   vu_le = CURRENT_TIMESTAMP
            """)
    Mono<Long> enregistrer(UUID id, String destinataireId, String jeton, String plateforme);

    /**
     * Retire un jeton, et SEULEMENT s'il appartient a cette personne.
     *
     * <h3>Le filtre sur le destinataire n'est pas une precaution de style</h3>
     *
     * Sans lui, n'importe quel porteur authentifie pourrait desinscrire
     * l'appareil de quelqu'un d'autre en devinant ou en interceptant son jeton
     * — donc le priver silencieusement de ses alertes de securite. C'est la
     * meme regle que partout ailleurs dans ce depot : la propriete se verifie
     * dans la requete, pas dans le controleur.
     *
     * @return le nombre de lignes supprimees : 0 si le jeton est inconnu OU
     *         appartient a un autre, et l'appelant ne doit pas distinguer les
     *         deux
     */
    @Query("DELETE FROM jeton_d_appareil WHERE jeton = :jeton "
            + "AND destinataire_id = :destinataireId")
    Mono<Long> retirerPour(String destinataireId, String jeton);

    /**
     * Retire un jeton que FCM a declare invalide, quel que soit son proprietaire.
     *
     * <p>Pas de filtre sur le destinataire ici, et c'est voulu : la decision
     * vient du FOURNISSEUR, qui ne connait que le jeton. Un jeton
     * {@code UNREGISTERED} designe une application desinstallee ou un jeton
     * remplace — il n'est plus joignable pour personne.
     *
     * <p>Sans cette purge, on pousse indefiniment vers des appareils disparus :
     * chaque notification paie un appel reseau et chaque compte rendu porte un
     * echec qui ne se reparera jamais.
     */
    @Query("DELETE FROM jeton_d_appareil WHERE jeton = :jeton")
    Mono<Long> retirerJetonInvalide(String jeton);

    /**
     * Retire TOUS les appareils d'une personne.
     *
     * <h3>Deux usages, et le second compte autant que le premier</h3>
     *
     * Pour la personne : couper les notifications sur un telephone perdu, sans
     * acces a ce telephone.
     *
     * <p>Pour la plateforme : c'est ce qu'un effacement RGPD devra appeler. Un
     * jeton d'appareil identifie un telephone, donc une personne. Ce service
     * n'ecoute pas encore l'evenement d'effacement — la capacite existe, et
     * elle est deja exercee par un endpoint reel plutot que d'attendre en code
     * mort.
     *
     * @return le nombre d'appareils retires, zero compris
     */
    @Query("DELETE FROM jeton_d_appareil WHERE destinataire_id = :destinataireId")
    Mono<Long> retirerTousPour(String destinataireId);

    /**
     * Les jetons dormants depuis une date.
     *
     * <p>Un client reenregistre son jeton a chaque demarrage : une date de
     * derniere vue ancienne signale une application desinstallee dont FCM n'a
     * pas encore signale l'invalidite.
     */
    @Query("DELETE FROM jeton_d_appareil WHERE vu_le < :avant")
    Mono<Long> purgerLesDormants(LocalDateTime avant);
}
