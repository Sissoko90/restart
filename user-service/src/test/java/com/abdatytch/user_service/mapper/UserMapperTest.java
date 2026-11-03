package com.abdatytch.user_service.mapper;

import com.abdatytch.user_service.client.ClientIdentiteAuth;
import com.abdatytch.user_service.dto.request.UserCreationDTO;
import com.abdatytch.user_service.dto.response.AuthUserDTO;
import com.abdatytch.user_service.entities.User;
import com.abdatytch.user_service.entities.UserProfile;
import com.abdatytch.user_service.enums.UserRole;
import com.abdatytch.user_service.enums.UserStatus;
import com.abdatytch.user_service.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La composition d'une réponse utilisateur, enfin exercée.
 *
 * <h2>Pourquoi cette classe méritait un test avant toutes les autres</h2>
 *
 * Elle n'en avait <b>aucun</b>. Les deux suites qui la touchent la remplacent par
 * un mock Mockito, donc sa logique n'était exercée nulle part — et c'est la
 * classe qui a produit le plus de défauts du module :
 *
 * <ul>
 *   <li>le profil déclaratif était <b>systématiquement vide</b> : la méthode
 *       posait {@code UserProfile profile = null} puis renseignait chaque champ
 *       par {@code profile != null ? ... : null}, une condition dont l'issue
 *       était écrite d'avance ;</li>
 *   <li>une liste faisait <b>une requête de profils par ligne</b> ;</li>
 *   <li>l'appel d'identité était fait <b>en bloquant, sur l'event loop</b>, sur
 *       le chemin de chaque réponse ;</li>
 *   <li>le repli posait {@code unknown@email.com} et {@code Unknown User} —
 *       des valeurs que rien ne distinguait de vraies données.</li>
 * </ul>
 *
 * <p>Ces quatre défauts ont tous la même cause racine : personne ne regardait.
 * Les assertions qui suivent sont donc écrites pour échouer si l'un d'eux
 * revient, et non pour décrire ce que le code fait aujourd'hui.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserMapper — composition des trois sources")
class UserMapperTest {

    private static final String AUTH_USER_ID = "auth-123";
    private static final String AUTRE_AUTH_USER_ID = "auth-456";

    @Mock
    private ClientIdentiteAuth clientIdentiteAuth;
    @Mock
    private UserProfileRepository userProfileRepository;

    @InjectMocks
    private UserMapper mapper;

    private UUID compteId;
    private User compte;
    private UserProfile profil;

    @BeforeEach
    void setUp() {
        compteId = UUID.randomUUID();

        compte = new User();
        compte.setId(compteId);
        compte.setAuthUserId(AUTH_USER_ID);
        compte.setStatus(UserStatus.ACTIVE);
        compte.setDateOfBirth(LocalDate.of(1995, 3, 12));
        compte.setProfilePictureUrl("http://passerelle/api/v1/files/auth-123/photo.jpg");

        profil = profilDe(compteId, "Commercante a Bamako");

        when(clientIdentiteAuth.identite(AUTH_USER_ID))
                .thenReturn(Mono.just(identiteDe(AUTH_USER_ID, "awa.diallo@example.ml", "Awa")));
        when(userProfileRepository.findByUserId(compteId)).thenReturn(Mono.just(profil));
        when(userProfileRepository.findAllByUserIdIn(any())).thenReturn(Flux.just(profil));
    }

    private static UserProfile profilDe(UUID userId, String bio) {
        UserProfile p = new UserProfile();
        p.setUserId(userId);
        p.setBio(bio);
        p.setAddress("Rue 12, Badalabougou");
        p.setCity("Bamako");
        p.setCountry("Mali");
        p.setPostalCode("BP 1234");
        p.setPreferences(Map.of("langue", "fr"));
        return p;
    }

    private static AuthUserDTO identiteDe(String id, String courriel, String prenom) {
        return new AuthUserDTO(
                id, courriel, "+22370000000", "ML", prenom, "Diallo",
                UserRole.USER, true, true, false,
                LocalDateTime.of(2026, 10, 30, 14, 5),
                LocalDateTime.of(2026, 1, 2, 9, 0));
    }

