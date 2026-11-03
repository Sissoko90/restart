package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.OAuthAccount;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.OAuthAccountRepository;
import com.abdatytch.auth_service.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lier, lister, delier — et ne jamais enfermer la personne dehors.
 *
 * <h2>Ce qui manquait</h2>
 *
 * On pouvait lier Google, Facebook et Apple. Jamais delier, ni meme savoir ce
 * qui etait lie. Une personne qui a relie un compte par erreur, ou qui
 * n'utilise plus ce fournisseur, n'avait aucune sortie.
 *
 * <h2>Le piege que la regle ferme</h2>
 *
 * Une inscription par Google suivie d'une deliaison de Google. Le compte n'a
 * alors <b>aucun</b> mot de passe — {@code OAuth2ServiceImpl} pose
 * explicitement {@code passwordHash(null)} pour ces comptes, ce qui a ete
 * verifie dans le code avant d'ecrire la regle — et plus aucun fournisseur.
 * Personne ne peut plus entrer, pas meme son proprietaire.
 */
@DisplayName("Comptes sociaux : lister et delier")
class ComptesSociauxServiceImplTest {

    private static final String UTILISATEUR = "utilisateur-1";

    private OAuthAccountRepository oauthAccountRepository;
    private UserRepository userRepository;
    private List<OAuthAccount> supprimes;
    private List<String> jetonsRevoques;
    private User compte;

    @BeforeEach
    void setUp() {
        oauthAccountRepository = mock(OAuthAccountRepository.class);
        userRepository = mock(UserRepository.class);
        supprimes = new ArrayList<>();
        jetonsRevoques = new ArrayList<>();

        compte = new User();
        compte.setId(UTILISATEUR);

        when(oauthAccountRepository.delete(any(OAuthAccount.class)))
                .thenAnswer(appel -> {
                    supprimes.add(appel.getArgument(0));
                    return Mono.empty();
                });
        when(userRepository.findById(anyString())).thenReturn(Mono.just(compte));
    }

    private static OAuthAccount liaison(OAuthProvider fournisseur, String jeton) {
        OAuthAccount liee = new OAuthAccount();
        liee.setId("liaison-" + fournisseur.getProviderName());
        liee.setUserId(UTILISATEUR);
        liee.setProvider(fournisseur);
        liee.setProviderUserId("id-chez-le-fournisseur");
        liee.setEmail("awa@exemple.test");
        liee.setRefreshToken(jeton);
        return liee;
    }

    /** Fournisseur d'essai qui note le jeton qu'on lui demande de revoquer. */
    private IFournisseurOAuth2 fournisseurQuiNoteLaRevocation(OAuthProvider valeur) {
        return new IFournisseurOAuth2() {
            @Override
            public OAuthProvider fournisseur() {
                return valeur;
            }

            @Override
            public boolean estConfigure() {
                return true;
            }

            @Override
            public String urlDAutorisation(String etat) {
                return "https://exemple.test/autoriser";
            }

            @Override
            public String uriDeRappel() {
                return "https://exemple.test/rappel";
            }

            @Override
            public Mono<com.abdatytch.auth_service.dto.response.OAuth2UserInfo>
                    echangerLeCode(String code) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> revoquerLAcces(String jeton) {
                jetonsRevoques.add(jeton);
                return Mono.empty();
            }
        };
    }

    private ComptesSociauxServiceImpl service(List<OAuthAccount> lies,
                                              IFournisseurOAuth2... fournisseurs) {
        when(oauthAccountRepository.findByUserId(anyString()))
                .thenReturn(Flux.fromIterable(lies));
        return new ComptesSociauxServiceImpl(oauthAccountRepository, userRepository,
                new RegistreDesFournisseursOAuth2(List.of(fournisseurs)));
    }

    // ==================================================================
    @Nested
    @DisplayName("Le dernier moyen de connexion ne se delie pas")
    class DernierMoyen {

        /**
         * Le cas d'une inscription par Google, puis deliaison de Google.
         */
        @Test
        @DisplayName("sans mot de passe et sans autre fournisseur, la deliaison est refusee")
        void deliaisonDuDernierMoyenRefusee() {
            compte.setPasswordHash(null);
            var service = service(List.of(liaison(OAuthProvider.GOOGLE, "rt-google")),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.GOOGLE));

            StepVerifier.create(service.delier(UTILISATEUR, OAuthProvider.GOOGLE))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .withFailMessage("""
                                    Le dernier moyen de connexion a ete delie.

                                    Le compte n'a aucun mot de passe — les comptes \
                                    crees par OAuth2 ont passwordHash null — et plus \
                                    aucun fournisseur : personne ne peut plus y \
                                    entrer, pas meme son proprietaire.""")
                            .isEqualTo(ErrorCode.DERNIER_MOYEN_DE_CONNEXION));

