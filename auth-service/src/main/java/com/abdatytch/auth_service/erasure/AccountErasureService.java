package com.abdatytch.auth_service.erasure;

import com.abdatytch.auth_service.entities.ErasureRequest;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.repository.*;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent;
import com.abdatytch.auth_service.dto.response.EtatEffacementResponse;
import com.abdatytch.auth_service.repository.ErasureAcknowledgementRepository;
import com.abdatytch.auth_service.service.IAccountErasureService;
import com.abdatytch.commons.erasure.UserErasureRequestedEvent.ErasureReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**

 * Effacement des données personnelles détenues par auth-service, et
 * déclenchement de l'effacement dans les autres services.
 *
 * <h2>Effacer n'est pas supprimer la ligne</h2>
 *
 * Supprimer la ligne {@code users} briserait les références des journaux
 * d'audit, des affectations de rôles et des écritures de facturation. La
 * transaction échouerait, ou pire, laisserait la base incohérente — et
 * l'effacement n'aurait pas lieu.
 *
 * <p>La règle appliquée : <b>écraser les champs identifiants, conserver
 * l'identifiant technique</b>. Une fois le courriel, le téléphone, le nom et
 * l'empreinte du mot de passe écrasés, l'identifiant ne renvoie plus à personne :
 * aucune table ne conserve la correspondance. C'est un effacement au sens de
 * l'article 17, pas une pseudonymisation — une pseudonymisation suppose une table
 * de correspondance conservée quelque part, ce qui n'est pas le cas ici.
 *
 * <h2>Ce qui est supprimé pour de bon</h2>
 *
 * Les jetons, codes de vérification, identifiants biométriques et comptes OAuth
 * ne sont référencés par rien : ils sont réellement supprimés. Les journaux
 * d'authentification sont anonymisés plutôt que supprimés — ils portent une
 * valeur antifraude légitime une fois détachés de la personne.
 */
@Service
@Slf4j
public class AccountErasureService implements IAccountErasureService {

    /**

     * Valeur écrite à la place des champs identifiants. Une chaîne fixe et
     * reconnaissable vaut mieux que {@code null} : elle rend l'effacement
     * visible lors d'une inspection de la base, là où un {@code null} se
     * confond avec une donnée jamais renseignée.
     */
    private static final String EFFACE = "[effacé]";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationCodeRepository verificationCodeRepository;
    private final BiometricCredentialRepository biometricCredentialRepository;
    private final OAuthAccountRepository oAuthAccountRepository;

    /**
     * Appareils de confiance.
     *
     * <p>Ajoutés à l'effacement en même temps que la table : une table de
     * données personnelles créée sans participer à l'effacement survit à la
     * suppression du compte, et personne ne le remarque — l'effacement rend un
     * succès, et le compte disparaît des listes. Ce dépôt a un écart RGPD
     * documenté, il n'a pas besoin d'un second.
     *
     * <p>Ce qu'elles portent de personnel : adresse IP, navigateur, système,
     * pays. De quoi reconstituer les habitudes de connexion d'une personne.
     */
    private final com.abdatytch.auth_service.repository.AppareilDeConfianceRepository
            appareilDeConfianceRepository;

    /**
     * Codes de secours du second facteur.
     *
     * <p>Ajoutés à l'effacement en même temps que la table, pour la raison dite
     * juste au-dessus.
     *
     * <p>Ce qu'ils portent de personnel : l'adresse IP d'où chaque code a été
     * consommé, et la date. Moins qu'un appareil de confiance, mais de même
     * nature — et ils restent surtout des clefs valides, ce qui suffit à exiger
     * leur suppression.
     */
    private final com.abdatytch.auth_service.repository.CodeDeSecoursRepository
            codeDeSecoursRepository;
    private final AuthenticationLogRepository authenticationLogRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final ErasureRequestRepository erasureRequestRepository;
    private final ErasureAcknowledgementRepository erasureAcknowledgementRepository;
    private final ErasurePublisher erasurePublisher;

