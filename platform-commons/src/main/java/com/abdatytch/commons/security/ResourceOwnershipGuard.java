package com.abdatytch.commons.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifie qu'un appelant agit bien sur ses propres donnees.
 *
 * <h2>Le defaut que cette classe evite</h2>
 *
 * Un endpoint dont l'identifiant vient du chemin — {@code /quotas/{userId}},
 * {@code /settings/users/{userId}/{key}} — ne protege rien avec
 * {@code authenticated()} ni meme avec {@code hasRole('USER')}. Tout porteur d'un
 * jeton valide peut y substituer l'identifiant de quelqu'un d'autre.
 *
 * <p>Ce schema a deja ete trouve quatre fois dans ce depot : deux fois dans
 * user-service (profil et fichiers), puis dans quotas-service et setting-service.
 * Chaque fois avec la meme cause — un role confondu avec une autorisation — et
 * chaque fois corrige localement. D'ou cette classe : la regle vit maintenant a
 * un seul endroit.
 *
 * <h2>Pourquoi 404 et non 403</h2>
 *
 * Un 403 confirme que la ressource existe. Sur une suite d'identifiants, cela
 * permet d'enumerer les comptes sans jamais en lire le contenu. Le refus est donc
 * indistinguable d'une ressource absente.
 *
 * <h2>Ce que cette classe ne fait pas</h2>
 *
 * Elle ne remplace pas une verification d'appartenance sur une ressource dont
 * l'identifiant n'est pas celui du porteur — un document, un projet. Dans ce cas
 * il faut charger la ressource et comparer son proprietaire, comme le fait
 * {@code DocumentServiceImpl}. Ici, l'identifiant du chemin EST celui du porteur.
 */
public final class ResourceOwnershipGuard {

    private static final Logger log = LoggerFactory.getLogger(ResourceOwnershipGuard.class);

    /** Roles autorises a agir sur les donnees d'autrui. */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String ROLE_SERVICE = "ROLE_SERVICE";

    private ResourceOwnershipGuard() {
    }

    /**
     * Laisse passer si l'appelant est le porteur designe, ou s'il dispose d'un
     * role l'autorisant a agir pour autrui.
     *
     * @param targetUserId identifiant issu de la requete
     * @param refus        erreur a emettre en cas de refus — de preference une
     *                     erreur traduite en 404, jamais en 403
     * @return un {@code Mono} qui complete si l'acces est legitime, et qui emet
     *         {@code refus} sinon
     */
    public static Mono<Void> requireSelfOrPrivileged(String targetUserId, Throwable refus) {
        // La decision passe par un BOOLEEN, jamais par la vacuite d'un Mono<Void>.
        //
        // Une premiere version faisait :
        //     .flatMap(auth -> autorise ? Mono.<Void>empty() : Mono.error(refus))
        //     .switchIfEmpty(Mono.error(refus))
        // Un Mono<Void> etant toujours vide, `switchIfEmpty` se declenchait aussi
        // dans le cas AUTORISE : la garde refusait tout, y compris au porteur
        // legitime. Le piege est le meme que celui deja rencontre dans
        // SuperAdminSeeder et dans le filtre de geolocalisation.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(authentication -> {
                    boolean autorise = estAutorise(authentication, targetUserId);
                    if (!autorise) {
                        // Journalise la tentative : c'est le signal qui permet de
                        // reperer un balayage d'identifiants. Le refus reste
                        // indistinguable d'une ressource absente pour l'appelant.
                        log.warn("Acces refuse : {} a tente d'agir sur les donnees de {}",
                                authentication.getName(), targetUserId);
                    }
                    return autorise;
                })
                // Aucun contexte de securite : la requete n'est pas authentifiee.
                // Refuser, plutot que de laisser passer faute d'information.
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(autorise -> Boolean.TRUE.equals(autorise)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(refus));
    }

    /**
     * Variante pour une chaine deja porteuse d'une valeur : la verification
     * precede l'acces aux donnees.
     */
    public static <T> Mono<T> guard(String targetUserId, Throwable refus, Mono<T> suite) {
        // `Mono.defer` : sans lui, `suite` serait evalue a l'assemblage, donc
        // AVANT la verification. La requete partirait en base alors que l'acces
        // est en train d'etre refuse.
        return requireSelfOrPrivileged(targetUserId, refus).then(Mono.defer(() -> suite));
    }

    /** Variante pour un flux. */
    public static <T> Flux<T> guardMany(String targetUserId, Throwable refus, Flux<T> suite) {
        return requireSelfOrPrivileged(targetUserId, refus)
                .thenMany(Flux.defer(() -> suite));
    }

    private static boolean estAutorise(Authentication authentication, String targetUserId) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (targetUserId != null && targetUserId.equals(authentication.getName())) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> ROLE_ADMIN.equals(a)
                        || ROLE_SUPER_ADMIN.equals(a)
                        || ROLE_SERVICE.equals(a));
    }

}
