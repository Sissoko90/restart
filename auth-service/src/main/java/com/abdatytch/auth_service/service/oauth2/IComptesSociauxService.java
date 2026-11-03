package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.dto.response.CompteSocialResponse;
import com.abdatytch.auth_service.enums.OAuthProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Les comptes sociaux lies a un compte, vus et delies par son proprietaire.
 *
 * <h2>Ce qui manquait</h2>
 *
 * On pouvait lier Google, Facebook et desormais Apple — jamais delier, ni meme
 * savoir ce qui etait lie. Une personne qui a relie un compte social par erreur,
 * ou qui n'utilise plus ce fournisseur, n'avait aucune sortie.
 *
 * <h2>La regle qui empeche de s'enfermer dehors</h2>
 *
 * Delier le <b>dernier</b> moyen de connexion rendrait le compte inaccessible a
 * son propre proprietaire : plus de mot de passe utilisable, plus de
 * fournisseur social, et un numero jamais confirme ne permet pas de recevoir un
 * code. L'operation est donc refusee, avec une erreur qui dit laquelle des deux
 * conditions manque.
 *
 * <p>Ce n'est pas une precaution theorique : c'est le resultat normal d'une
 * inscription par Google suivie d'une deliaison de Google.
 */
public interface IComptesSociauxService {

    /**
     * Comptes sociaux lies.
     *
     * <p>Ne rend <b>aucun</b> jeton du fournisseur. Les colonnes
     * {@code access_token} et {@code refresh_token} de {@code oauth_accounts}
     * sont des justificatifs valables chez Google ou Apple : les montrer dans
     * une liste les ferait voyager dans une reponse HTTP alors que la personne
     * demandait seulement de voir ce qui est lie.
     *
     * @param userId compte, pris du jeton et jamais d'un parametre
     */
    Flux<CompteSocialResponse> liesAuCompte(String userId);

    /**
     * Delie un fournisseur du compte.
     *
     * @throws com.abdatytch.auth_service.exception.AuthException
     *         {@code VAL_6007} si c'est le dernier moyen de connexion ;
     *         {@code AUTH_1004} si ce fournisseur n'est pas lie — le refus rend
     *         404 et non 403, conformement a la regle de ce depot
     */
    Mono<Void> delier(String userId, OAuthProvider fournisseur);

    /**
     * Fournisseurs reellement utilisables sur cette installation.
     *
     * <p>Sans cette information, l'application cliente code en dur les boutons
     * qu'elle affiche. Elle propose alors « Se connecter avec Apple » sur une
     * installation ou Apple n'est pas configure, et la personne obtient un refus
     * apres avoir clique — au lieu de ne pas voir le bouton.
     */
    Flux<String> fournisseursDisponibles();
}