    /**
     * Les fournisseurs sociaux, pour les prevenir avant d'effacer la liaison.
     *
     * <p>Apple <b>exige</b> cet appel : une application distribuee sur l'App
     * Store qui propose Sign in with Apple et la suppression de compte doit
     * appeler son point de revocation, et Apple teste le parcours en revue.
     * Sans lui, l'autorisation reste vive chez Apple alors que la personne a
     * demande la disparition de ses donnees — et la liste « Se connecter avec
     * Apple » de son appareil continue d'afficher notre application.
     */
    private final com.abdatytch.auth_service.service.oauth2.RegistreDesFournisseursOAuth2
            registreDesFournisseurs;

    public AccountErasureService(UserRepository userRepository,
                                 RefreshTokenRepository refreshTokenRepository,
                                 VerificationCodeRepository verificationCodeRepository,
                                 BiometricCredentialRepository biometricCredentialRepository,
                                 OAuthAccountRepository oAuthAccountRepository,
                                 com.abdatytch.auth_service.repository
                                         .AppareilDeConfianceRepository
                                         appareilDeConfianceRepository,
                                 com.abdatytch.auth_service.repository
                                         .CodeDeSecoursRepository
                                         codeDeSecoursRepository,
                                 AuthenticationLogRepository authenticationLogRepository,
                                 UserRoleAssignmentRepository roleAssignmentRepository,
                                 ErasureRequestRepository erasureRequestRepository,
                                 ErasureAcknowledgementRepository erasureAcknowledgementRepository,
                                 ErasurePublisher erasurePublisher,
                                 com.abdatytch.auth_service.service.oauth2
                                         .RegistreDesFournisseursOAuth2 registreDesFournisseurs) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.verificationCodeRepository = verificationCodeRepository;
        this.biometricCredentialRepository = biometricCredentialRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.appareilDeConfianceRepository = appareilDeConfianceRepository;
        this.codeDeSecoursRepository = codeDeSecoursRepository;
        this.authenticationLogRepository = authenticationLogRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.erasureRequestRepository = erasureRequestRepository;
        this.erasureAcknowledgementRepository = erasureAcknowledgementRepository;
        this.erasurePublisher = erasurePublisher;
        this.registreDesFournisseurs = registreDesFournisseurs;
    }

    /**

     * Enregistre la demande, efface les données locales, puis prévient les
     * autres services.
     *
     * <p>L'ordre compte. La demande est enregistrée <b>avant</b> tout effacement :
     * si le traitement échoue en cours de route, la trace subsiste et la reprise
     * est possible. Enregistrer après aurait laissé, en cas de panne, des données
     * à moitié effacées sans aucune trace de la demande.
     *
     * @param subjectId   identifiant du porteur
     * @param requestedBy qui déclenche — la personne elle-même, ou un administrateur
     * @return l'identifiant de la demande, à communiquer à la personne
     */
    @Override
    public Mono<UUID> requestErasure(String subjectId, String requestedBy, ErasureReason reason) {
        UUID requestId = UUID.randomUUID();

        ErasureRequest request = new ErasureRequest();
        request.setId(requestId.toString());
        request.setSubjectId(subjectId);
        request.setReason(reason);
        request.setRequestedAt(LocalDateTime.now());
        request.setRequestedBy(requestedBy);

        return erasureRequestRepository.save(request)
                .then(eraseLocalData(subjectId))
                // `eraseLocalData` repose sur un Mono.zip, qui se termine VIDE si
                // l'une de ses six sources l'est. Le flatMap ci-dessous serait
                // alors ignore — donc l'evenement ne serait JAMAIS publie — et le
                // `thenReturn` final emettrait quand meme l'identifiant de
                // demande. Resultat : un effacement rapporte comme reussi, inscrit
                // au registre, dont aucun autre service n'aurait ete prevenu.
                //
                // Les huit sources emettent aujourd'hui par construction
                // (defaultIfEmpty, collectList, count). Faire dependre la
                // diffusion de cette propriete, qu'une seule modification
                // suffirait a rompre sans rien casser visiblement, n'est pas
                // defendable sur le chemin d'une obligation legale.
                .defaultIfEmpty(0)
                .flatMap(effaces -> {
                    log.info(" Effacement local terminé pour {} — {} enregistrement(s)",
                            subjectId, effaces);
                    return erasurePublisher.publishRequested(
                            new UserErasureRequestedEvent(
                                    requestId, subjectId, java.time.Instant.now(), reason));
                })
                .thenReturn(requestId);
    }

    /**

     * Efface les données personnelles détenues par auth-service.
     *
     * @return le nombre d'enregistrements effacés ou anonymisés
     */
    private Mono<Integer> eraseLocalData(String subjectId) {
        return Mono.zip(
                        anonymiserUtilisateur(subjectId),
                        supprimer(refreshTokenRepository.findByUserId(subjectId),
                                refreshTokenRepository::delete),
                        supprimer(biometricCredentialRepository.findByUserId(subjectId),
                                biometricCredentialRepository::delete),
                        effacerLesLiaisonsSociales(subjectId),
                        // Les appareils de confiance sont SUPPRIMES et non
                        // anonymises : leur seule raison d'etre est de
                        // dispenser du second facteur d'un compte qui
                        // n'existera plus. Les garder anonymises laisserait des
                        // jetons valides sans proprietaire.
                        supprimer(appareilDeConfianceRepository.findByUserId(subjectId),
                                appareilDeConfianceRepository::delete),
                        // Les codes de secours sont SUPPRIMES, pour la meme
                        // raison que les appareils de confiance : ils ne
                        // servent qu'a franchir le second facteur d'un compte
                        // qui n'existera plus. Anonymises, ils resteraient des
                        // clefs valides sans proprietaire — et la cle etrangere
                        // ne les emportera pas, puisque la ligne du compte est
                        // anonymisee, non supprimee.
                        supprimer(codeDeSecoursRepository.findByUserId(subjectId),
                                codeDeSecoursRepository::delete),
                        anonymiserJournaux(subjectId),
                        supprimer(roleAssignmentRepository.findByUserId(subjectId),
                                roleAssignmentRepository::delete))
                // La somme DOIT couvrir tous les elements du zip.
                //
                // L'ajout des appareils de confiance a porte le tuple a sept
                // elements, et une somme restee a six aurait compile sans
                // broncher : le nombre rendu a la personne aurait simplement
                // ete faux, en silence. C'est le genre d'ecart qu'un registre
                // de preuve ne doit pas porter — il sert a attester de ce qui a
                // ete efface. Les codes de secours l'ont porte a huit, qui est
                // aussi l'arite MAXIMALE de Mono.zip : le prochain ajout
                // demandera de regrouper, et non d'allonger.
                .map(t -> t.getT1() + t.getT2() + t.getT3() + t.getT4()
                        + t.getT5() + t.getT6() + t.getT7() + t.getT8());
    }

    /**
     * Prévient chaque fournisseur, puis supprime la liaison.
     *
     * <h2>L'ordre n'est pas interchangeable</h2>
     *
     * La révocation a besoin du jeton, qui est <b>dans</b> la ligne qu'on
     * s'apprête à supprimer. Supprimer d'abord rendrait la révocation
     * impossible, et l'autorisation resterait vive chez le fournisseur sans
     * qu'aucune trace ne permette de la retirer plus tard.
     *
     * <h2>Un échec de révocation n'arrête pas l'effacement</h2>
     *
     * La personne a demandé que ses données disparaissent. Une indisponibilité
     * d'Apple ne doit pas l'en empêcher, sinon une panne chez un tiers bloque
     * l'exercice d'un droit. {@code revoquerLAcces} avale donc ses propres
     * erreurs, et la ligne est supprimée dans tous les cas.
     *
     * <p>Ce que cela signifie honnêtement : l'autorisation peut rester vive
     * chez le fournisseur alors que la liaison a disparu chez nous. C'est le
     * moindre mal, et c'est le sens de « effacer n'est pas supprimer la
     * ligne » — ce qui n'a pas abouti doit se lire dans la trace, pas
     * disparaître.
     */
    private Mono<Integer> effacerLesLiaisonsSociales(String subjectId) {
        return oAuthAccountRepository.findByUserId(subjectId)
                .concatMap(compte -> prevenirLeFournisseur(compte)
                        .then(oAuthAccountRepository.delete(compte))
                        .thenReturn(1))
                .reduce(0, Integer::sum);
    }

    private Mono<Void> prevenirLeFournisseur(
            com.abdatytch.auth_service.entities.OAuthAccount compte) {
        // sansVerifierLaConfiguration et non pour(...) : un compte lié du temps
        // où le fournisseur était configuré doit pouvoir être effacé après sa
        // désactivation. `pour(...)` lèverait, et l'effacement échouerait pour
        // une cause sans rapport avec la demande.
        var fournisseur = registreDesFournisseurs.sansVerifierLaConfiguration(
                compte.getProvider());
        if (fournisseur == null) {
            return Mono.empty();
        }
        String jeton = compte.getRefreshToken() != null
                ? compte.getRefreshToken()
                : compte.getAccessToken();
        return fournisseur.revoquerLAcces(jeton);
    }

    /**

     * Écrase les champs identifiants du compte.
     *
     * <p>Le compte est également désactivé : un compte effacé mais toujours
     * actif accepterait encore des tentatives de connexion, et son existence
     * resterait observable par énumération.
     */
    private Mono<Integer> anonymiserUtilisateur(String subjectId) {
        return userRepository.findById(subjectId)
                .flatMap(user -> {
                    effacerIdentite(user);
                    return userRepository.save(user);
                })
                .map(saved -> 1)
                .defaultIfEmpty(0);
    }

    private void effacerIdentite(User user) {
        // Chaque champ écrasé ici est un champ qui, autrement, resterait dans la
        // base, dans les sauvegardes, et dans la portée d'une fuite.
        user.setEmail(null);
        user.setPhoneNumber(null);
        user.setFirstName(EFFACE);
        user.setLastName(EFFACE);
        user.setCountryCode(null);

        // Une empreinte de mot de passe reste attaquable hors ligne : la
        // remplacer par une valeur inutilisable est aussi important que le reste.
        user.setPasswordHash(EFFACE);

        user.setIsActive(false);
        user.setIsEmailVerified(false);
        user.setIsPhoneVerified(false);
        user.setLastLogin(null);
    }

    /**

     * Anonymise les journaux d'authentification plutôt que de les supprimer.
     *
     * <p>Détachés de la personne, ils gardent une valeur antifraude légitime
     * (fréquence des tentatives, plages d'adresses). L'adresse IP et l'agent
     * utilisateur sont en revanche effacés : ce sont des données personnelles à
     * part entière.
     */
    private Mono<Integer> anonymiserJournaux(String subjectId) {
        return authenticationLogRepository.findByUserIdOrderByTimestampDesc(subjectId)
                .flatMap(entry -> {
                    entry.setPhoneNumber(null);
                    entry.setIpAddress(null);
                    entry.setUserAgent(null);
                    entry.setDeviceInfo(null);
                    return authenticationLogRepository.save(entry);
                })
                .count()
                .map(Long::intValue);
    }

    /**

     * Supprime tous les éléments de la source et retourne combien il y en avait.
     *
     * <p>Compter la source AVANT suppression, et non le résultat : la suppression
     * produit un {@code Flux<Void>}, dont le décompte vaut toujours zéro. Le
     * registre aurait alors indiqué « 0 enregistrement effacé » pour chaque
     * demande, ce qui rend la preuve d'effacement inexploitable.
     */
    private <T> Mono<Integer> supprimer(Flux<T> source,
                                        java.util.function.Function<T, Mono<Void>> delete) {
        return source.collectList()
                .flatMap(items -> Flux.fromIterable(items)
                        .flatMap(delete)
                        .then(Mono.just(items.size())));
    }

    /**
     * Preuve d'execution d'une demande.
     *
     * <p>Voir {@link IAccountErasureService#etatDeLaDemande} pour le defaut que
     * ce deplacement corrige.
     */
    @Override
    public Mono<EtatEffacementResponse> etatDeLaDemande(String requestId) {
        return erasureRequestRepository.findById(requestId)
                .flatMap(requete -> erasureAcknowledgementRepository.findByRequestId(requestId)
                        .collectList()
                        .map(confirmations -> EtatEffacementResponse.de(requete, confirmations)));
    }
}
