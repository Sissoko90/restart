package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.response.UserResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Operations ouvertes aux autres services de la plateforme sur
 * {@code /internal/auth/**}.
 *
 * <h2>Pourquoi ce service existe</h2>
 *
 * {@code InternalAuthController} injectait {@code UserRepository} et
 * {@code UserMapper} : il lisait la base et traduisait les entites lui-meme.
 * Deux consequences concretes, au-dela de la couche :
 *
 * <ul>
 *   <li>le contrat de l'operation d'effacement — la plus irreversible du depot —
 *       etait decrit dans un controleur, et son garde-fou le plus important (un
 *       identifiant de registre obligatoire) y etait ecrit en ligne ;</li>
 *   <li>l'ancienne version de cet endpoint appelait
 *       {@code userRepository.delete(user)} <b>parce que le repository etait a
 *       portee de main</b>. Avec un service interpose, la suppression franche
 *       n'aurait meme pas ete exprimable.</li>
 * </ul>
 *
 * <p>Le controle du secret inter-services reste dans le controleur : c'est de
 * l'authentification du transport, pas de la logique de compte.
 */
public interface IInternalAccountService {

    /**
     * @param userId identifiant technique
     * @return la vue publique du compte, ou une erreur {@code USER_4001}
     */
    Mono<UserResponse> lireCompte(String userId);

    /**
     * Efface un compte a la demande d'un autre service.
     *
     * <p>Delegue a {@link IAccountErasureService#requestErasure} : inscription
     * au registre de preuve, ecrasement des champs identifiants, et diffusion de
     * l'evenement qui declenche l'effacement chez les cinq autres detenteurs de
     * donnees. L'ancienne version supprimait la ligne et ne prevenait personne.
     *
     * <p>L'existence est verifiee <b>avant</b> : {@code requestErasure} tolere
     * un sujet inconnu (il compte zero enregistrement), alors que le contrat de
     * cet endpoint est de signaler un identifiant inexistant a l'appelant.
     *
     * @return l'identifiant de la demande au registre. Ne rend <b>jamais</b> un
     *         {@code Mono} vide en cas de succes : un effacement sans
     *         identifiant de registre n'a pas ete enregistre, et l'appelant
     *         tiendrait la personne pour effacee sans que personne ne reprenne
     *         le traitement.
     */
    Mono<UUID> effacerCompte(String userId, String demandeur);
}
