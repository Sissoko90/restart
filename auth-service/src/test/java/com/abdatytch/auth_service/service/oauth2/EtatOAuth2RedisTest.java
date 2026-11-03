package com.abdatytch.auth_service.service.oauth2;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.enums.OAuthProvider;
import com.abdatytch.auth_service.exception.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La valeur anti-falsification du parcours OAuth2.
 *
 * <h2>Ce que ces tests ferment</h2>
 *
 * Le <i>login CSRF</i>. Sans {@code state}, un tiers declenche le parcours avec
 * son propre code d'autorisation et fait aboutir le rappel dans le navigateur de
 * la victime : selon le parcours, elle se retrouve connectee au compte de
 * l'attaquant — et y depose ce qu'elle croit deposer chez elle — ou bien le
 * compte social de l'attaquant se lie au sien, ce qui lui en donne ensuite
 * l'acces permanent.
 *
 * <p>La protection etait <b>deja ecrite</b> dans ce depot :
 * {@code AppConstants.OAUTH2_STATE_PREFIX} existait et
 * {@code OAuth2CallbackRequest.state} portait le commentaire « Pour la
 * validation CSRF ». Ni l'un ni l'autre n'etait utilise. C'est le motif le plus
 * frequent de cette base : une protection presente et jamais atteinte.
 */
@DisplayName("EtatOAuth2Redis — la valeur anti-falsification du parcours")
class EtatOAuth2RedisTest {

    private Map<String, String> magasin;
    private Set<String> cleEcrites;

    /**
     * Cles dont la lecture a ete <b>tentee</b>, cle inexistante comprise.
     *
     * <p>Distincte du magasin : c'est l'appel a Redis qui est mesure, pas son
     * resultat. Un refus qui interroge Redis pour rien reste un aller-retour
     * reseau offert a l'appelant.
     */
    private List<String> lecturesTentees;

    private EtatOAuth2Redis etats;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void preparerUnRedisEnMemoire() {
        magasin = new HashMap<>();
        cleEcrites = new HashSet<>();
        lecturesTentees = new ArrayList<>();

        ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
        ReactiveValueOperations<String, String> valeurs = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valeurs);

        when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(appel -> {
                    magasin.put(appel.getArgument(0), appel.getArgument(1));
                    cleEcrites.add(appel.getArgument(0));
                    return Mono.just(true);
                });

        // getAndDelete : la lecture RETIRE la valeur. C'est ce qui rend l'usage
        // unique, et le test s'appuie dessus plutot que sur un get simple.
        when(valeurs.getAndDelete(anyString()))
                .thenAnswer(appel -> {
                    lecturesTentees.add(appel.getArgument(0));
                    String retiree = magasin.remove(appel.getArgument(0));
                    return retiree == null ? Mono.empty() : Mono.just(retiree);
                });

