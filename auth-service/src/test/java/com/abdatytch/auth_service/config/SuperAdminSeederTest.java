package com.abdatytch.auth_service.config;

import com.abdatytch.auth_service.entities.User;
import com.abdatytch.auth_service.entities.UserRoleAssignment;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.repository.UserRoleAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationRunner;
import com.abdatytch.commons.totp.TotpService;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Creation du compte SUPER_ADMIN au deploiement.
 *
 * <p>Ce seeder est le <b>seul</b> chemin par lequel le role existe : il n'est pas
 * attribuable par l'API. S'il ne fonctionne pas, personne ne peut administrer
 * techniquement la plateforme. S'il fonctionne trop bien — en creant un compte
 * sur des valeurs de repli — il ouvre un acces dont les identifiants sont
 * lisibles dans le depot.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SuperAdminSeeder - creation du responsable technique")
class SuperAdminSeederTest {

    private static final String EMAIL = "tech@abdatech.test";
    private static final String MOT_DE_PASSE = "MotDePasseTechnique123!";

    @Mock private UserRepository userRepository;
    @Mock private UserRoleAssignmentRepository roleAssignmentRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TotpService totpService;

    private SuperAdminSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new SuperAdminSeeder(userRepository, roleAssignmentRepository,
                passwordEncoder, totpService);

        when(passwordEncoder.encode(any())).thenAnswer(inv -> "chiffre:" + inv.getArgument(0));
        when(totpService.generateTotpSecret()).thenReturn("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(userRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(roleAssignmentRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(roleAssignmentRepository.findByUserId(any())).thenReturn(Flux.empty());
        when(userRepository.findByEmail(any())).thenReturn(Mono.empty());
    }

    private SuperAdminProperties proprietes(String email, String phone, String password) {
        SuperAdminProperties p = new SuperAdminProperties();
        p.setEmail(email);
        p.setPhone(phone);
        p.setPassword(password);
        return p;
    }

    private SuperAdminProperties proprietesCompletes() {
        return proprietes(EMAIL, "+22300000009", MOT_DE_PASSE);
    }

    private void executer(SuperAdminProperties proprietes) throws Exception {
        ApplicationRunner runner = seeder.superAdminApplicationRunner(proprietes);
        runner.run(null);
    }

    // ==================================================================
    @Nested
    @DisplayName("Creation")
    class Creation {

