package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.AdminLoginRequest;
import com.abdatytch.auth_service.dto.request.TotpResetRequest;
import com.abdatytch.auth_service.dto.response.AuthResponse;
import com.abdatytch.auth_service.dto.response.TotpEnrolementResponse;
import reactor.core.publisher.Mono;

/**
 * Operations propres aux comptes d'administration de la plateforme.
 *
 * <h2>La connexion n'est plus un chemin a part</h2>
 *
 * {@code /auth/admin/login} existait parce que {@code /auth/login} etait reserve
 * au SDK et refusait tout appel non authentifie. Depuis l'ouverture de la
 * surface publique, {@code /auth/login} accepte les comptes d'administration,
 * second facteur compris.
 *
 * <p>{@link #login} est donc conserve comme <b>entree restreinte</b> — elle
 * refuse les comptes sans role d'administration — et <b>delegue</b> la
 * connexion elle-meme a {@link IAuthService#login}. Elle n'en reecrit aucun
 * controle. L'ancienne version en portait sa propre copie, et il lui manquait
 * la verification de {@code isActive} : un administrateur desactive pouvait se
 * connecter par ce chemin, et par celui-la seulement.
 */
public interface IAdminAuthService {

    /**
     * Connexion restreinte aux comptes d'administration.
     *
     * <p>Un compte sans role d'administration reçoit le meme refus qu'un mot de
     * passe invalide : distinguer les deux permettrait d'enumerer les comptes
     * administrateurs, c'est-a-dire de designer la cible.
     *
     * @return un jeton d'acces si identifiant, mot de passe et second facteur
     *         concordent
     */
    Mono<AuthResponse> login(AdminLoginRequest request, String ipAddress, String userAgent);

    /**
     * Premier enrolement du second facteur, a usage unique.
     *
     * <p>Exige le mot de passe, et ne remet le secret que tant que le second
     * facteur n'est pas actif.
     */
    Mono<TotpEnrolementResponse> enrollTotp(AdminLoginRequest request);

    /**
     * Remplacement du second facteur par l'administrateur lui-meme.
     *
     * <p>Exige le mot de passe <b>et</b> le code de l'appareil actuellement
     * enrole. Voir {@link TotpResetRequest} pour le raisonnement.
     */
    Mono<TotpEnrolementResponse> reinitialiserTotp(TotpResetRequest request);

    /**
     * Retrait du second facteur d'un administrateur, par un {@code SUPER_ADMIN}.
     *
     * <p>Seule sortie quand l'appareil enrole est perdu. Le secret n'est jamais
     * rendu a l'appelant : il peut retirer le second facteur d'un autre
     * administrateur, non s'y substituer.
     *
     * @param identifier adresse ou telephone du compte vise
     * @param acteurId identifiant du super administrateur, pour le journal
     * @return un {@code Mono} vide si aucun compte d'administration ne porte cet
     *         identifiant, pour que l'appelant rende 404 et non une confirmation
     */
    Mono<Void> retirerSecondFacteur(String identifier, String acteurId);
}
