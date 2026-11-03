package com.abdatytch.auth_service.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Modele de roles de la plateforme.
 *
 * <p>Ces tests portent sur des regles d'autorisation, pas sur du formatage. Une
 * erreur dans l'une d'elles n'echoue pas bruyamment : elle accorde un pouvoir a
 * quelqu'un qui ne devrait pas l'avoir, et rien ne le signale.
 */
@DisplayName("UserRole - modele de roles")
class UserRoleTest {

    @Nested
    @DisplayName("Hierarchie resolue a l'emission du jeton")
    class Hierarchie {

        @Test
        @DisplayName("SUPER_ADMIN confere aussi ADMIN")
        void superAdminHeriteDAdmin() {
            // La hierarchie est resolue ici, et non par une RoleHierarchy declaree
            // dans chacun des huit services : l'un d'eux l'oublierait, et le
            // defaut serait invisible.
            assertThat(UserRole.SUPER_ADMIN.expand())
                    .containsExactlyInAnyOrder(UserRole.SUPER_ADMIN, UserRole.ADMIN);
        }

        @Test
        @DisplayName("ADMIN ne confere PAS SUPER_ADMIN")
        void adminNHeritePasDeSuperAdmin() {
            assertThat(UserRole.ADMIN.expand())
                    .withFailMessage("ADMIN se verrait accorder les pouvoirs techniques : "
                            + "la distinction entre les deux roles ne servirait plus a rien")
                    .containsExactly(UserRole.ADMIN);
        }

        @ParameterizedTest(name = "{0} ne confere que lui-meme")
        @EnumSource(value = UserRole.class, names = {"USER", "DRIVER", "MERCHANT", "DEVELOPER"})
        @DisplayName("un role metier ou d'acces API ne confere rien d'autre")
        void rolesSansHeritage(UserRole role) {
            assertThat(role.expand()).containsExactly(role);
        }

        @Test
        @DisplayName("DEVELOPER n'accorde aucun pouvoir d'administration")
        void developerNEstPasAdministrateur() {
            // C'est un droit de consommation de l'API, soumis aux quotas comme
            // les autres. Le confondre avec un role d'administration serait la
            // faute la plus couteuse de ce modele.
            assertThat(UserRole.DEVELOPER.estRoleDAdministration()).isFalse();
            assertThat(UserRole.DEVELOPER.expand()).doesNotContain(UserRole.ADMIN);
        }
    }

    @Nested
    @DisplayName("SUPER_ADMIN n'est pas attribuable par l'API")
    class Attribution {

        @Test
        @DisplayName("SUPER_ADMIN est refuse a l'attribution")
        void superAdminNonAttribuable() {
            // Sans cette regle, un ADMIN compromis se promouvrait lui-meme : le
            // pouvoir de distribuer les roles inclurait celui de s'octroyer le
            // plus eleve.
            assertThat(UserRole.SUPER_ADMIN.estAttribuableParApi())
                    .withFailMessage("SUPER_ADMIN peut etre accorde par l'API : un ADMIN "
                            + "compromis peut se promouvoir lui-meme")
                    .isFalse();
        }

        @ParameterizedTest(name = "{0} reste attribuable")
        @EnumSource(value = UserRole.class, names = {"USER", "DRIVER", "DELIVERY", "MERCHANT",
                "PHARMACY", "RESTAURANT", "SUPERMARKET", "TENANT", "DISPATCHER",
                "DEVELOPER", "ADMIN"})
        @DisplayName("tous les autres roles restent attribuables")
        void autresRolesAttribuables(UserRole role) {
            assertThat(role.estAttribuableParApi()).isTrue();
        }
    }

    @Nested
    @DisplayName("Coherence de l'enumeration")
    class Coherence {

        @ParameterizedTest(name = "{0}")
        @EnumSource(UserRole.class)
        @DisplayName("chaque role porte une authority prefixee et une description")
        void authorityEtDescription(UserRole role) {
            // Une description manquante fait lever le switch a l'execution, dans
            // une interface d'administration, sur un role rarement utilise.
            assertThat(role.getAuthority()).isEqualTo("ROLE_" + role.name());
            assertThat(role.getDescription()).isNotBlank();
        }

        @Test
        @DisplayName("les deux seuls roles d'administration sont ADMIN et SUPER_ADMIN")
        void perimetreDAdministration() {
            assertThat(java.util.Arrays.stream(UserRole.values())
                    .filter(UserRole::estRoleDAdministration)
                    .toList())
                    .containsExactlyInAnyOrder(UserRole.ADMIN, UserRole.SUPER_ADMIN);
        }
    }
}
