package com.abdatytch.user_service.service;

import com.abdatytch.user_service.dto.request.AdminActionDTO;
import com.abdatytch.user_service.dto.request.AdminUserUpdateDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Décisions d'administration sur un compte — <b>service unique</b>.
 *
 * <h2>Ce qui a été réuni ici</h2>
 *
 * Ce service en doublait un autre, {@code AdminUserManagementService}, et les
 * deux portaient trois mêmes opérations sous le même nom. Elles ne faisaient pas
 * la même chose :
 *
 * <ul>
 *   <li>{@code getAllUsers} partait de {@code findAll()} d'un côté et de
 *       {@code findAllActive()} de l'autre : la même page d'administration
 *       incluait ou excluait les comptes révoqués selon l'endpoint
 *       interrogé.</li>
 *   <li>{@code deleteUser} faisait un <b>effacement logique</b> d'un côté et un
 *       {@code DELETE} de la ligne de l'autre. Deux boutons d'une même console,
 *       l'un réversible et l'autre définitif, sans rien qui les
 *       distingue.</li>
 *   <li>{@code searchUsers} chargeait la table entière puis filtrait en mémoire,
 *       sur le seul {@code auth_user_id}.</li>
 * </ul>
 *
 * <h2>Les cinq décisions, une seule mécanique</h2>
 *
 * Blocage, bannissement, suspension, révocation et rétablissement sont cinq
 * décisions <b>distinctes</b> — un bannissement est définitif, un blocage est
 * temporaire, une suspension porte une échéance — mais elles partagent
 * entièrement leur plomberie : lire le compte, refuser s'il n'existe pas, muter,
 * enregistrer, journaliser, invalider le cache, mesurer, composer la réponse.
 *
 * <p>C'était recopié huit fois, à vingt-cinq lignes l'exemplaire. Seule la
 * mutation diffère, et c'est elle qui porte la décision ; le reste est partagé.
 *
 * <h2>Trois réversions devenues une</h2>
 *
 * {@code unblockUser}, {@code unbanUser} et {@code unsuspendUser} existaient
 * séparément et <b>divergeaient</b> : la première effaçait {@code blockedAt}
 * sans toucher {@code suspendedUntil}, la troisième l'inverse. Rétablir un
 * compte suspendu <i>puis</i> bloqué laissait donc une échéance de suspension
 * posée sur un compte redevenu actif.
 *
 * <p>{@link #restoreUser(UUID)} les remplace et remet le compte à plat :
 * l'état d'arrivée est ACTIVE, et ACTIVE ne porte ni motif, ni date de blocage,
 * ni échéance, quel que soit l'état de départ.
 */
public interface UserAdminService {

    /** Blocage temporaire : l'accès est retiré, la décision est réversible. */
    Mono<UserDetailDTO> blockUser(UUID userId, AdminActionDTO action);

    /** Bannissement : même retrait d'accès, mais décision définitive. */
    Mono<UserDetailDTO> banUser(UUID userId, AdminActionDTO action);

    /**
     * Suspension : retrait d'accès <b>avec échéance</b>.
     *
     * <p>C'est l'échéance qui la distingue d'un blocage, et
     * {@code AdminActionDTO.until()} doit donc être fourni.
     */
    Mono<UserDetailDTO> suspendUser(UUID userId, AdminActionDTO action);

    /**
     * Révocation : effacement logique du compte dans ce service.
     *
     * <p>La ligne est conservée — « effacer n'est pas supprimer la ligne » — et
     * l'audit garde trace du compte ayant existé. Le compte d'authentification
     * n'est pas touché : voir {@link #closeAccount(UUID)}.
     */
    Mono<UserDetailDTO> revokeUser(UUID userId);

    /**
     * Rétablissement : retour à ACTIVE depuis n'importe lequel des quatre autres
     * états, motif et échéances effacés.
     */
    Mono<UserDetailDTO> restoreUser(UUID userId);

    /**
     * Fermeture complète du compte, dans les deux services.
     *
     * <p>C'est ce que porte {@code DELETE /admin/users/{id}}, et ce qui le
     * distingue de {@link #revokeUser(UUID)} : la révocation ne vaut qu'ici,
     * la fermeture propage à auth-service et <b>consigne la décision dans le
     * journal d'audit</b>.
     *
     * <p>Elle réutilise la révocation pour la partie locale : il n'y a qu'une
     * implémentation du passage à l'état effacé.
     */
    Mono<Void> closeAccount(UUID userId);

    /**
     * Modification du profil par un administrateur.
     *
     * <p>Ne touche que ce que ce service détient. Le nom, l'adresse de courriel,
     * le numéro et le rôle vivent dans auth-service et ne se modifient pas d'ici.
     */
    Mono<UserDetailDTO> updateUserByAdmin(UUID userId, AdminUserUpdateDTO dto);
}
