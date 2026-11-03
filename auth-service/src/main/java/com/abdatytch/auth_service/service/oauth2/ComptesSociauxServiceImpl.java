package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.dto.response.CompteSocialResponse;
import com.abdatytch.auth_service.entities.OAuthAccount;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.OAuthAccountRepository;
import com.abdatytch.auth_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Comptes sociaux lies : voir {@link IComptesSociauxService} pour le pourquoi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComptesSociauxServiceImpl implements IComptesSociauxService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final RegistreDesFournisseursOAuth2 registre;

    @Override
    public Flux<CompteSocialResponse> liesAuCompte(String userId) {
        return oauthAccountRepository.findByUserId(userId)
                .map(CompteSocialResponse::de);
    }

    @Override
    @Transactional
    public Mono<Void> delier(String userId, OAuthProvider fournisseur) {
        return oauthAccountRepository.findByUserId(userId)
                .collectList()
                .flatMap(lies -> {
                    OAuthAccount aDelier = lies.stream()
                            .filter(compte -> fournisseur == compte.getProvider())
                            .findFirst()
                            .orElse(null);

                    if (aDelier == null) {
                        // 404 et non 403 : un 403 confirmerait que le compte
                        // existe et qu'il n'a pas ce fournisseur, ce qui se
                        // parcourt. Regle appliquee partout dans ce depot.
                        log.warn("Deliaison refusee : {} n'est pas lie au compte {}",
                                fournisseur.getProviderName(), userId);
                        return Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND));
                    }

                    return userRepository.findById(userId)
                            .switchIfEmpty(Mono.error(new AuthException(ErrorCode.USER_NOT_FOUND)))
                            // Mono.defer sur chaque etape, et ce n'est pas
                            // decoratif : `.then(repo.delete(x))` construit le
                            // Mono de suppression a l'ASSEMBLAGE. Rien n'est
                            // supprime sans souscription, mais la lecture laisse
                            // croire le contraire — et un simulacre qui compte
                            // ses appels voit la suppression alors qu'elle n'a
                            // pas lieu. Le meme piege a ete corrige sur les
                            // filtres de la passerelle et dans la verification
                            // du jeton Apple.
                            //
                            // Avec defer, l'etape n'existe que si la precedente
                            // a abouti. Le refus est alors verifiable.
                            .flatMap(user -> verifierQuilResteUnMoyen(user, lies, fournisseur)
                                    .then(Mono.defer(() -> revoquerChezLeFournisseur(aDelier)))
                                    .then(Mono.defer(() ->
                                            oauthAccountRepository.delete(aDelier)))
                                    .doOnSuccess(rien -> log.info(
                                            "Compte {} delie de {}",
                                            userId, fournisseur.getProviderName())));
                });
    }

    /**
     * Refuse la deliaison du dernier moyen de connexion.
     *
     * <h2>Ce que ce controle empeche concretement</h2>
     *
     * Une inscription par Google suivie d'une deliaison de Google. Le compte
     * n'a alors <b>aucun</b> mot de passe — {@code OAuth2ServiceImpl} pose
     * explicitement {@code passwordHash(null)} pour ces comptes — et plus aucun
     * fournisseur. Personne ne peut plus y entrer, pas meme son proprietaire.
     *
     * <h2>Pourquoi le telephone ne compte pas comme moyen de connexion</h2>
     *
     * La connexion par code sur le numero ({@code verifyPhoneAuth}) exige
     * <b>aussi</b> le mot de passe : un OTP intercepte ne suffit pas a ouvrir
     * une session, et c'est voulu. Un numero confirme sans mot de passe ne
     * permet donc pas de se connecter, et le compter ici laisserait le compte
     * inaccessible malgre le controle.
     */
    private Mono<Void> verifierQuilResteUnMoyen(User user, List<OAuthAccount> lies,
                                                OAuthProvider fournisseur) {
        boolean motDePasseUtilisable = user.getPasswordHash() != null
                && !user.getPasswordHash().isBlank();
        boolean autreFournisseurLie = lies.stream()
                .anyMatch(compte -> fournisseur != compte.getProvider());

        if (motDePasseUtilisable || autreFournisseurLie) {
            return Mono.empty();
        }
        log.warn("Deliaison refusee pour {} : {} est le dernier moyen de connexion",
                user.getId(), fournisseur.getProviderName());
        return Mono.error(new AuthException(ErrorCode.DERNIER_MOYEN_DE_CONNEXION));
    }

    /**
     * Previent le fournisseur, sans jamais faire echouer la deliaison.
     *
     * <p>Apple impose cette revocation a la suppression d'un compte ; la faire
     * aussi a la deliaison est coherent — nous cessons d'utiliser le jeton, donc
     * nous le disons. Mais la deliaison est une demande de la personne : une
     * indisponibilite du fournisseur ne doit pas l'empecher, sinon la liaison
     * reste en place pour une cause qui ne la concerne pas.
     *
     * <p>Le fournisseur est cherche sans passer par {@code registre.pour(...)},
     * qui refuse un fournisseur non configure : un compte lie du temps ou Apple
     * etait configure doit pouvoir etre delie apres sa desactivation.
     */
    private Mono<Void> revoquerChezLeFournisseur(OAuthAccount compte) {
        IFournisseurOAuth2 fournisseur = registre.sansVerifierLaConfiguration(
                compte.getProvider());
        if (fournisseur == null) {
            return Mono.empty();
        }
        String jeton = compte.getRefreshToken() != null
                ? compte.getRefreshToken()
                : compte.getAccessToken();
        return fournisseur.revoquerLAcces(jeton);
    }

    @Override
    public Flux<String> fournisseursDisponibles() {
        return Flux.fromIterable(registre.fournisseursUtilisables())
                .map(OAuthProvider::getProviderName);
    }
}
