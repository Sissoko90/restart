package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.EtatEffacementResponse;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Effacement d'un compte au sens du RGPD.
 *
 * <h2>Pourquoi une interface</h2>
 *
 * {@code AccountErasureService} etait une classe concrete, injectee telle quelle
 * par deux controleurs. Le reste du service passe par des interfaces
 * {@code I*}, et l'ecart n'etait pas qu'une affaire de convention : le contrat
 * de l'operation la plus irreversible du depot n'etait decrit nulle part
 * ailleurs que dans son implementation.
 *
 * <p>La classe reste dans le paquet {@code erasure/}, aupres de ses auditeurs
 * Kafka et de son publieur : les y separer de l'interface serait un
 * deplacement sans gain.
 *
 * <h2>Effacer n'est pas supprimer la ligne</h2>
 *
 * La regle du projet est d'<b>ecraser les champs identifiants</b> et de
 * <b>conserver l'identifiant technique</b>. Supprimer la ligne briserait les
 * references des journaux d'audit, des affectations de role et des ecritures de
 * facturation.
 *
 * <p>C'est exactement ce que faisait {@code /internal/auth/users/{id}} en
 * {@code DELETE} avant correction : un {@code userRepository.delete(user)},
 * sans inscription au registre de preuve exige par l'article 5.2, et sans
 * prevenir les cinq autres detenteurs de donnees.
 */
public interface IAccountErasureService {

    /**
     * Inscrit la demande au registre, efface, et diffuse l'evenement qui
     * declenche l'effacement dans les autres services.
     *
     * @param subjectId identifiant technique de la personne
     * @param requestedBy auteur de la demande, conserve au registre
     * @param reason motif, conserve au registre
     * @return l'identifiant de la demande au registre. Un {@code Mono} vide
     *         signifie que <b>rien n'a ete enregistre</b> : l'appelant ne doit
     *         pas conclure a un effacement, faute de quoi la personne est tenue
     *         pour effacee sans que personne ne reprenne le traitement.
     */
    Mono<UUID> requestErasure(String subjectId, String requestedBy, ErasureReason reason);

    /**
     * Preuve d'execution d'une demande : qui a efface, quand, combien de lignes.
     *
     * <p>Etait assemblee dans {@code ErasureController}, qui injectait pour cela
     * {@code ErasureRequestRepository} et
     * {@code ErasureAcknowledgementRepository} et construisait une {@code Map}
     * imbriquee. La reponse qui demontre la conformite a l'article 5.2 ne doit
     * pas dependre de chaines ecrites a la main dans un controleur.
     *
     * @return l'etat, ou un {@code Mono} vide si aucune demande ne porte cet
     *         identifiant — l'appelant rend alors 404
     */
    Mono<EtatEffacementResponse> etatDeLaDemande(String requestId);
}
