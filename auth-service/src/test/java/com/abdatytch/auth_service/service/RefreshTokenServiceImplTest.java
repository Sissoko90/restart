package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.constants.AppConstants;
import com.abdatytch.auth_service.constants.ErrorCode;
import com.abdatytch.auth_service.entities.RefreshToken;
import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.exception.AuthException;
import com.abdatytch.auth_service.repository.RefreshTokenRepository;
import com.abdatytch.auth_service.service.impl.RefreshTokenServiceImpl;
import com.abdatytch.commons.security.EmpreinteDeSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests des refresh tokens.
 *
 * <h2>Ce que verrouille cette suite</h2>
 *
 * Deux contrats, l'un ancien et l'autre nouveau.
 *
 * <p>Le premier vient de la migration R2DBC : le jeton porte {@code userId} et
 * non plus l'entite {@code User}, parce que R2DBC ne gere pas les relations.
 *
 * <p>Le second est la raison d'etre de la reecriture de cette suite : la base ne
 * garde plus le secret, seulement son <b>empreinte</b>. Un jeton de
 * rafraichissement suffit a obtenir des jetons d'acces neufs pendant des jours,
 * sans mot de passe ni second facteur : la colonne {@code token} etait donc une
 * liste de sessions pretes a l'emploi. Le service masquait pourtant deja ces
 * jetons dans les <b>journaux</b> — la precaution la plus visible etait prise,
 * la plus importante ne l'etait pas.
 *
 * <p>Le cas le plus important de cette suite est
 * {@link StockageDeLEmpreinte#leSecretNEstJamaisEcrit()} : il verifie que ce qui
 * part en base n'est pas ce qui part au client.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenServiceImpl - la base ne garde que l'empreinte")
class RefreshTokenServiceImplTest {

    private static final String USER_ID = "user-1";

    /** Le secret tel que le client le presenterait. */
    private static final String SECRET = "a-refresh-token";

    /** Ce que la base en garde. C'est par cela qu'on retrouve la ligne. */
    private static final String EMPREINTE = EmpreinteDeSecret.empreinte(SECRET);

    private static final String IP = "41.223.10.5";
    private static final String USER_AGENT = "Mozilla/5.0";

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenServiceImpl(refreshTokenRepository);
    }

    private User user() {
        User user = new User();
        user.setId(USER_ID);
        return user;
    }

    /** Une ligne telle que la base la rend : empreinte posee, secret absent. */
    private RefreshToken ligneEnBase() {
        return RefreshToken.builder()
                .userId(USER_ID)
                .empreinte(EMPREINTE)
                .isRevoked(false)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
    }

    @Nested
    @DisplayName("Stockage de l'empreinte")
    class StockageDeLEmpreinte {

        @Test
        @DisplayName("Le secret n'est JAMAIS ecrit en base")
        void leSecretNEstJamaisEcrit() {
            // Le cas qui porte toute la correction : ce qui part en base ne doit
            // pas etre ce qui part au client.
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            String secretRemis = service.createRefreshToken(user(), IP, USER_AGENT)
                    .block()
                    .valeurAEnvoyerAuClient();

            ArgumentCaptor<RefreshToken> enregistre =
                    ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(enregistre.capture());

            assertThat(enregistre.getValue().getEmpreinte())
                    .as("la base doit porter l'empreinte du secret remis")
                    .isEqualTo(EmpreinteDeSecret.empreinte(secretRemis));
            assertThat(enregistre.getValue().getEmpreinte())
                    .as("et surtout pas le secret lui-meme")
                    .isNotEqualTo(secretRemis);

            // SHA-256 en hexadecimal : 64 caracteres. Nomme l'invariant que
            // porte la colonne VARCHAR(64) de la migration V8.
            assertThat(enregistre.getValue().getEmpreinte()).hasSize(64);
        }

        @Test
        @DisplayName("Le secret est remis a l'appelant, une seule fois")
        void leSecretEstRemisALAppelant() {
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            StepVerifier.create(service.createRefreshToken(user(), IP, USER_AGENT))
                    .assertNext(jeton -> assertThat(jeton.valeurAEnvoyerAuClient())
                            .as("sans lui, le client n'a aucun moyen de se rafraichir")
                            .isNotBlank())
                    .verifyComplete();
        }

        @Test
        @DisplayName("Une ligne chargee depuis la base LEVE au lieu de rendre null")
        void ligneChargeeNeRendPasNull() {
            // Rendre null ferait parvenir au client un jeton vide, et l'erreur
            // ne se verrait qu'au rafraichissement suivant, loin de la cause.
            assertThatThrownBy(() -> ligneEnBase().valeurAEnvoyerAuClient())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empreinte");
        }

        @Test
        @DisplayName("Creation : porte le userId, pas l'entite User")
        void createdTokenCarriesUserId() {
            // Contrat de la migration R2DBC : pas de relation, une cle etrangere.
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            StepVerifier.create(service.createRefreshToken(user(), IP, USER_AGENT))
                    .expectNextCount(1)
                    .verifyComplete();

            ArgumentCaptor<RefreshToken> enregistre =
                    ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(enregistre.capture());

            assertThat(enregistre.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(enregistre.getValue().getEmpreinte()).isNotBlank();
            assertThat(enregistre.getValue().getIpAddress()).isEqualTo(IP);
            assertThat(enregistre.getValue().getUserAgent()).isEqualTo(USER_AGENT);
            assertThat(enregistre.getValue().getExpiresAt()).isAfter(
                    LocalDateTime.now().plusDays(AppConstants.REFRESH_TOKEN_VALIDITY_DAYS - 1));
        }

        @Test
        @DisplayName("Chaque creation produit un secret distinct")
        void eachTokenIsUnique() {
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            String premier = service.createRefreshToken(user(), IP, USER_AGENT)
                    .block().valeurAEnvoyerAuClient();
            String second = service.createRefreshToken(user(), IP, USER_AGENT)
                    .block().valeurAEnvoyerAuClient();

            assertThat(premier).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("La recherche se fait par EMPREINTE du jeton presente")
        void rechercheParEmpreinte() {
            // Si le service cherchait encore par le jeton presente, cette
            // simulation ne repondrait jamais et chaque rafraichissement
            // echouerait en TOKEN_INVALID.
            when(refreshTokenRepository.findByEmpreinte(EMPREINTE))
                    .thenReturn(Mono.just(ligneEnBase()));

            StepVerifier.create(service.validateAndGetRefreshToken(SECRET))
                    .assertNext(trouve -> assertThat(trouve.getUserId()).isEqualTo(USER_ID))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Jeton inconnu : TOKEN_INVALID")
        void unknownTokenIsRejected() {
            when(refreshTokenRepository.findByEmpreinte(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.validateAndGetRefreshToken("inconnu"))
                    .expectErrorSatisfies(erreur ->
                            assertThat(((AuthException) erreur).getErrorCode())
                                    .isEqualTo(ErrorCode.TOKEN_INVALID))
                    .verify();
        }

        @Test
        @DisplayName("Jeton revoque : TOKEN_INVALID, distinct de l'expiration")
        void revokedTokenIsRejected() {
            RefreshToken revoque = ligneEnBase();
            revoque.setIsRevoked(true);
            when(refreshTokenRepository.findByEmpreinte(EMPREINTE))
                    .thenReturn(Mono.just(revoque));

            StepVerifier.create(service.validateAndGetRefreshToken(SECRET))
                    .expectErrorSatisfies(erreur ->
                            assertThat(((AuthException) erreur).getErrorCode())
                                    .isEqualTo(ErrorCode.TOKEN_INVALID))
                    .verify();
        }

        @Test
        @DisplayName("Jeton expire : REFRESH_TOKEN_EXPIRED, le client peut se reauthentifier")
        void expiredTokenGetsDistinctError() {
            RefreshToken expire = ligneEnBase();
            expire.setExpiresAt(LocalDateTime.now().minusMinutes(1));
            when(refreshTokenRepository.findByEmpreinte(EMPREINTE))
                    .thenReturn(Mono.just(expire));

            StepVerifier.create(service.validateAndGetRefreshToken(SECRET))
                    .expectErrorSatisfies(erreur ->
                            assertThat(((AuthException) erreur).getErrorCode())
                                    .isEqualTo(ErrorCode.REFRESH_TOKEN_EXPIRED))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Un jeton absent se REFUSE, il ne provoque pas d'erreur serveur")
    class JetonAbsent {

        /**
         * Le piege introduit par le passage a l'empreinte.
         *
         * <p>{@code EmpreinteDeSecret.empreinte("")} leve : une empreinte de
         * valeur vide est une empreinte valide, et la stocker ferait d'une
         * absence de jeton un jeton acceptable. Mais cette levee traverserait la
         * chaine en <b>500</b>, sur une entree entierement controlee par
         * l'appelant — alors que {@code findByToken(null)} rendait auparavant un
         * {@code Mono} vide, donc un refus propre.
         */
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Validation : TOKEN_INVALID, et la base n'est pas interrogee")
        void validationSansJeton(String jeton) {
            StepVerifier.create(service.validateAndGetRefreshToken(jeton))
                    .expectErrorSatisfies(erreur -> {
                        assertThat(erreur)
                                .as("un 500 signalerait un defaut interne a l'appelant")
                                .isInstanceOf(AuthException.class);
                        assertThat(((AuthException) erreur).getErrorCode())
                                .isEqualTo(ErrorCode.TOKEN_INVALID);
                    })
                    .verify();

            verifyNoInteractions(refreshTokenRepository);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Revocation : TOKEN_INVALID, et la base n'est pas interrogee")
        void revocationSansJeton(String jeton) {
            StepVerifier.create(service.revokeToken(jeton))
                    .expectError(AuthException.class)
                    .verify();

            verifyNoInteractions(refreshTokenRepository);
        }
    }

    @Nested
    @DisplayName("Revocation")
    class Revocation {

        @Test
        @DisplayName("Marque le jeton et horodate")
        void revokeMarksToken() {
            when(refreshTokenRepository.findByEmpreinte(EMPREINTE))
                    .thenReturn(Mono.just(ligneEnBase()));
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            StepVerifier.create(service.revokeToken(SECRET)).verifyComplete();

            ArgumentCaptor<RefreshToken> enregistre =
                    ArgumentCaptor.forClass(RefreshToken.class);
            verify(refreshTokenRepository).save(enregistre.capture());
            assertThat(enregistre.getValue().getIsRevoked()).isTrue();
            assertThat(enregistre.getValue().getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("Jeton inconnu : erreur, aucune ecriture")
        void revokeUnknownTokenFails() {
            when(refreshTokenRepository.findByEmpreinte(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.revokeToken("inconnu"))
                    .expectError(AuthException.class)
                    .verify();

            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("Revocation globale : toutes les sessions actives")
        void revokeAllRevokesEveryActiveSession() {
            RefreshToken premiere = ligneEnBase();
            RefreshToken seconde = ligneEnBase();
            seconde.setEmpreinte(EmpreinteDeSecret.empreinte("un-autre-secret"));

            when(refreshTokenRepository.findByUserIdAndIsRevokedFalse(USER_ID))
                    .thenReturn(Flux.just(premiere, seconde));
            when(refreshTokenRepository.save(any(RefreshToken.class)))
                    .thenAnswer(appel -> Mono.just(appel.getArgument(0)));

            StepVerifier.create(service.revokeAllUserTokens(user())).verifyComplete();

            verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
            assertThat(premiere.getIsRevoked()).isTrue();
            assertThat(seconde.getIsRevoked()).isTrue();
        }
    }

    @Test
    @DisplayName("Nettoyage : supprime les jetons expires")
    void cleanupDeletesExpiredTokens() {
        when(refreshTokenRepository.deleteByExpiresAtBefore(any(LocalDateTime.class)))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.cleanupExpiredTokens()).verifyComplete();

        verify(refreshTokenRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }
}