    // ==================================================================
    @Nested
    @DisplayName("Les trois sources sont reellement reunies")
    class TroisSources {

        /**
         * Le défaut principal de la classe, et le plus durable.
         *
         * <p>Biographie, adresse, ville, pays, code postal et préférences
         * étaient vides dans <b>toutes</b> les réponses, y compris juste après
         * une modification qui venait de les écrire. Cette assertion est la
         * raison d'être du fichier.
         */
        @Test
        @DisplayName("le profil declaratif est VRAIMENT renseigne")
        void profilRenseigne() {
            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.bio())
                                .withFailMessage("le profil est de nouveau vide : "
                                        + "la reponse ne charge plus user_profiles")
                                .isEqualTo("Commercante a Bamako");
                        assertThat(detail.address()).isEqualTo("Rue 12, Badalabougou");
                        assertThat(detail.city()).isEqualTo("Bamako");
                        assertThat(detail.country()).isEqualTo("Mali");
                        assertThat(detail.postalCode()).isEqualTo("BP 1234");
                        assertThat(detail.preferences()).containsEntry("langue", "fr");
                    })
                    .verifyComplete();
        }

        /** L'identité vient d'auth-service, qui en est la source de vérité. */
        @Test
        @DisplayName("l'identite vient d'auth-service")
        void identiteRenseignee() {
            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.email()).isEqualTo("awa.diallo@example.ml");
                        assertThat(detail.phoneNumber()).isEqualTo("+22370000000");
                        assertThat(detail.countryCode()).isEqualTo("ML");
                        assertThat(detail.firstName()).isEqualTo("Awa");
                        assertThat(detail.lastName()).isEqualTo("Diallo");
                        assertThat(detail.role()).isEqualTo(UserRole.USER);
                        assertThat(detail.lastLoginAt())
                                .isEqualTo(LocalDateTime.of(2026, 10, 30, 14, 5));
                    })
                    .verifyComplete();
        }

        /** L'état local reste détenu par ce service. */
        @Test
        @DisplayName("l'etat local vient de ce service")
        void etatLocalRenseigne() {
            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.id()).isEqualTo(compteId);
                        assertThat(detail.authUserId()).isEqualTo(AUTH_USER_ID);
                        assertThat(detail.status()).isEqualTo(UserStatus.ACTIVE);
                        assertThat(detail.dateOfBirth()).isEqualTo(LocalDate.of(1995, 3, 12));
                        assertThat(detail.profilePictureUrl())
                                .isEqualTo("http://passerelle/api/v1/files/auth-123/photo.jpg");
                    })
                    .verifyComplete();
        }

        /**
         * Le piège du {@code Mono} vide.
         *
         * <p>Le profil est rendu dans un {@code Optional} parce que
         * {@code Mono.zip} <b>ignore une source vide</b> : un compte sans profil
         * aurait fait disparaître la réponse entière, et l'appelant aurait reçu
         * un 404 pour un compte qui existe.
         */
        @Test
        @DisplayName("un compte sans profil rend quand meme une reponse")
        void comptesSansProfil() {
            when(userProfileRepository.findByUserId(compteId)).thenReturn(Mono.empty());

            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.id())
                                .withFailMessage("la reponse a disparu parce que le compte "
                                        + "n'a pas de profil : c'est le piege du Mono vide")
                                .isEqualTo(compteId);
                        assertThat(detail.bio()).isNull();
                        assertThat(detail.preferences()).isEmpty();
                    })
                    .verifyComplete();
        }

        /** Réponse courte : pas de profil déclaratif, donc pas de lecture locale. */
        @Test
        @DisplayName("la reponse courte ne lit pas user_profiles")
        void reponseCourteSansProfil() {
            StepVerifier.create(mapper.toResponseDTO(compte))
                    .assertNext(reponse -> {
                        assertThat(reponse.email()).isEqualTo("awa.diallo@example.ml");
                        assertThat(reponse.status()).isEqualTo(UserStatus.ACTIVE);
                    })
                    .verifyComplete();

            verify(userProfileRepository, never()).findByUserId(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Une liste ne fait PAS une requete par ligne")
    class PasDeNPlusUn {

        /**
         * Le N+1 fermé par {@code findAllByUserIdIn}.
         *
         * <p>Chaque réponse chargeait son profil séparément : une page de vingt
         * comptes faisait vingt requêtes sur {@code user_profiles}, une page de
         * cent en faisait cent — et chacune prend une connexion du pool.
         */
        @Test
        @DisplayName("deux comptes font UNE requete de profils, et non deux")
        void uneSeuleRequeteDeProfils() {
            User autre = autreCompte();
            when(userProfileRepository.findAllByUserIdIn(any()))
                    .thenReturn(Flux.just(profil, profilDe(autre.getId(), "Chauffeur")));

            StepVerifier.create(mapper.toDetailDTOs(Flux.just(compte, autre)))
                    .expectNextCount(2)
                    .verifyComplete();

            verify(userProfileRepository, times(1)).findAllByUserIdIn(any());
            verify(userProfileRepository, never()).findByUserId(any());
        }

        /**
         * L'ordre du tri SQL est préservé.
         *
         * <p>{@code flatMapSequential} et non {@code flatMap} : une liste
         * paginée qui se réordonne au gré des latences réseau est inutilisable,
         * la page suivante ne reprenant pas où la précédente s'est arrêtée.
         */
        @Test
        @DisplayName("l'ordre du tri SQL est preserve malgre les appels simultanes")
        void ordrePreserve() {
            User autre = autreCompte();
            when(userProfileRepository.findAllByUserIdIn(any()))
                    .thenReturn(Flux.just(profil, profilDe(autre.getId(), "Chauffeur")));

            StepVerifier.create(mapper.toDetailDTOs(Flux.just(compte, autre))
                            .map(detail -> detail.authUserId()))
                    .expectNext(AUTH_USER_ID, AUTRE_AUTH_USER_ID)
                    .verifyComplete();
        }

        /**
         * Un profil absent de la carte n'est pas un décalage.
         *
         * <p>La base ne rend que les profils <b>existants</b> : rien ne garantit
         * ni leur nombre ni leur ordre. D'où une carte indexée par identifiant,
         * et non une liste alignée sur la page — qui aurait attribué le profil
         * d'un compte à un autre.
         */
        @Test
        @DisplayName("un compte sans profil n'empile pas le profil du voisin")
        void pasDeDecalage() {
            User autre = autreCompte();
            // Seul le SECOND compte a un profil.
            when(userProfileRepository.findAllByUserIdIn(any()))
                    .thenReturn(Flux.just(profilDe(autre.getId(), "Chauffeur")));

            StepVerifier.create(mapper.toDetailDTOs(Flux.just(compte, autre)))
                    .assertNext(premier -> assertThat(premier.bio())
                            .withFailMessage("le profil du voisin a ete attribue a ce compte")
                            .isNull())
                    .assertNext(second -> assertThat(second.bio()).isEqualTo("Chauffeur"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("une page vide ne declenche aucune requete")
        void pageVide() {
            StepVerifier.create(mapper.toDetailDTOs(Flux.empty()))
                    .verifyComplete();

            verify(userProfileRepository, never()).findAllByUserIdIn(any());
        }

        private User autreCompte() {
            User autre = new User();
            autre.setId(UUID.randomUUID());
            autre.setAuthUserId(AUTRE_AUTH_USER_ID);
            autre.setStatus(UserStatus.ACTIVE);
            when(clientIdentiteAuth.identite(AUTRE_AUTH_USER_ID)).thenReturn(
                    Mono.just(identiteDe(AUTRE_AUTH_USER_ID, "sekou@example.ml", "Sekou")));
            return autre;
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le repli dit « je ne sais pas », et n'invente pas")
    class LeRepli {

        /**
         * Des champs nuls, et non des valeurs vraisemblables.
         *
         * <p>Le repli posait {@code unknown@email.com} et {@code Unknown User} :
         * une adresse syntaxiquement valide et un nom lisible. Comme le préfixe
         * erroné faisait par ailleurs échouer <b>chaque</b> appel, ce chemin
         * dégradé était le chemin <b>normal</b> — toutes les réponses du service
         * portaient donc une identité fausse, sans que rien ne le dise.
         *
         * <p>Un champ nul se voit. C'est tout l'écart entre « je ne sais pas » et
         * une réponse inventée.
         */
        @Test
        @DisplayName("auth-service indisponible rend des champs NULS")
        void champsNulsEtNonVraisemblables() {
            when(clientIdentiteAuth.identite(AUTH_USER_ID))
                    .thenReturn(Mono.error(new IllegalStateException("auth-service indisponible")));

            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.email())
                                .withFailMessage("une valeur de repli vraisemblable est "
                                        + "revenue : elle passerait pour une vraie donnee")
                                .isNull();
                        assertThat(detail.firstName()).isNull();
                        assertThat(detail.lastName()).isNull();
                        assertThat(detail.phoneNumber()).isNull();
                        assertThat(detail.role()).isNull();
                    })
                    .verifyComplete();
        }

        /**
         * L'état administratif reste exact pendant la panne.
         *
         * <p>C'est la raison de conserver un repli plutôt que de propager
         * l'erreur : bloqué, suspendu, révoqué viennent de <b>ce</b> service, et
         * un administrateur doit pouvoir les lire même quand auth-service est
         * tombé.
         */
        @Test
        @DisplayName("l'etat administratif local reste exact pendant la panne")
        void etatAdministratifExact() {
            compte.setStatus(UserStatus.BLOCKED);
            when(clientIdentiteAuth.identite(AUTH_USER_ID))
                    .thenReturn(Mono.error(new IllegalStateException("auth-service indisponible")));

            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.status()).isEqualTo(UserStatus.BLOCKED);
                        assertThat(detail.authUserId()).isEqualTo(AUTH_USER_ID);
                    })
                    .verifyComplete();
        }

        /** Un seul compte en échec ne fait pas disparaître la liste entière. */
        @Test
        @DisplayName("un compte en echec ne tue pas la liste")
        void listeSurvitAUnEchec() {
            User autre = new User();
            autre.setId(UUID.randomUUID());
            autre.setAuthUserId(AUTRE_AUTH_USER_ID);
            autre.setStatus(UserStatus.ACTIVE);
            when(clientIdentiteAuth.identite(AUTRE_AUTH_USER_ID))
                    .thenReturn(Mono.error(new IllegalStateException("auth-service indisponible")));
            when(userProfileRepository.findAllByUserIdIn(any())).thenReturn(Flux.just(profil));

            StepVerifier.create(mapper.toDetailDTOs(Flux.just(compte, autre)))
                    .assertNext(premier ->
                            assertThat(premier.email()).isEqualTo("awa.diallo@example.ml"))
                    .assertNext(second -> assertThat(second.email()).isNull())
                    .verifyComplete();
        }

        /** Un profil illisible ne fait pas disparaître la réponse. */
        @Test
        @DisplayName("un profil illisible rend une reponse aux champs declaratifs nuls")
        void profilIllisible() {
            when(userProfileRepository.findByUserId(compteId))
                    .thenReturn(Mono.error(new IllegalStateException("table indisponible")));

            StepVerifier.create(mapper.toDetailDTO(compte))
                    .assertNext(detail -> {
                        assertThat(detail.bio()).isNull();
                        assertThat(detail.status()).isEqualTo(UserStatus.ACTIVE);
                        assertThat(detail.email()).isEqualTo("awa.diallo@example.ml");
                    })
                    .verifyComplete();
        }

        /** Une page dont les profils sont illisibles reste servie. */
        @Test
        @DisplayName("des profils illisibles ne font pas disparaitre la page")
        void profilsDePageIllisibles() {
            when(userProfileRepository.findAllByUserIdIn(any()))
                    .thenReturn(Flux.error(new IllegalStateException("table indisponible")));

            StepVerifier.create(mapper.toDetailDTOs(Flux.just(compte)))
                    .assertNext(detail -> {
                        assertThat(detail.id()).isEqualTo(compteId);
                        assertThat(detail.bio()).isNull();
                    })
                    .verifyComplete();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("L'entite creee ne retient que ce que ce service detient")
    class Creation {

        /**
         * Les champs locaux étaient acceptés puis <b>jetés</b>.
         *
         * <p>Seul {@code authUserId} était repris : un appelant qui fournissait
         * une date de naissance recevait un 201 et un profil vide.
         */
        @Test
        @DisplayName("date de naissance et photo sont conservees")
        void champsLocauxConserves() {
            User entite = mapper.toEntity(creation());

            assertThat(entite.getAuthUserId()).isEqualTo(AUTH_USER_ID);
            assertThat(entite.getDateOfBirth())
                    .withFailMessage("la date de naissance fournie est de nouveau jetee")
                    .isEqualTo(LocalDate.of(1995, 3, 12));
            assertThat(entite.getProfilePictureUrl()).isEqualTo("http://fournisseur/photo.jpg");
        }

        /**
         * L'identité n'est <b>pas</b> recopiée, et volontairement.
         *
         * <p>auth-service en est la source de vérité ; la recopier ferait un
         * second magasin qui divergerait dès la première modification là-bas.
         */
        @Test
        @DisplayName("seul le lien vers auth-service est conserve, et rien n'est devine")
        void identiteNonRecopiee() {
            User entite = mapper.toEntity(creation());

            // Courriel, numero, nom, role : ces champs n'existent pas sur
            // l'entite, et le verifier par leur absence serait vain. Ce qui est
            // verifiable, c'est que le mapper ne DEVINE rien d'autre : le seul
            // lien conserve vers auth-service est l'identifiant, l'identifiant
            // local reste a la base, et le statut garde le defaut de l'entite.
            assertThat(entite.getAuthUserId()).isEqualTo(AUTH_USER_ID);
            assertThat(entite.getId())
                    .withFailMessage("le mapper attribue un identifiant : il revient a la base")
                    .isNull();
            assertThat(entite.getStatus())
                    .withFailMessage("le mapper pose un statut depuis le DTO : il doit "
                            + "laisser le defaut de l'entite, qu'aucun appelant ne choisit")
                    .isEqualTo(new User().getStatus());
        }

        /**
         * {@code address} et {@code bio} ne vont pas sur l'entité.
         *
         * <p>Ils figurent bien dans le DTO et sont bien enregistrés — mais dans
         * {@code user_profiles}, par {@code UserServiceReactiveImpl.createUser},
         * pas ici. Le partage est volontaire : {@code toEntity} ne connaît que
         * la table {@code users}. Ce test fixe la frontière, pour qu'on ne les
         * cherche pas au mauvais endroit.
         */
        @Test
        @DisplayName("toEntity ne touche que la table users")
        void frontiereAvecLeProfil() {
            User entite = mapper.toEntity(creation());

            assertThat(entite.getDateOfBirth()).isNotNull();
            assertThat(entite.getProfilePictureUrl()).isNotNull();
        }

        private static UserCreationDTO creation() {
            return new UserCreationDTO(
                    AUTH_USER_ID,
                    "awa.diallo@example.ml",
                    "+22370000000",
                    "ML",
                    "Awa",
                    "Diallo",
                    UserRole.USER,
                    Set.of(UserRole.USER),
                    UserRole.USER,
                    LocalDate.of(1995, 3, 12),
                    "Rue 12, Badalabougou",
                    "Commercante a Bamako",
                    "http://fournisseur/photo.jpg");
        }
    }
}