        @Test
        @DisplayName("le compte est cree avec le role SUPER_ADMIN")
        void compteCree() throws Exception {
            executer(proprietesCompletes());

            ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(capture.capture());
            User cree = capture.getValue();

            assertThat(cree.getEmail()).isEqualTo(EMAIL);
            assertThat(cree.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
            assertThat(cree.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("le mot de passe est chiffre, jamais stocke en clair")
        void motDePasseChiffre() throws Exception {
            executer(proprietesCompletes());

            ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(capture.capture());

            assertThat(capture.getValue().getPasswordHash())
                    .withFailMessage("le mot de passe du responsable technique est stocke en clair")
                    .isNotEqualTo(MOT_DE_PASSE);
            verify(passwordEncoder).encode(MOT_DE_PASSE);
        }

        @Test
        @DisplayName("le compte est verifie d'office")
        void compteVerifie() throws Exception {
            // Il n'y a personne pour lui envoyer un code de verification, et le
            // deploiement ne peut pas attendre une confirmation humaine.
            executer(proprietesCompletes());

            ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(capture.capture());

            assertThat(capture.getValue().getIsEmailVerified()).isTrue();
            assertThat(capture.getValue().getIsPhoneVerified()).isTrue();
        }

        @Test
        @DisplayName("un secret TOTP est genere, mais le second facteur reste inactif")
        void secretTotpGenere() throws Exception {
            // Le secret existe des la creation, mais `totpEnabled` reste faux :
            // il doit etre remis une seule fois, a l'enrolement. Le poser a vrai
            // ici rendrait le compte inutilisable — personne n'aurait le secret.
            executer(proprietesCompletes());

            ArgumentCaptor<User> capture = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(capture.capture());

            assertThat(capture.getValue().getTotpSecret()).isNotBlank();
            assertThat(capture.getValue().getTotpEnabled())
                    .withFailMessage("le second facteur est actif alors que personne n'a "
                            + "encore recu le secret : le compte est inutilisable")
                    .isFalse();
        }

        @Test
        @DisplayName("l'affectation de role est creee, active et principale")
        void affectationCreee() throws Exception {
            executer(proprietesCompletes());

            ArgumentCaptor<UserRoleAssignment> capture =
                    ArgumentCaptor.forClass(UserRoleAssignment.class);
            verify(roleAssignmentRepository).save(capture.capture());
            UserRoleAssignment affectation = capture.getValue();

            assertThat(affectation.getRole()).isEqualTo(UserRole.SUPER_ADMIN);
            assertThat(affectation.getIsActive()).isTrue();
            assertThat(affectation.getIsPrimary()).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Aucune valeur de repli")
    class AucunRepli {

        @Test
        @DisplayName("sans configuration, AUCUN compte n'est cree")
        void aucunCompteSansConfiguration() throws Exception {
            // Un compte administrateur cree sur des valeurs de repli aurait des
            // identifiants lisibles dans le depot : c'est pire que pas de compte.
            executer(proprietes(null, null, null));

            verify(userRepository, never()).save(any());
            verify(roleAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("un mot de passe manquant suffit a tout annuler")
        void motDePasseManquant() throws Exception {
            executer(proprietes(EMAIL, "+22300000009", null));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("un champ vide vaut un champ absent")
        void champVide() throws Exception {
            // "" passerait un test de nullite mais ne constitue pas un mot de
            // passe : le compte serait inaccessible, ou pire, accessible.
            executer(proprietes(EMAIL, "+22300000009", "   "));

            verify(userRepository, never()).save(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Idempotence")
    class Idempotence {

        @Test
        @DisplayName("un compte existant n'est pas recree")
        void pasDeRecreation() throws Exception {
            User existant = new User();
            existant.setId("super-admin-1");
            existant.setEmail(EMAIL);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(existant));

            UserRoleAssignment affectation = new UserRoleAssignment();
            affectation.setRole(UserRole.SUPER_ADMIN);
            when(roleAssignmentRepository.findByUserId("super-admin-1"))
                    .thenReturn(Flux.just(affectation));

            executer(proprietesCompletes());

            // Ni doublon de compte, ni mot de passe reinitialise a chaque
            // demarrage — ce qui ecraserait un mot de passe change depuis.
            verify(userRepository, never()).save(any());
            verify(roleAssignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("un role retire a la main est retabli")
        void roleRetabli() throws Exception {
            // Sans cela, le compte existe sans ses pouvoirs et plus personne ne
            // peut administrer la plateforme.
            User existant = new User();
            existant.setId("super-admin-1");
            existant.setEmail(EMAIL);
            when(userRepository.findByEmail(EMAIL)).thenReturn(Mono.just(existant));
            when(roleAssignmentRepository.findByUserId("super-admin-1")).thenReturn(Flux.empty());

            executer(proprietesCompletes());

            ArgumentCaptor<UserRoleAssignment> capture =
                    ArgumentCaptor.forClass(UserRoleAssignment.class);
            verify(roleAssignmentRepository).save(capture.capture());
            assertThat(capture.getValue().getRole()).isEqualTo(UserRole.SUPER_ADMIN);
            verify(userRepository, never()).save(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Robustesse")
    class Robustesse {

        @Test
        @DisplayName("un echec de base ne fait pas echouer le demarrage")
        void echecToleré() {
            // Le seeder est un ApplicationRunner : une exception qui remonte
            // empeche le service de demarrer. Mieux vaut un service debout sans
            // SUPER_ADMIN, avec l'erreur journalisee, qu'un service qui refuse
            // de se lever.
            when(userRepository.findByEmail(any()))
                    .thenReturn(Mono.error(new RuntimeException("base injoignable")));

            org.assertj.core.api.Assertions
                    .assertThatCode(() -> executer(proprietesCompletes()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Affectation de role : les colonnes obligatoires")
    class AffectationDeRole {

        /**
         * Le defaut que ce test existe pour attraper.
         *
         * <p>{@code email} est {@code NOT NULL} dans
         * {@code user_role_assignments}, et {@code creerAffectation} ne le
         * renseignait pas. L'insertion echouait a chaque demarrage, et le
         * SUPER_ADMIN n'a jamais eu d'affectation.
         *
         * <p>{@code assigned_at} est NOT NULL aussi, mais l'entite
         * l'initialise : verifie par mutation, retirer son appel ne fait
         * echouer aucun test. Il est quand meme assure ici, parce qu'une
         * valeur par defaut d'entite peut disparaitre a une relecture.
         *
         * <h3>Pourquoi la suite restait verte</h3>
         *
         * Ce fichier simule {@code roleAssignmentRepository.save(any())} par un
         * succes systematique : <b>un depot simule accepte ce que la base
         * rejette</b>. Aucun test ne regardait le contenu de l'entite sauvegardee,
         * donc aucun ne pouvait voir qu'il manquait deux colonnes obligatoires.
         *
         * <h3>Pourquoi ca ne se voyait pas non plus a l'usage</h3>
         *
         * {@code JwtServiceImpl} se replie sur {@code users.role} quand la liste
         * des affectations est vide. La connexion fonctionnait, le jeton portait
         * bien {@code [ROLE_SUPER_ADMIN, ROLE_ADMIN]}, et l'echec restait
         * invisible — il ne se manifestait que sur la GESTION des roles, qui lit
         * les affectations et elles seules.
         */
        @Test
        @DisplayName("aucune colonne NOT NULL n'est laissee vide")
        void toutesLesColonnesObligatoiresSontRenseignees() throws Exception {
            executer(proprietesCompletes());

            ArgumentCaptor<UserRoleAssignment> capture =
                    ArgumentCaptor.forClass(UserRoleAssignment.class);
            verify(roleAssignmentRepository).save(capture.capture());
            UserRoleAssignment affectation = capture.getValue();

            // Les colonnes NOT NULL du schema, nommees une a une : un
            // assertThat global sur l'objet ne dirait pas LAQUELLE manque.
            assertThat(affectation.getId()).as("id").isNotNull();
            assertThat(affectation.getUserId()).as("user_id").isNotNull();
            assertThat(affectation.getEmail())
                    .as("email — NOT NULL, et c'est celle qui manquait")
                    .isNotNull();
            assertThat(affectation.getRole()).as("role").isNotNull();
            assertThat(affectation.getIsPrimary()).as("is_primary").isNotNull();
            assertThat(affectation.getIsActive()).as("is_active").isNotNull();
            assertThat(affectation.getAssignedAt())
                    .as("assigned_at — NOT NULL, couverte par l'entite")
                    .isNotNull();
            assertThat(affectation.getCreatedAt()).as("created_at").isNotNull();
        }

        @Test
        @DisplayName("l'affectation porte le courriel et le numero du compte")
        void lAffectationPorteLesIdentifiants() throws Exception {
            executer(proprietesCompletes());

            ArgumentCaptor<UserRoleAssignment> capture =
                    ArgumentCaptor.forClass(UserRoleAssignment.class);
            verify(roleAssignmentRepository).save(capture.capture());

            // findActiveByEmailAndRole cherche par EMAIL : une affectation
            // sans courriel serait introuvable, donc impossible a retirer ou a
            // promouvoir.
            assertThat(capture.getValue().getEmail()).isEqualTo(EMAIL);
            assertThat(capture.getValue().getIsPrimary()).isTrue();
            assertThat(capture.getValue().getIsActive()).isTrue();
        }
    }
}
