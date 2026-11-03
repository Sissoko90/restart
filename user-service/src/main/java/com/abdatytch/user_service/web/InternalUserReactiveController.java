package com.abdatytch.user_service.web;

import com.abdatytch.user_service.constants.WebConstants;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Lecture d'un profil par un autre service.
 *
 * <h2>Les deux écritures ont été retirées, parce qu'elles doublaient Kafka</h2>
 *
 * Ce contrôleur portait trois endpoints :
 *
 * <ul>
 *   <li>{@code POST /internal/users} — création d'un compte. C'était un
 *       <b>second mécanisme</b> pour ce que l'événement {@code user.created}
 *       fait déjà : auth-service appelait les deux, dans la même méthode
 *       ({@code publishUserCreated} puis {@code syncUserWithUserService}). Et
 *       l'appel Feign était enveloppé dans un {@code catch (Exception)} qui se
 *       contentait de journaliser — il échouait depuis toujours sans que
 *       personne le voie, parce que la voie Kafka faisait le travail. Supprimé
 *       des deux côtés ;</li>
 *   <li>{@code POST /internal/users/auth/{id}/login} — mise à jour de la
 *       dernière connexion. Son implémentation rendait {@code Mono.empty()} :
 *       la date de dernière connexion appartient à auth-service. Trois niveaux
 *       d'indirection pour ne rien faire. Supprimé ;</li>
 *   <li>{@code GET /internal/users/auth/{id}} — conservé. C'est le seul des
 *       trois qui ne double rien, et le besoin est réel : un service voisin peut
 *       avoir à lire le profil applicatif d'un compte.</li>
 * </ul>
 *
 * <h2>Le rôle SERVICE n'était accordé à personne</h2>
 *
 * La version précédente annonçait « Sécurisé par JWT avec rôle SERVICE », et
 * {@code SecurityConfig} réservait bien {@code /internal/**} à ce rôle. Mais le
 * {@code ServiceTokenFilter} qui seul l'accordait gardait les chemins commençant
 * par {@code /api/v1/internal/} — un préfixe que ce service ne sert pas, la
 * passerelle le retirant avant transmission. Le filtre ne se déclenchait donc
 * <b>jamais</b>, et toute la surface interne était refusée.
 *
 * <p>La décision d'autorisation vit désormais dans {@code AutorisationInterne},
 * branché sur la chaîne de filtres. Ce contrôleur ne la reprend <b>pas</b> : la
 * vérifier ici aussi ferait deux mécanismes pour une même exigence, et poserait
 * la question de savoir lequel fait foi. Et la placer ici <i>seulement</i> —
 * ce que j'avais d'abord fait — protégeait cet endpoint et laissait le prochain
 * ouvert.
 */
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Interne", description = "Appels de service à service. Non routé par la passerelle.")
public class InternalUserReactiveController {

    private final UserService userService;

    /**
     * Profil applicatif d'un compte, par son identifiant d'authentification.
     *
     * <p>Un appel sans secret valide n'arrive jamais jusqu'ici :
     * {@code AutorisationInterne} le refuse dans la chaîne de filtres, avant
     * que ce code ne soit atteint — donc avant toute lecture en base, ce qui
     * évite aussi qu'un appel non authentifié en fasse porter le coût.
     */
    @GetMapping("/auth/{authUserId}")
    @Operation(summary = "Profil applicatif d'un compte",
            description = "Appel de service à service. Exige l'en-tête "
                    + "X-Internal-Secret, vérifié par la chaîne de filtres.")
    public Mono<ResponseEntity<UserDetailDTO>> getUserByAuthUserId(
            @PathVariable String authUserId) {
        log.debug(WebConstants.LOG_INTERNAL_GET_USER_BY_AUTH_ID, authUserId);
        return userService.getUserByAuthUserId(authUserId)
                .map(ResponseEntity::ok)
                // Le compte introuvable leve une ResourceNotFoundException, que
                // le gestionnaire global rend en 404 : meme reponse qu'un refus
                // de secret, ce qui est exactement voulu.
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
