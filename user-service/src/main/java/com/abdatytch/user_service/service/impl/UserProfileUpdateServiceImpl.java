package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.FileStorageService;
import com.abdatytch.user_service.service.UserProfileUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;

/**
 * Rattache une photo de profil à un compte.
 *
 * <h2>L'invalidation du cache manquait</h2>
 *
 * Cette écriture ne touchait pas le cache. Or les deux endpoints qui rendent la
 * photo — {@code GET /users/me} et {@code GET /users/me/profile-picture} —
 * passent tous deux par {@code getUserByAuthUserId}, donc par une entrée mise
 * en cache un quart d'heure. Après l'envoi d'une photo, la personne continuait
 * d'en voir l'ancienne, ou aucune, et l'envoi paraissait avoir échoué alors
 * qu'il avait abouti.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileUpdateServiceImpl implements UserProfileUpdateService {

    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final CacheDesComptes cache;

    @Override
    public Mono<String> updateProfilePicture(UUID userId, String profilePictureUrl) {
        log.info("Rattachement d'une photo de profil au compte {}", userId);

        return userRepository.findById(userId)
            .switchIfEmpty(Mono.error(new ResourceNotFoundException("User not found: " + userId)))
            .flatMap(user -> {
                user.setProfilePictureUrl(profilePictureUrl);
                return userRepository.save(user);
            })
            .flatMap(user -> cache.invaliderPuisRendre(
                    user.getId(), user.getAuthUserId(), user))
            .map(user -> {
                log.info("Photo de profil rattachee au compte {}", userId);
                return user.getProfilePictureUrl();
            });
    }

    @Override
    public Mono<Void> supprimerPhotoDeProfil(UUID userId) {
        log.info("Retrait de la photo de profil du compte {}", userId);

        return userRepository.findById(userId)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("User not found: " + userId)))
                .flatMap(user -> {
                    String url = user.getProfilePictureUrl();
                    if (url == null || url.isBlank()) {
                        // Idempotent : rien a retirer, et c'est un succes. Un
                        // refus serait inutilement bavard pour une suppression.
                        log.debug("Aucune photo a retirer pour le compte {}", userId);
                        return Mono.<Void>empty();
                    }
                    user.setProfilePictureUrl(null);
                    return userRepository.save(user)
                            // Le FICHIER, et pas seulement l'URL. Effacer l'URL
                            // sans supprimer le fichier laisse une photographie
                            // d'identite accessible a qui connait le chemin.
                            .then(supprimerLeFichier(url))
                            .then(cache.invalider(userId, user.getAuthUserId()))
                            .doOnSuccess(rien -> log.info(
                                    "Photo de profil retiree du compte {}", userId));
                });
    }

    /**
     * Supprime le fichier, sur {@code boundedElastic}.
     *
     * <p>{@code deleteProfilePicture} fait des entrees-sorties bloquantes :
     * l'executer sur le fil de l'event loop immobiliserait une ressource que
     * WebFlux n'a qu'en quelques exemplaires.
     *
     * <p>Son echec n'interrompt pas l'operation : la base est deja a jour, et
     * mieux vaut un fichier orphelin signale qu'un retrait a moitie fait. Le
     * stockage journalise lui-meme un fichier introuvable.
     */
    private Mono<Void> supprimerLeFichier(String url) {
        return Mono.fromRunnable(() -> fileStorageService.deleteProfilePicture(url))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(erreur -> {
                    log.warn("Fichier de photo non supprime ({}) : {}", url, erreur.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