            assertThat(supprimes)
                    .withFailMessage("la liaison a ete supprimee malgre le refus")
                    .isEmpty();
        }

        @Test
        @DisplayName("un mot de passe utilisable autorise la deliaison")
        void motDePasseAutoriseLaDeliaison() {
            compte.setPasswordHash("$2a$12$une.empreinte.bcrypt.valide");
            var service = service(List.of(liaison(OAuthProvider.GOOGLE, "rt-google")),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.GOOGLE));

            StepVerifier.create(service.delier(UTILISATEUR, OAuthProvider.GOOGLE))
                    .verifyComplete();

            assertThat(supprimes).hasSize(1);
        }

        @Test
        @DisplayName("un autre fournisseur lie autorise la deliaison")
        void autreFournisseurAutoriseLaDeliaison() {
            compte.setPasswordHash(null);
            var service = service(
                    List.of(liaison(OAuthProvider.GOOGLE, "rt-google"),
                            liaison(OAuthProvider.APPLE, "rt-apple")),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.GOOGLE),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.APPLE));

            StepVerifier.create(service.delier(UTILISATEUR, OAuthProvider.GOOGLE))
                    .verifyComplete();

            assertThat(supprimes).hasSize(1);
            assertThat(supprimes.get(0).getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        }

        /**
         * Un mot de passe vide n'est pas un mot de passe.
         *
         * <p>Une empreinte blanche en base passerait un simple controle de
         * nullite et laisserait delier le dernier fournisseur, en croyant qu'une
         * connexion par mot de passe reste possible.
         */
        @Test
        @DisplayName("une empreinte vide ne compte pas comme un mot de passe")
        void empreinteVideNeCompatePas() {
            compte.setPasswordHash("   ");
            var service = service(List.of(liaison(OAuthProvider.APPLE, "rt-apple")),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.APPLE));

            StepVerifier.create(service.delier(UTILISATEUR, OAuthProvider.APPLE))
                    .verifyError(AuthException.class);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("La revocation precede la suppression")
    class RevocationAvantSuppression {

        /**
         * L'ordre n'est pas interchangeable.
         *
         * <p>La revocation a besoin du jeton, qui est <b>dans</b> la ligne qu'on
         * s'apprete a supprimer. Supprimer d'abord rendrait la revocation
         * impossible, et l'autorisation resterait vive chez le fournisseur sans
         * qu'aucune trace ne permette de la retirer plus tard.
         */
        @Test
        @DisplayName("le fournisseur est prevenu, avec le jeton de la liaison")
        void fournisseurPrevenuAvecLeJeton() {
            compte.setPasswordHash("$2a$12$empreinte");
            var service = service(List.of(liaison(OAuthProvider.APPLE, "rt-apple")),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.APPLE));

            service.delier(UTILISATEUR, OAuthProvider.APPLE).block();

            assertThat(jetonsRevoques)
                    .withFailMessage("""
                            Le fournisseur n'a pas ete prevenu.

                            Apple EXIGE cet appel a la suppression d'un compte : \
                            une application sur l'App Store qui propose Sign in \
                            with Apple et la suppression de compte doit appeler \
                            son point de revocation.""")
                    .containsExactly("rt-apple");
            assertThat(supprimes).hasSize(1);
        }

        @Test
        @DisplayName("un fournisseur absent du registre n'empeche pas la deliaison")
        void fournisseurAbsentNEmpechePas() {
            // Un compte lie du temps ou Apple etait configure doit pouvoir etre
            // delie apres sa desactivation. Sinon, desactiver un fournisseur
            // emprisonne les liaisons existantes.
            compte.setPasswordHash("$2a$12$empreinte");
            var service = service(List.of(liaison(OAuthProvider.APPLE, "rt-apple")));

            StepVerifier.create(service.delier(UTILISATEUR, OAuthProvider.APPLE))
                    .verifyComplete();

            assertThat(supprimes).hasSize(1);
            assertThat(jetonsRevoques).isEmpty();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Lister, et refuser ce qui n'est pas lie")
    class ListerEtRefuser

    {
        @Test
        @DisplayName("delier un fournisseur non lie rend une absence, pas un refus")
        void fournisseurNonLie() {
            // 404 et non 403 : un 403 confirmerait que le compte existe et
            // n'a pas ce fournisseur, ce qui se parcourt.
            compte.setPasswordHash("$2a$12$empreinte");
            var service = service(List.of(liaison(OAuthProvider.GOOGLE, "rt-google")),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.GOOGLE));

            StepVerifier.create(service.delier(UTILISATEUR, OAuthProvider.APPLE))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.USER_NOT_FOUND));

            assertThat(supprimes).isEmpty();
        }

        /**
         * Aucun jeton du fournisseur ne sort dans la liste.
         *
         * <p>{@code oauth_accounts} porte un {@code access_token} et un
         * {@code refresh_token} valables <b>chez Google ou chez Apple</b>. Les
         * rendre ici les ferait voyager dans une reponse HTTP pour repondre a
         * une question qui ne les demandait pas.
         */
        @Test
        @DisplayName("la liste ne contient AUCUN jeton du fournisseur")
        void listeSansJeton() {
            var service = service(List.of(liaison(OAuthProvider.APPLE, "rt-tres-secret")));

            StepVerifier.create(service.liesAuCompte(UTILISATEUR))
                    .assertNext(vue -> {
                        assertThat(vue.getProvider()).isEqualTo("apple");
                        assertThat(vue.toString())
                                .withFailMessage("un jeton du fournisseur figure dans "
                                        + "la reponse : il devient lisible dans les "
                                        + "journaux du client")
                                .doesNotContain("rt-tres-secret");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("seuls les fournisseurs configures sont annonces comme disponibles")
        void fournisseursDisponibles() {
            var service = service(List.of(),
                    fournisseurQuiNoteLaRevocation(OAuthProvider.GOOGLE));

            StepVerifier.create(service.fournisseursDisponibles())
                    .expectNext("google")
                    .verifyComplete();
        }
    }
}
