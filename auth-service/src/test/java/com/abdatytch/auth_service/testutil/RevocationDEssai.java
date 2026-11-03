package com.abdatytch.auth_service.testutil;

import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.service.IRefreshTokenService;
import reactor.core.publisher.Mono;

/**
 * Revocation de sessions inerte, pour les tests qui n'en mesurent pas l'effet.
 *
 * <h2>Pourquoi un helper partage et non quatre copies</h2>
 *
 * {@code AccountLockoutServiceImpl} a gagne une dependance a
 * {@code IRefreshTokenService} — le verrouillage ferme desormais les sessions,
 * sans quoi il ne verrouille que la porte d'entree. Quatre suites le
 * construisent, et aucune ne mesure cette fermeture : elles portent sur le
 * seuil, le verrou et les refus.
 *
 * <p>Ecrire quatre fois le meme simulacre aurait recree la duplication que ce
 * depot passe la session a retirer, avec la garantie qu'une correction n'en
 * toucherait qu'une.
 *
 * <h2>Ce que ce simulacre NE doit pas faire</h2>
 *
 * Il rend {@code Mono.empty()} et non {@code null}. Un {@code null} ferait lever
 * un {@code NullPointerException} dans la chaine reactive, et le test
 * echouerait sur le simulacre plutot que sur ce qu'il mesure — c'est ce qui
 * s'est produit quand une methode de depot {@code default} s'est trouvee
 * simulee par Mockito dans 33 tests.
 *
 * <p>La fermeture des sessions au verrouillage est mesuree ailleurs, par un
 * test qui la vise.
 */
public final class RevocationDEssai {

    private RevocationDEssai() {
    }

    public static IRefreshTokenService revocationQuiNeFaitRien() {
        return new IRefreshTokenService() {
            @Override
            public Mono<RefreshToken> createRefreshToken(User user, String ipAddress,
                                                         String userAgent) {
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
                return Mono.empty();
            }

            @Override
            public Mono<Void> cleanupExpiredTokens() {
                return Mono.empty();
            }
        };
    }
}
