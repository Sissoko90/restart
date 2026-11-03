package com.abdatytch.user_service.service.impl;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.event.OAuth2ProfileCompletedEvent;
import com.abdatytch.user_service.exception.ResourceNotFoundException;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.UserProfileSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Synchronisation du profil depuis les événements d'auth-service.
 *
 * <p>Voir {@link UserProfileSyncService} pour la raison d'un service distinct.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileSyncServiceImpl implements UserProfileSyncService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final CacheDesComptes cache;

    @Override
    public Mono<Void> appliquerCompletionOAuth2(OAuth2ProfileCompletedEvent evenement) {
        String authUserId = evenement.getAuthUserId();

        return userRepository.findByAuthUserId(authUserId)
                // Pas de `defaultIfEmpty` : un compte absent doit faire ECHOUER
                // le traitement, pour que Kafka repropose l'evenement. S'en
                // accommoder silencieusement perdrait le profil que la personne
                // vient de saisir.
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException(
                        "Compte introuvable pour la completion OAuth2 : " + authUserId)))
                .flatMap(compte -> majDuCompte(compte, evenement))
                .flatMap(compte -> majDuProfil(compte, evenement))
                .flatMap(compte -> cache.invaliderPuisRendre(
                        compte.getId(), compte.getAuthUserId(), compte))
                .doOnSuccess(compte -> log.info(
                        "Profil OAuth2 synchronise pour le compte {}", authUserId))
                .then();
    }

    /**
     * Champs portés par la ligne {@code users}.
     *
     * <p>L'enregistrement est évité quand rien ne change : un événement rejoué
     * ne doit pas produire d'écriture, ni faire avancer {@code updated_at}, ni
     * invalider un cache pour rien.
     */
    private Mono<User> majDuCompte(User compte, OAuth2ProfileCompletedEvent evenement) {
        boolean modifie = false;

        if (evenement.getDateOfBirth() != null
                && !evenement.getDateOfBirth().equals(compte.getDateOfBirth())) {
            compte.setDateOfBirth(evenement.getDateOfBirth());
            modifie = true;
        }
        // La photo venue du fournisseur OAuth2 ne remplace PAS celle que la
        // personne aurait envoyee depuis : elle ne sert qu'a garnir un profil
        // encore vide.
        if (evenement.getProfilePicture() != null
                && (compte.getProfilePictureUrl() == null
                    || compte.getProfilePictureUrl().isBlank())) {
            compte.setProfilePictureUrl(evenement.getProfilePicture());
            modifie = true;
        }

        return modifie ? userRepository.save(compte) : Mono.just(compte);
    }

    private Mono<User> majDuProfil(User compte, OAuth2ProfileCompletedEvent evenement) {
        boolean rienAEcrire = evenement.getAddress() == null && evenement.getBio() == null;
        if (rienAEcrire) {
            return Mono.just(compte);
        }

        return profileRepository.findByUserId(compte.getId())
                .switchIfEmpty(Mono.defer(() -> {
                    UserProfile nouveau = new UserProfile();
                    nouveau.setUserId(compte.getId());
                    return Mono.just(nouveau);
                }))
                .flatMap(profil -> {
                    boolean modifie = false;
                    // Un champ nul de l'evenement n'efface pas l'existant : une
                    // completion complete, elle ne remplace pas.
                    if (evenement.getAddress() != null
                            && !evenement.getAddress().equals(profil.getAddress())) {
                        profil.setAddress(evenement.getAddress());
                        modifie = true;
                    }
                    if (evenement.getBio() != null
                            && !evenement.getBio().equals(profil.getBio())) {
                        profil.setBio(evenement.getBio());
                        modifie = true;
                    }
                    return modifie ? profileRepository.save(profil).thenReturn(compte)
                            : Mono.just(compte);
                });
    }
}
