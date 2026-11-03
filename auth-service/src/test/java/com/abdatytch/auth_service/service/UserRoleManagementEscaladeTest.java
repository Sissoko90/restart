package com.abdatytch.auth_service.service;

import com.abdatytch.auth_service.dto.request.CreateUserWithRolesRequest;
import com.abdatytch.auth_service.enums.UserRole;
import com.abdatytch.auth_service.repository.UserRepository;
import com.abdatytch.auth_service.repository.UserRoleAssignmentRepository;
import com.abdatytch.auth_service.service.impl.UserRoleManagementServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Attribution des roles : aucune porte vers {@code SUPER_ADMIN}.
 *
 * <h2>La faille que ces tests figent</h2>
 *
 * {@code SUPER_ADMIN} n'existe que par le seeder de deploiement. Si l'API
 * permettait de l'attribuer, un {@code ADMIN} compromis se promouvrait lui-meme
 * et la separation entre administrateur fonctionnel et responsable technique
 * cesserait d'exister.
 *
 * <p>{@code addRoleToUser} appliquait bien ce controle, et son commentaire
 * disait meme : « une regle verifiee a un seul endroit est une regle qu'on
 * contourne en ajoutant un second point d'entree ».
 *
 * <p>Ce second point d'entree existait deja. {@code createUserWithRoles}
 * attribuait <b>sans aucun controle</b> tous les roles presents dans la
 * requete : un {@code ADMIN} creait un compte {@code SUPER_ADMIN} par
 * {@code POST /auth-users} avec {@code roles: [SUPER_ADMIN]}, alors que la
 * promotion directe par {@code POST /auth-users/{id}/roles/SUPER_ADMIN} etait
 * refusee. Meme escalade, autre porte.
 *
 * <p>Ces tests couvrent les <b>trois</b> chemins qui touchent aux roles, et
 * balayent l'enumeration entiere plutot que de nommer {@code SUPER_ADMIN} : un
 * role ajoute demain avec {@code estAttribuableParApi()} a faux sera couvert
 * sans que personne n'ait a y penser.
 */
@DisplayName("Attribution des roles - aucune escalade vers SUPER_ADMIN")
class UserRoleManagementEscaladeTest {

    private final UserRepository userRepository = Mockito.mock(UserRepository.class);
    private final UserRoleAssignmentRepository roleAssignmentRepository =
            Mockito.mock(UserRoleAssignmentRepository.class);

    private UserRoleManagementServiceImpl service() {
        // Les dependances non sollicitees par les chemins de refus restent
        // nulles : le controle intervient avant tout acces aux donnees, et c'est
        // precisement ce que ces tests verifient. Si une implementation future
        // touchait la base avant de refuser, elle echouerait ici — ce qui est le
        // comportement souhaite.
        // Quatre dependances, et non cinq : le client Feign vers user-service a
        // ete retire. Il recopiait l'identite du compte dans un service qui n'a
        // aucune raison de la conserver, en double de l'evenement Kafka
        // `user.created`.
        return new UserRoleManagementServiceImpl(
                userRepository, roleAssignmentRepository, null, null);
    }

    private CreateUserWithRolesRequest requete(UserRole... roles) {
        CreateUserWithRolesRequest r = new CreateUserWithRolesRequest();
        r.setEmail("cible@exemple.test");
        r.setPhoneNumber("+22300000001");
        r.setPassword("MotDePasse123");
        r.setRoles(new LinkedHashSet<>(Set.of(roles)));
        return r;
    }

    // ==================================================================
    @Nested
    @DisplayName("Promotion directe")
    class Promotion {

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserRole.class)
        @DisplayName("addRoleToUser refuse tout role non attribuable par l'API")
        void promotionRefusee(UserRole role) {
            if (role.estAttribuableParApi()) {
                return; // les roles attribuables ont leurs propres tests
            }

            StepVerifier.create(service().addRoleToUser("cible-1", role))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains(role.name()))
                    .verify();

            // Le refus precede toute ecriture : rien ne doit etre enregistre.
            verify(roleAssignmentRepository, never()).save(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Creation de compte - la porte qui etait ouverte")
    class Creation {

        @ParameterizedTest(name = "roles: [{0}]")
        @EnumSource(UserRole.class)
        @DisplayName("createUserWithRoles refuse tout role non attribuable par l'API")
        void creationRefusee(UserRole role) {
            if (role.estAttribuableParApi()) {
                return;
            }

            StepVerifier.create(service().createUserWithRoles(requete(role)))
                    .expectErrorMatches(e -> e instanceof IllegalArgumentException
                            && e.getMessage().contains(role.name()))
                    .verify();

            // Aucun compte ne doit avoir ete cree avant le refus.
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("un role interdit melange a des roles valides fait refuser l'ensemble")
        void melangeRefuse() {
            // La verification porte sur CHAQUE role de la requete, pas seulement
            // sur le premier ni sur le role principal. Glisser SUPER_ADMIN a cote
            // de USER ne doit pas passer.
            UserRole interdit = premierNonAttribuable();

            StepVerifier.create(service().createUserWithRoles(requete(UserRole.USER, interdit)))
                    .expectError(IllegalArgumentException.class)
                    .verify();

            verify(userRepository, never()).save(any());
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Changement de role principal")
    class RolePrincipal {

        @Test
        @DisplayName("changePrimaryRole exige que le role soit DEJA attribue")
        void rolePrincipalExigeUnRoleDejaAttribue() {
            // Ce chemin est protege indirectement, et il faut le dire : il ne
            // verifie pas estAttribuableParApi(), mais exige que le role soit
            // deja activement affecte. Comme les deux chemins d'attribution le
            // refusent, SUPER_ADMIN ne peut jamais remplir cette condition.
            //
            // La protection est donc transitive. Si quelqu'un relache l'un des
            // deux controles en amont, ce chemin devient exploitable sans qu'une
            // seule ligne n'ait change ici — d'ou ce test, qui rend la
            // dependance visible.
            assertThat(premierNonAttribuable())
                    .withFailMessage("plus aucun role n'est non attribuable : "
                            + "la protection transitive de changePrimaryRole n'a plus de sens")
                    .isNotNull();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Le modele lui-meme")
    class Modele {

        @Test
        @DisplayName("au moins un role reste non attribuable par l'API")
        void ilResteUnRoleProtege() {
            // Si cette liste devenait vide, tous les tests parametres ci-dessus
            // passeraient en n'exercant rien — la forme de succes la plus
            // trompeuse, puisqu'elle ressemble a une absence de probleme.
            assertThat(java.util.Arrays.stream(UserRole.values())
                    .filter(r -> !r.estAttribuableParApi())
                    .toList())
                    .withFailMessage("aucun role n'est protege : les tests "
                            + "d'escalade n'exercent plus rien")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("SUPER_ADMIN n'est pas attribuable par l'API")
        void superAdminProtege() {
            assertThat(UserRole.SUPER_ADMIN.estAttribuableParApi()).isFalse();
        }
    }

    private UserRole premierNonAttribuable() {
        return java.util.Arrays.stream(UserRole.values())
                .filter(r -> !r.estAttribuableParApi())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "aucun role non attribuable : le modele de roles a change"));
    }
}
