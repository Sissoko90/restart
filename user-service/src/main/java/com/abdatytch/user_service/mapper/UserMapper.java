package com.abdatytch.user_service.mapper;

import com.abdatytch.user_service.client.ClientIdentiteAuth;
import com.abdatytch.user_service.constants.MapperConstants;
import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.dto.response.AuthUserDTO;
import com.abdatytch.user_service.dto.response.UserDetailDTO;
import com.abdatytch.user_service.dto.response.UserResponseDTO;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Compose une réponse utilisateur à partir des trois sources qui la portent.
 *
 * <h2>Pourquoi les méthodes rendent des {@code Mono}</h2>
 *
 * Elles rendaient l'objet directement, et appelaient donc <b>Feign en
 * bloquant</b> — depuis l'intérieur d'un {@code .map(userMapper::toDetailDTO)},
 * c'est-à-dire sur le fil d'exécution de l'event loop, sur le chemin de
 * <b>chaque</b> réponse utilisateur.
 *
 * <p>La conséquence tenait en une ligne de code et se payait partout : lister
 * vingt comptes faisait vingt appels HTTP bloquants l'un après l'autre, sur un
 * fil que WebFlux n'a qu'en quelques exemplaires. Une instance d'auth-service
 * lente immobilisait le service entier, pas seulement la requête en cours.
 *
 * <p>Rien ne le signalait : la politique non bloquante du projet est vérifiée par
 * BlockHound, mais les tests de ce service <b>simulent le mapper</b> — le seul
 * endroit où l'appel a lieu n'était jamais exécuté sous surveillance.
 *
 * <p>L'appel passe désormais par {@link ClientIdentiteAuth}, qui emploie
 * {@code WebClient} : il n'y a plus de blocage à déplacer. L'étape intermédiaire
 * — Feign isolé sur {@code boundedElastic} — était correcte mais ne faisait que
 * déporter l'immobilisation sur un autre fil ; lister vingt comptes en retenait
 * huit à la fois pour attendre le réseau.
 *
 * <h2>Le profil était toujours absent de la réponse</h2>
 *
 * {@code toDetailDTO} posait {@code UserProfile profile = null} puis renseignait
 * chaque champ par {@code profile != null ? ... : null} — une condition dont
 * l'issue était écrite d'avance. Biographie, adresse, ville, pays, code postal
 * et préférences étaient donc <b>systématiquement vides</b> dans toutes les
 * réponses, y compris juste après une modification qui venait de les écrire.
 *
 * <p>Le mapper charge désormais le profil lui-même : c'est la seule place qui
 * sache quels champs la réponse réclame, et la faire renseigner par chaque
 * appelant aurait recréé la même omission à huit endroits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserMapper {

    /**
     * Nombre d'enrichissements menés de front sur une liste.
     *
     * <p>Une liste de comptes demande un appel à auth-service par ligne, et
     * {@code flatMapSequential} les mènerait tous en parallèle sans borne : une
     * page de deux cents comptes ouvrirait deux cents requêtes d'un coup et
     * saturerait le pool de connexions. Huit à la fois garde le gain du
     * parallélisme sans transformer une lecture en rafale.
     */
    private static final int ENRICHISSEMENTS_SIMULTANES = 8;

    private final ClientIdentiteAuth clientIdentiteAuth;
    private final UserProfileRepository userProfileRepository;

    /**
     * Entité à créer, à partir des champs que ce service <b>détient</b>.
     *
     * <p>Seul {@code authUserId} était repris : la date de naissance et l'URL de
     * photo fournies par l'appelant de {@code POST /internal/users} étaient
     * acceptées puis <b>jetées</b>. Un appelant qui fournissait une date de
     * naissance recevait un 201 et un profil vide.
     *
     * <p>Le courriel, le numéro, le nom et le rôle restent ignorés, et
     * volontairement : auth-service en est la source de vérité, et les recopier
     * ferait un second magasin qui divergerait dès la première modification.
     */
    public User toEntity(UserCreationDTO dto) {
        User user = new User();
        user.setAuthUserId(dto.authUserId());
        user.setDateOfBirth(dto.dateOfBirth());
        user.setProfilePictureUrl(dto.profilePicture());
        return user;
    }

    /**
     * Réponse détaillée : état local, identité d'auth-service, profil déclaratif.
     *
     * <p>Les deux lectures distantes sont menées <b>de front</b> par
     * {@code zip} : elles ne dépendent pas l'une de l'autre, et les enchaîner
     * aurait additionné leurs latences sans raison.
     */
    public Mono<UserDetailDTO> toDetailDTO(User user) {
        return Mono.zip(identite(user.getAuthUserId()), profil(user.getId()))
                .map(sources -> composer(user, sources.getT1(), sources.getT2().orElse(null)));
    }

    /**
     * Enrichit une liste de comptes : <b>une</b> requête de profils, pas une par
     * ligne.
     *
     * <h3>Le N+1 que cette méthode ferme</h3>
     *
     * Elle appelait {@link #toDetailDTO(User)} par compte, et chaque appel
     * chargeait son profil séparément : une page de vingt comptes faisait vingt
     * requêtes sur {@code user_profiles}, une page de cent en faisait cent. Le
     * coût d'une page se comptait donc en allers-retours, et chacun prend une
     * connexion du pool.
     *
     * <p>Désormais la page est collectée, les profils chargés d'un coup par
     * {@code findAllByUserIdIn}, puis chaque réponse composée depuis cette
     * carte. Vingt requêtes deviennent une.
     *
     * <h3>Ce que cela coûte, et pourquoi c'est acceptable</h3>
     *
     * {@code collectList} rend la lecture <b>non ruisselante</b> : la page
     * entière est en mémoire avant la première réponse. C'est le compromis, et
     * il tient parce que la page est déjà bornée par le {@code LIMIT} du dépôt —
     * jamais par le volume de la table. Sur un flux non borné ce serait une
     * fuite mémoire ; ici la taille est celle demandée par l'appelant.
     *
     * <h3>L'identité reste un appel par compte</h3>
     *
     * auth-service n'expose pas de lecture par lot, et je n'en ai pas ajouté
     * une : ce serait une API de plus entre deux services pour un besoin que la
     * concurrence bornée couvre. Les appels partent donc par
     * {@value #ENRICHISSEMENTS_SIMULTANES} de front, et
     * {@code flatMapSequential} préserve l'ordre du tri SQL — une liste paginée
     * qui se réordonne est inutilisable. {@code concatMap} les aurait
     * sérialisés.
     */
    public Flux<UserDetailDTO> toDetailDTOs(Flux<User> comptes) {
        return comptes.collectList()
                .flatMapMany(page -> {
                    if (page.isEmpty()) {
                        return Flux.empty();
                    }
                    return profilsDe(page).flatMapMany(profils ->
                            Flux.fromIterable(page)
                                    .flatMapSequential(compte -> identite(compte.getAuthUserId())
                                                    .map(authUser -> composer(compte, authUser,
                                                            profils.get(compte.getId()))),
                                            ENRICHISSEMENTS_SIMULTANES));
                });
    }

    /**
     * Profils de la page, indexés par identifiant de compte.
     *
     * <p>Une carte et non une liste alignée sur la page : la base ne rend que
     * les profils <b>existants</b>, et rien ne garantit ni leur nombre ni leur
     * ordre. Un compte sans profil est simplement absent, et {@code composer}
     * sait traiter un profil nul.
     */
    private Mono<Map<UUID, UserProfile>> profilsDe(List<User> page) {
        List<UUID> identifiants = page.stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .toList();
        if (identifiants.isEmpty()) {
            return Mono.just(Map.of());
        }
        return userProfileRepository.findAllByUserIdIn(identifiants)
                .collectMap(UserProfile::getUserId)
                .onErrorResume(erreur -> {
                    // Meme repli que la lecture unitaire : un profil illisible
                    // ne doit pas faire disparaitre la liste entiere. Les champs
                    // declaratifs seront nuls, et l'etat administratif — qui
                    // vient de ce service — reste exact.
                    log.error("Lecture des profils de la page impossible : {}",
                            erreur.getMessage());
                    return Mono.just(Map.of());
                });
    }

    /** Réponse courte : pas de profil déclaratif, donc pas de lecture locale. */
    public Mono<UserResponseDTO> toResponseDTO(User user) {
        return identite(user.getAuthUserId())
                .map(authUser -> new UserResponseDTO(
                        user.getId(),
                        user.getAuthUserId(),
                        authUser.email(),
                        authUser.phoneNumber(),
                        authUser.firstName(),
                        authUser.lastName(),
                        user.getStatus(),
                        authUser.role(),
                        user.getCreatedAt(),
                        authUser.lastLogin()));
    }

    private UserDetailDTO composer(User user, AuthUserDTO authUser, UserProfile profil) {
        return new UserDetailDTO(
                user.getId(),
                user.getAuthUserId(),
                authUser.email(),
                authUser.phoneNumber(),
                authUser.countryCode(),
                authUser.firstName(),
                authUser.lastName(),
                user.getDateOfBirth(),
                user.getProfilePictureUrl(),
                user.getStatus(),
                authUser.role(),
                profil != null ? profil.getBio() : null,
                profil != null ? profil.getAddress() : null,
                profil != null ? profil.getCity() : null,
                profil != null ? profil.getCountry() : null,
                profil != null ? profil.getPostalCode() : null,
                profil != null ? profil.getPreferences() : Collections.emptyMap(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                authUser.lastLogin());
    }

    /**
     * Profil déclaratif du compte, s'il en a un.
     *
     * <p>Rendu dans un {@code Optional} : {@code zip} ignore une source vide, et
     * un compte sans profil aurait alors fait disparaître la réponse entière —
     * l'appelant aurait vu un 404 pour un compte qui existe. C'est le piège du
     * {@code Mono} vide, dont ce projet a déjà payé plusieurs variantes.
     */
    private Mono<java.util.Optional<UserProfile>> profil(UUID userId) {
        if (userId == null) {
            return Mono.just(java.util.Optional.empty());
        }
        return userProfileRepository.findByUserId(userId)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .onErrorResume(erreur -> {
                    log.error("Lecture du profil impossible pour {} : {}",
                            userId, erreur.getMessage());
                    return Mono.just(java.util.Optional.empty());
                });
    }

    /**
     * Identité détenue par auth-service.
     *
     * <p>Le repli reste <b>ici</b>, et non dans {@link ClientIdentiteAuth} : le
     * client propage ses erreurs, parce que son autre appelant — la propagation
     * de fermeture de compte — doit impérativement échouer. Décider qu'une
     * identité manquante se traduit par des champs nuls plutôt que par une
     * réponse perdue est une décision de composition, et elle appartient à cette
     * classe.
     *
     * <p>Conséquence utile : un échec n'entre jamais dans le cache du client,
     * puisque le client n'écrit qu'en aval d'une réponse obtenue.
     */
    private Mono<AuthUserDTO> identite(String authUserId) {
        return clientIdentiteAuth.identite(authUserId)
                .onErrorResume(erreur -> {
                    log.error(MapperConstants.LOG_ERROR_AUTH_FETCH_FAILED, authUserId);
                    log.error("Cause : {} — {}",
                            erreur.getClass().getSimpleName(), erreur.getMessage());
                    return Mono.just(identiteIndisponible(authUserId));
                });
    }

    /**
     * Identité de repli quand auth-service ne répond pas.
     *
     * <p>Les champs sont <b>nuls</b>, et non remplis de valeurs d'apparence
     * plausible. Le repli posait {@code unknown@email.com} et {@code Unknown
     * User} : une adresse syntaxiquement valide et un nom lisible, que rien ne
     * distinguait d'une vraie donnée. Un administrateur consultant une liste
     * pendant une panne d'auth-service y lisait des identités fausses sans le
     * savoir, et un export les aurait figées.
     *
     * <p>Un champ nul se voit. C'est tout l'écart entre « je ne sais pas » et une
     * réponse inventée.
     *
     * <p>Le repli lui-même est conservé : propager l'erreur ferait échouer la
     * liste entière parce qu'un seul compte n'a pas pu être enrichi, et l'état
     * administratif — bloqué, suspendu, révoqué — vient de ce service et reste
     * exact.
     */
    private static AuthUserDTO identiteIndisponible(String authUserId) {
        return new AuthUserDTO(
                authUserId,
                null, null, null, null, null,
                null, false, false, false, null, null);
    }
}