        etats = new EtatOAuth2Redis(redis, 10);
    }

    // ==================================================================
    @Nested
    @DisplayName("Production")
    class Production {

        @Test
        @DisplayName("la valeur est imprevisible et differente a chaque parcours")
        void valeurImprevisible() {
            // Une valeur devinable ne protege de rien : l'attaquant la place
            // lui-meme dans son rappel.
            Set<String> vues = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                vues.add(etats.creer(OAuthProvider.GOOGLE).block());
            }

            assertThat(vues)
                    .withFailMessage("deux parcours ont produit la meme valeur : "
                            + "elle n'est pas imprevisible")
                    .hasSize(50);
            assertThat(vues).allSatisfy(etat -> assertThat(etat).hasSizeGreaterThan(32));
        }

        @Test
        @DisplayName("la valeur est retenue sous le prefixe prevu, avec une echeance")
        void retenueAvecEcheance() {
            // L'echeance n'est pas une commodite : sans elle, chaque parcours
            // abandonne — et il y en a beaucoup — laisserait une cle dans Redis
            // pour toujours.
            String etat = etats.creer(OAuthProvider.APPLE).block();

            assertThat(cleEcrites)
                    .containsExactly(AppConstants.OAUTH2_STATE_PREFIX + etat);
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Verification")
    class Verification {

        @Test
        @DisplayName("une valeur que nous avons emise est acceptee")
        void valeurEmiseAcceptee() {
            String etat = etats.creer(OAuthProvider.GOOGLE).block();

            StepVerifier.create(etats.consommer(etat, OAuthProvider.GOOGLE))
                    .verifyComplete();
        }

        @Test
        @DisplayName("une valeur ne sert qu'UNE fois")
        void usageUnique() {
            // Une valeur rejouable laisserait un code d'autorisation intercepte
            // utilisable plusieurs fois.
            String etat = etats.creer(OAuthProvider.GOOGLE).block();

            StepVerifier.create(etats.consommer(etat, OAuthProvider.GOOGLE))
                    .verifyComplete();

            StepVerifier.create(etats.consommer(etat, OAuthProvider.GOOGLE))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("la valeur est rejouable : elle n'est "
                                    + "donc pas consommee a la verification")
                            .isInstanceOf(AuthException.class));
        }

        /**
         * Un state absent est refuse, et refuse <b>sans interroger Redis</b>.
         *
         * <h2>Pourquoi la seconde moitie de cette assertion existe</h2>
         *
         * Elle a ete ajoutee apres un mutant <b>survivant</b>. En retirant le
         * controle {@code etat == null || etat.isBlank()}, la suite passait
         * encore : {@code cle(null)} produit la cle {@code oauth2_state_null},
         * absente de Redis, donc le refus arrivait quand meme. Les deux chemins
         * etaient indiscernables pour les assertions d'alors, et le garde-fou
         * paraissait donc inutile.
         *
         * <p>Il ne l'est pas, mais pour une raison que seul ce comptage
         * exprime : sans lui, chaque rappel sans state coute un aller-retour
         * Redis. Il suffit alors d'envoyer des rappels vides en nombre pour
         * faire porter la charge sur Redis — que partagent la liste noire de
         * jetons, le registre des envois SMS et les quotas. C'est le meme
         * amplificateur que celui ferme sur le JWKS d'Apple.
         */
        @Test
        @DisplayName("un rappel SANS state est refuse, et sans interroger Redis")
        void sansStateRefuseSansAppelRedis() {
            int avant = magasin.size();

            for (String absent : new String[]{null, "", "   "}) {
                StepVerifier.create(etats.consommer(absent, OAuthProvider.GOOGLE))
                        .verifyErrorSatisfies(erreur -> assertThat(
                                ((AuthException) erreur).getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_REQUEST));
            }

            assertThat(lecturesTentees)
                    .withFailMessage("""
                            Un state absent declenche une lecture Redis.

                            Le refus arrive bien, mais il coute un aller-retour \
                            Redis par rappel vide. Il suffit d'en envoyer en \
                            nombre pour faire porter la charge sur Redis, que \
                            partagent la liste noire de jetons, le registre des \
                            envois SMS et les quotas.

                            Un rappel sans state se refuse localement.""")
                    .isEmpty();
            assertThat(magasin).hasSize(avant);
        }

        @Test
        @DisplayName("une valeur que nous n'avons pas emise est refusee")
        void valeurInconnueRefusee() {
            StepVerifier.create(etats.consommer("valeur-forgee", OAuthProvider.GOOGLE))
                    .verifyError(AuthException.class);
        }

        /**
         * Le lien entre la valeur et le fournisseur.
         *
         * <p>Sans ce controle, une valeur obtenue par un parcours Google serait
         * recevable au rappel d'Apple. L'attaquant obtiendrait alors une valeur
         * legitime chez le fournisseur le plus facile a atteindre, et la
         * presenterait au rappel du fournisseur qu'il vise.
         */
        @Test
        @DisplayName("une valeur emise pour un AUTRE fournisseur est refusee")
        void valeurDUnAutreFournisseurRefusee() {
            String pourGoogle = etats.creer(OAuthProvider.GOOGLE).block();

            StepVerifier.create(etats.consommer(pourGoogle, OAuthProvider.APPLE))
                    .verifyErrorSatisfies(erreur -> assertThat(erreur)
                            .withFailMessage("""
                                    Une valeur emise pour Google est acceptee au \
                                    rappel d'Apple.

                                    L'attaquant obtient alors une valeur legitime \
                                    chez le fournisseur le plus accessible, et la \
                                    presente au rappel de celui qu'il vise.""")
                            .isInstanceOf(AuthException.class));
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Redis indisponible")
    class RedisIndisponible {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("une ecriture qui ne rend rien est une indisponibilite, pas un succes")
        void ecritureVideEstUneIndisponibilite() {
            // Le piege du Mono vide : set() ne rend pas de valeur si Redis est
            // injoignable. Sans switchIfEmpty, la chaine se terminerait vide et
            // l'URL d'autorisation ne serait ni produite ni refusee — la
            // personne verrait une page qui ne fait rien.
            ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
            ReactiveValueOperations<String, String> valeurs = mock(ReactiveValueOperations.class);
            when(redis.opsForValue()).thenReturn(valeurs);
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(new EtatOAuth2Redis(redis, 10).creer(OAuthProvider.GOOGLE))
                    .verifyErrorSatisfies(erreur -> assertThat(
                            ((AuthException) erreur).getErrorCode())
                            .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("une ecriture refusee ne rend pas une valeur inutilisable")
        void ecritureRefusee() {
            // Rendre une valeur non ecrite enverrait la personne jusqu'au
            // fournisseur pour se faire refuser au retour, avec un message qui
            // ne designe pas la cause.
            ReactiveRedisTemplate<String, String> redis = mock(ReactiveRedisTemplate.class);
            ReactiveValueOperations<String, String> valeurs = mock(ReactiveValueOperations.class);
            when(redis.opsForValue()).thenReturn(valeurs);
            when(valeurs.set(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(new EtatOAuth2Redis(redis, 10).creer(OAuthProvider.GOOGLE))
                    .verifyError(AuthException.class);
        }
    }
}
