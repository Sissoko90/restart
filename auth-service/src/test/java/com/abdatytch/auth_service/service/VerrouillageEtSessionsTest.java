package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.metrics.AuthMetrics;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.service.impl.AccountLockoutServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verrouiller un compte doit fermer ses sessions.
 *
 * <h2>Ce que le verrouillage ne faisait pas</h2>
 *
 * Il empechait de se connecter, et <b>seulement</b> cela. Les sessions deja
 * ouvertes vivaient leur vie : le jeton de rafraichissement restait valable et
 * renouvelait l'acces. L'administrateur voyait « compte verrouille » pendant que
 * la personne gardait son acces — le verrou portait sur la porte d'entree, pas
 * sur qui etait deja entre.
 *
 * <p>Et sur le chemin du rafraichissement, le verrou n'etait meme pas consulte :
 * {@code assurerNonVerrouille} etait appele par la connexion normale et par la
 * connexion administrateur, deux chemins sur trois. C'est le motif de toute
 * cette base — une protection ecrite, presente, et non atteinte sur un chemin.
 *
 * <h2>Ce que ces tests ne pretendent pas</h2>
 *
 * Le jeton d'acces deja emis reste valable jusqu'a son echeance : c'est la
 * nature d'un JWT, et le raccourcir demanderait une consultation en base a
 * chaque requete, ce que la politique de cette plateforme refuse. Ce qui est
 * coupe, c'est le <b>renouvellement</b>.
 */
@DisplayName("Verrouillage : les sessions ouvertes sont fermees")
class VerrouillageEtSessionsTest {

    private static final String IDENTIFIANT = "+22374427759";

    private UserRepository userRepository;
    private List<User> sessionsFermeesPour;
    private AccountLockoutServiceImpl verrouillage;
    private User compte;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionsFermeesPour = new ArrayList<>();

        compte = new User();
        compte.setId("utilisateur-1");
        compte.setPhoneNumber(IDENTIFIANT);
        compte.setFailedLoginAttempts(0);
        compte.setIsLocked(false);

        when(userRepository.findByPhoneNumber(anyString())).thenReturn(Mono.just(compte));
        when(userRepository.save(any(User.class)))
                .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

        verrouillage = new AccountLockoutServiceImpl(userRepository, metriques(),
                revocationQuiEnregistre());
        ReflectionTestUtils.setField(verrouillage, "maxAttempts", 3);
        ReflectionTestUtils.setField(verrouillage, "lockoutDurationMinutes", 30);
    }

    private AuthMetrics metriques() {
        return new AuthMetrics(new SimpleMeterRegistry());
    }

    /** Revocation qui note sur quel compte elle a ete appelee. */
    private IRefreshTokenService revocationQuiEnregistre() {
        return new IRefreshTokenService() {
            @Override
            public Mono<RefreshToken> createRefreshToken(User user, String ip, String ua) {
                return Mono.empty();
            }

            @Override
            public Mono<RefreshToken> validateAndGetRefreshToken(String token) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> revokeToken(String token) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> revokeAllUserTokens(User user) {
                sessionsFermeesPour.add(user);
                return Mono.empty();
            }

            @Override
            public Mono<Void> cleanupExpiredTokens() {
                return Mono.empty();
            }
        };
    }

    // ==================================================================
    @Nested
    @DisplayName("Verrouillage administratif")
    class VerrouillageAdministratif {

        @Test
        @DisplayName("verrouiller ferme les sessions ouvertes")
        void verrouillerFermeLesSessions() {
            verrouillage.lockAccount(IDENTIFIANT, 30).block();

            assertThat(sessionsFermeesPour)
                    .withFailMessage("""
                            Le verrouillage n'a ferme aucune session.

                            Le compte ne peut plus se connecter, mais qui detient \
                            deja un refresh token continue de renouveler son \
                            acces. L'administrateur voit « compte verrouille » et \
                            la personne garde l'acces.""")
                    .hasSize(1);
            assertThat(sessionsFermeesPour.get(0).getId()).isEqualTo("utilisateur-1");
        }

        @Test
        @DisplayName("le verrou est bien pose en base, en plus de la fermeture")
        void verrouPoseEnBase() {
            // La fermeture des sessions ne remplace pas le verrou : sans lui,
            // la personne se reconnecte immediatement et obtient une session
            // neuve.
            verrouillage.lockAccount(IDENTIFIANT, 30).block();

            assertThat(compte.getIsLocked()).isTrue();
            assertThat(compte.getLockedUntil()).isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Verrouillage automatique au seuil")
    class VerrouillageAutomatique {

        /**
         * Le cas qu'un attaquant peut provoquer lui-meme.
         *
         * <p>Qui a reussi une connexion puis epuise le compteur d'echecs
         * garderait son acces si le verrouillage automatique ne fermait pas les
         * sessions : le verrou ne generait alors que le proprietaire legitime.
         */
        @Test
        @DisplayName("atteindre le seuil ferme aussi les sessions")
        void seuilAtteintFermeLesSessions() {
            compte.setFailedLoginAttempts(2);

            Boolean verrouille = verrouillage.enregistrerEchec(compte).block();

            assertThat(verrouille).isTrue();
            assertThat(sessionsFermeesPour)
                    .withFailMessage("""
                            Le verrouillage automatique n'a ferme aucune session.

                            Un attaquant qui a deja une session la conserve en \
                            declenchant lui-meme le verrou.""")
                    .hasSize(1);
        }

        @Test
        @DisplayName("un echec SOUS le seuil ne ferme rien")
        void echecSousLeSeuilNeFermeRien() {
            // Fermer les sessions a chaque echec deconnecterait une personne
            // qui se trompe une fois de mot de passe sur un autre appareil.
            compte.setFailedLoginAttempts(0);

            Boolean verrouille = verrouillage.enregistrerEchec(compte).block();

            assertThat(verrouille).isFalse();
            assertThat(sessionsFermeesPour)
                    .withFailMessage("un simple echec a ferme les sessions : une "
                            + "faute de frappe deconnecterait tous les appareils")
                    .isEmpty();
        }
    }
}
