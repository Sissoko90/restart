package com.abdatytch.user_service.cache;

import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.metrics.UserMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

/**
 * Détenteur unique du cache des comptes : les clés, la rétention, l'invalidation.
 *
 * <h2>Pourquoi une classe pour ça</h2>
 *
 * Un compte est mis en cache sous <b>deux</b> clés — {@code user:<uuid>} pour la
 * lecture par identifiant, {@code user:auth_<authUserId>} pour
 * {@code GET /users/me}. Le préfixe était recopié dans chaque service qui écrit,
 * et <b>chacun invalidait ce qu'il voulait</b> :
 *
 * <ul>
 *   <li>{@code UserServiceReactiveImpl} et {@code UserAdminServiceImpl}
 *       n'effaçaient que la clé par identifiant. Celle que sert
 *       {@code /users/me} — donc la plus consultée — survivait un quart d'heure :
 *       un compte fraîchement bloqué continuait de se voir actif, et une
 *       modification de profil paraissait perdue.</li>
 *   <li>{@code UserProfileUpdateServiceImpl} n'invalidait <b>rien</b>. Après
 *       l'envoi d'une photo, {@code GET /users/me/profile-picture} rendait
 *       l'ancienne URL — ou aucune — pendant quinze minutes.</li>
 *   <li>{@code ProfileErasureService} n'invalidait rien non plus, et c'est le
 *       plus grave : après un effacement RGPD, la copie en cache gardait
 *       biographie, adresse, ville, pays et préférences. La base disait que la
 *       donnée était partie ; l'API continuait de la servir.</li>
 * </ul>
 *
 * <p>Quatre écrivains, une convention recopiée, deux qui l'ignorent : le défaut
 * n'est pas qu'un oubli ait eu lieu, c'est qu'il ait <b>pu</b> avoir lieu. Une
 * convention de clé qui vit à quatre endroits finit par diverger.
 *
 * <h2>Une panne de Redis ne doit pas faire échouer une écriture</h2>
 *
 * L'invalidation est le dernier maillon d'une mutation déjà enregistrée en base.
 * Propager son erreur ferait répondre « échec » à un appelant dont la
 * modification a abouti, et l'inciterait à recommencer. L'échec est journalisé
 * en WARN : la conséquence réelle est une donnée périmée pendant la durée de
 * rétention, pas une perte.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheDesComptes {

    private static final String PREFIXE = "user:";
    private static final String PREFIXE_AUTH = PREFIXE + "auth_";

    /**
     * Durée de rétention d'une entrée, et seule autorité sur le sujet.
     *
     * <p>Le commentaire précédent la disait « alignée sur
     * {@code spring.cache.redis.time-to-live} de {@code user-service-dev.yml} ».
     * C'était trompeur : cette propriété configure le {@code CacheManager} de
     * l'abstraction Spring Cache, et ce module n'en a pas — ni
     * {@code @EnableCaching}, ni {@code @Cacheable}, ni {@code CacheManager}. Il
     * passe directement par {@code ReactiveRedisTemplate}. La propriété était
     * donc <b>inerte</b>, et quiconque l'aurait ajustée en attendant un effet
     * n'en aurait obtenu aucun ; elle a été retirée du fichier de configuration.
     *
     * <p>Un quart d'heure. C'est aussi la durée pendant laquelle une
     * invalidation manquée reste visible — la raison pour laquelle elle n'est
     * pas optionnelle.
     */
    private static final Duration RETENTION = Duration.ofMinutes(15);

    private final ReactiveRedisTemplate<String, Object> redis;
    private final UserMetrics userMetrics;

    public String cleParIdentifiant(UUID id) {
        return PREFIXE + id;
    }

    public String cleParAuthUserId(String authUserId) {
        return PREFIXE_AUTH + authUserId;
    }

    /**
     * Lecture comptée.
     *
     * <p>{@code doOnNext} pour le succès et {@code switchIfEmpty} pour
     * l'absence : {@code doOnSuccess} s'exécute <b>aussi</b> sur une complétion
     * vide, avec {@code null}, et compterait donc un succès là où il n'y a rien.
     *
     * <p>Une panne de Redis compte un manque et laisse passer : un cache
     * indisponible doit ralentir le service, pas le faire échouer.
     */
    public Mono<UserDetailDTO> lire(String cle) {
        return redis.opsForValue().get(cle)
                .cast(UserDetailDTO.class)
                .doOnNext(trouve -> userMetrics.lectureServieParLeCache())
                .switchIfEmpty(Mono.<UserDetailDTO>fromRunnable(
                        userMetrics::lectureAbsenteDuCache))
                .onErrorResume(erreur -> {
                    log.warn("Cache illisible pour {} : {}", cle, erreur.getMessage());
                    userMetrics.lectureAbsenteDuCache();
                    return Mono.empty();
                });
    }

    /** Écriture : un échec ne doit pas perdre la réponse déjà calculée. */
    public Mono<UserDetailDTO> ecrire(String cle, UserDetailDTO detail) {
        return redis.opsForValue().set(cle, detail, RETENTION)
                .onErrorResume(erreur -> {
                    log.warn("Cache non ecrit pour {} : {}", cle, erreur.getMessage());
                    return Mono.just(false);
                })
                .thenReturn(detail);
    }

    /**
     * Invalide les <b>deux</b> entrées d'un compte.
     *
     * <p>C'est la seule méthode d'invalidation du service, et c'est tout
     * l'intérêt : il n'est plus possible d'en oublier une.
     *
     * @param authUserId peut être nul — l'appelant n'a pas toujours l'entité
     *                   sous la main ; seule la clé par identifiant est alors
     *                   effacée, ce qui est mieux que rien et visible ici
     */
    public Mono<Void> invalider(UUID id, String authUserId) {
        String[] cles = authUserId == null
                ? new String[] {cleParIdentifiant(id)}
                : new String[] {cleParIdentifiant(id), cleParAuthUserId(authUserId)};

        return redis.delete(cles)
                .onErrorResume(erreur -> {
                    log.warn("Invalidation du cache du compte {} impossible : {}",
                            id, erreur.getMessage());
                    return Mono.just(0L);
                })
                .then();
    }

    /**
     * Invalide, puis rend la valeur passée.
     *
     * <p>Confort d'enchaînement : les appelants invalident au milieu d'une
     * chaîne dont ils veulent conserver l'élément courant.
     */
    public <T> Mono<T> invaliderPuisRendre(UUID id, String authUserId, T valeur) {
        return invalider(id, authUserId).thenReturn(valeur);
    }
}
