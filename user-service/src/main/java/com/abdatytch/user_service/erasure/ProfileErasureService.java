package com.abdatytch.user_service.erasure;

import com.abdatytch.user_service.cache.CacheDesComptes;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.repository.AuditLogRepository;
import com.abdatytch.user_service.repository.UserProfileRepository;
import com.abdatytch.user_service.repository.UserRepository;
import com.abdatytch.user_service.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.UUID;

/**

 * Efface les données personnelles détenues par user-service.
 *
 * <p><b>Quatre</b> emplacements, souvent oubliés séparément :
 * <ol>
 * <li>la ligne {@code users} — date de naissance, URL de la photo ;</li>
 * <li>la ligne {@code user_profiles} — biographie, adresse, ville, pays,
 * code postal, préférences ;</li>
 * <li><b>le fichier de la photo sur le disque</b>. Effacer l'URL en base sans
 * supprimer le fichier laisse une photographie d'identité accessible à
 * quiconque connaît le chemin. C'est l'oubli le plus courant : la donnée
 * n'est plus dans la base, donc on la croit partie.</li>
 * <li><b>l'entrée de cache Redis</b>, qui porte le profil déjà composé et
 * survivait un quart d'heure à l'effacement — voir le champ {@code cache}.</li>
 * </ol>
 *
 * <p>La ligne {@code users} est conservée, vidée de ses champs identifiants :
 * les journaux d'audit y font référence, et leur suppression en cascade
 * effacerait la trace des actions d'administration — dont celle de l'effacement
 * lui-même.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileErasureService {

    private final UserRepository userRepository;
    private final UserProfileRepository profileRepository;
    private final AuditLogRepository auditLogRepository;
    private final FileStorageService fileStorageService;

    /**
     * Quatrième emplacement, et il manquait : <b>le cache</b>.
     *
     * <p>Un compte est mis en cache un quart d'heure, sous la forme du
     * {@code UserDetailDTO} rendu par l'API — biographie, adresse, ville, pays,
     * préférences comprises. L'effacement vidait les trois emplacements
     * ci-dessus et laissait cette copie intacte : la base disait que la donnée
     * était partie, et l'API continuait de la servir.
     *
     * <p>C'est la même nature d'oubli que le fichier sur le disque, et elle se
     * raconte pareil : la donnée n'est plus dans la base, donc on la croit
     * partie.
     */
    private final CacheDesComptes cache;

    /**

     * @param authUserId identifiant du porteur, tel que connu d'auth-service
     * @return le nombre d'enregistrements effacés
     */
    public Mono<Integer> erase(String authUserId) {
        return userRepository.findByAuthUserId(authUserId)
                .flatMap(this::eraseUserAndProfile)
                .defaultIfEmpty(0)
                .doOnSuccess(n -> log.info("Effacement du profil {} — {} enregistrement(s)",
                        authUserId, n));
    }

    private Mono<Integer> eraseUserAndProfile(User user) {
        String pictureUrl = user.getProfilePictureUrl();
        UUID userId = user.getId();

        user.setDateOfBirth(null);
        user.setProfilePictureUrl(null);
        user.setBlockedReason(null);
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(LocalDateTime.now());

        return userRepository.save(user)
                .then(supprimerFichier(pictureUrl))
                .then(effacerProfil(userId))
                .map(profilsEfaces -> 1 + profilsEfaces)
                .flatMap(total -> anonymiserJournaux(userId).map(j -> total + j))
                // Le cache en DERNIER : l'invalider avant d'avoir fini
                // laisserait une lecture concurrente reconstituer l'entree
                // depuis une base encore a moitie effacee.
                .flatMap(total -> cache.invaliderPuisRendre(
                        userId, user.getAuthUserId(), total));
    }

    private Mono<Integer> effacerProfil(UUID userId) {
        return profileRepository.findByUserId(userId)
                .flatMap(this::viderProfil)
                .map(saved -> 1)
                .defaultIfEmpty(0);
    }

    private Mono<UserProfile> viderProfil(UserProfile profile) {
        profile.setBio(null);
        profile.setAddress(null);
        profile.setCity(null);
        profile.setCountry(null);
        profile.setPostalCode(null);
        // Les préférences peuvent contenir n'importe quoi, y compris des
        // données saisies librement : on les vide entièrement plutôt que de
        // tenter d'y faire le tri.
        profile.setPreferences(new java.util.HashMap<>());
        return profileRepository.save(profile);
    }

    /**

     * Supprime la photographie du disque.
     *
     * <p>L'opération est bloquante : elle est isolée sur {@code boundedElastic}
     * pour ne pas immobiliser un thread de l'EventLoop.
     */
    private Mono<Void> supprimerFichier(String pictureUrl) {
        if (pictureUrl == null || pictureUrl.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> fileStorageService.deleteProfilePicture(pictureUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    // Un fichier introuvable n'est pas un échec : c'est le
                    // résultat recherché. En revanche il faut le savoir, car
                    // un échec réel laisserait la photographie accessible.
                    log.warn("Photographie non supprimée ({}) : {}", pictureUrl, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**

     * Retire le détail des journaux d'audit sans supprimer les lignes.
     *
     * <p>La trace « telle action a eu lieu à telle date » garde une valeur pour
     * l'audit interne ; le contenu libre du champ de détail, lui, peut porter des
     * données personnelles.
     */
    private Mono<Integer> anonymiserJournaux(UUID userId) {
        // UNE requete, et non une lecture suivie d'un enregistrement par ligne.
        //
        // La version precedente faisait un UPDATE par entree de journal. Le
        // journal d'un compte ancien en compte beaucoup, et un effacement RGPD
        // a un delai legal a respecter.
        return auditLogRepository.anonymiserLeDetailParUserId(userId)
                .defaultIfEmpty(0);
    }
}
