package com.abdatytch.commons.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lecture unique des claims de rôle, partagée par tous les services.
 *
 * <p>Cette classe remplace quatre implémentations divergentes, dont trois
 * faisaient l'hypothèse — jamais vraie pour un jeton d'utilisateur final — que
 * le claim {@code roles} est une chaîne. Les tests ci-dessous couvrent
 * <b>toutes</b> les formes réellement émises par l'autorité, pour qu'aucun
 * service n'ait plus à deviner.
 */
@DisplayName("JwtClaimsReader — lecture unique des rôles")
class JwtClaimsReaderTest {

    private static Claims claims(Map<String, Object> values) {
        return Jwts.claims().add(values).build();
    }

    // ==================================================================
    @Nested
    @DisplayName("Les deux formes réellement émises par l'autorité")
    class FormesEmises {

        @Test
        @DisplayName("jeton d'utilisateur final : `roles` est une LISTE")
        void rolesEnListe() {
            Claims jeton = claims(Map.of("type", "user", "roles", List.of("ROLE_USER", "ROLE_MERCHANT")));

            assertThat(JwtClaimsReader.roles(jeton))
                    .withFailMessage("c'est la forme qui faisait échouer les lectures typées "
                            + "String : 401 au Gateway, 403 sur les documents")
                    .containsExactly("ROLE_USER", "ROLE_MERCHANT");
            assertThat(JwtClaimsReader.primaryRole(jeton)).isEqualTo("ROLE_USER");
        }

        @Test
        @DisplayName("jeton de développeur : `role` est une CHAÎNE, et `roles` est absent")
        void roleEnChaine() {
            Claims jeton = claims(Map.of("type", "developer", "role", "ROLE_DEVELOPER"));

            assertThat(JwtClaimsReader.roles(jeton)).containsExactly("ROLE_DEVELOPER");
            assertThat(JwtClaimsReader.primaryRole(jeton)).isEqualTo("ROLE_DEVELOPER");
        }

        @Test
        @DisplayName("`primaryRole` prime sur la liste quand il est présent")
        void primaryRolePrioritaire() {
            // auth-service ajoute `primaryRole` en claim personnalisé à côté de
            // la liste complète : c'est lui qui désigne le rôle principal.
            Claims jeton = claims(Map.of(
                    "roles", List.of("ROLE_USER", "ROLE_ADMIN"),
                    "primaryRole", "ROLE_ADMIN"));

            assertThat(JwtClaimsReader.primaryRole(jeton)).isEqualTo("ROLE_ADMIN");
        }

        @Test
        @DisplayName("`authorities` est accepté en dernier recours")
        void authoritiesEnDernierRecours() {
            assertThat(JwtClaimsReader.roles(claims(Map.of("authorities", List.of("ROLE_ADMIN")))))
                    .containsExactly("ROLE_ADMIN");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Un claim inattendu ne doit jamais lever d'exception")
    class Robustesse {

        @Test
        @DisplayName("un jeton sans aucun rôle donne une liste vide")
        void aucunRole() {
            Claims jeton = claims(Map.of("type", "user"));

            assertThat(JwtClaimsReader.roles(jeton)).isEmpty();
            assertThat(JwtClaimsReader.primaryRole(jeton)).isNull();
        }

        @Test
        @DisplayName("un type inattendu vaut « aucun rôle », pas une erreur")
        void typeInattendu() {
            // Une exception ici serait captée en amont et transformée en refus :
            // le défaut deviendrait invisible, comme il l'a été jusqu'ici.
            Claims jeton = claims(Map.of("roles", 42));

            assertThatCode(() -> JwtClaimsReader.roles(jeton)).doesNotThrowAnyException();
            assertThat(JwtClaimsReader.roles(jeton)).isEmpty();
        }

        @Test
        @DisplayName("les entrées vides de la liste sont ignorées")
        void entreesVides() {
            assertThat(JwtClaimsReader.roles(claims(Map.of("roles", List.of("", "  ", "ROLE_USER")))))
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("des claims nuls ne font pas échouer l'appelant")
        void claimsNuls() {
            assertThat(JwtClaimsReader.roles(null)).isEmpty();
            assertThat(JwtClaimsReader.primaryRole(null)).isNull();
            assertThat(JwtClaimsReader.hasRole(null, "ADMIN")).isFalse();
            assertThat(JwtClaimsReader.isAccessToken(null)).isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Comparaison de rôle")
    class ComparaisonDeRole {

        @ParameterizedTest(name = "rôle porté « {0} » satisfait une exigence d'ADMIN")
        @ValueSource(strings = {"ROLE_ADMIN", "ADMIN", "role_admin", "admin"})
        @DisplayName("le préfixe ROLE_ et la casse n'empêchent pas la reconnaissance")
        void prefixeEtCasseIndifferents(String rolePorte) {
            // Les deux écritures circulent : le Gateway propage ROLE_ADMIN,
            // certains services comparent à ADMIN. Une égalité stricte refusait
            // l'accès selon l'émetteur du jeton.
            Claims jeton = claims(Map.of("roles", List.of(rolePorte)));

            assertThat(JwtClaimsReader.hasRole(jeton, "ADMIN")).isTrue();
            assertThat(JwtClaimsReader.hasRole(jeton, "ROLE_ADMIN")).isTrue();
        }

        @Test
        @DisplayName("un rôle voisin ne satisfait PAS l'exigence")
        void pasDeCorrespondancePartielle() {
            // La comparaison doit être une égalité, jamais un `contains` : sinon
            // un rôle nommé ADMIN_ASSISTANT ouvrirait la console d'administration.
            Claims jeton = claims(Map.of("roles", List.of("ROLE_ADMIN_ASSISTANT")));

            assertThat(JwtClaimsReader.hasRole(jeton, "ADMIN"))
                    .withFailMessage("un rôle voisin a satisfait l'exigence d'ADMIN : "
                            + "la comparaison se fait par inclusion et non par égalité")
                    .isFalse();
        }

        @Test
        @DisplayName("le rôle est reconnu où qu'il soit dans la liste")
        void rechercheDansTouteLaListe() {
            Claims jeton = claims(Map.of("roles", List.of("ROLE_USER", "ROLE_ADMIN")));

            assertThat(JwtClaimsReader.hasRole(jeton, "ADMIN")).isTrue();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Type de jeton")
    class TypeDeJeton {

        @ParameterizedTest(name = "type « {0} » est un jeton d'accès")
        @ValueSource(strings = {"user", "developer"})
        void typesDAcces(String type) {
            assertThat(JwtClaimsReader.isAccessToken(claims(Map.of("type", type)))).isTrue();
        }

        @ParameterizedTest(name = "type « {0} » n'est PAS un jeton d'accès")
        @ValueSource(strings = {"refresh", "refresh_user", "inconnu"})
        @DisplayName("un jeton de rafraîchissement ne peut pas servir d'accès")
        void typesRefuses(String type) {
            // Même signature, même audience, mais durée de vie bien plus longue.
            assertThat(JwtClaimsReader.isAccessToken(claims(Map.of("type", type)))).isFalse();
        }

        @Test
        @DisplayName("un jeton sans claim `type` est refusé")
        void typeAbsent() {
            assertThat(JwtClaimsReader.isAccessToken(claims(Map.of("roles", List.of("ROLE_USER")))))
                    .isFalse();
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Autorités Spring Security — le préfixe ROLE_")
    class Autorites {

        @Test
        @DisplayName("un rôle sans préfixe en reçoit un")
        void prefixePose() {
            // hasRole("ADMIN") ne compare pas a "ADMIN" : il prefixe lui-meme et
            // exige l'autorite ROLE_ADMIN. Un jeton de developpeur porte
            // role: "DEVELOPER" sans prefixe ; transmis brut, il ne satisfait
            // aucune regle, et le refus qui s'ensuit n'a aucune explication
            // visible.
            assertThat(JwtClaimsReader.authorities(claims(Map.of("role", "DEVELOPER"))))
                    .containsExactly("ROLE_DEVELOPER");
        }

        @Test
        @DisplayName("un rôle déjà préfixé n'est pas doublé")
        void prefixeNonDouble() {
            // ROLE_ROLE_ADMIN ne satisferait rien non plus : corriger l'absence
            // de prefixe ne doit pas creer le defaut symetrique.
            assertThat(JwtClaimsReader.authorities(claims(Map.of("roles", List.of("ROLE_ADMIN")))))
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("les deux formes mélangées donnent des autorités homogènes")
        void formesMelangees() {
            // Les deux ecritures circulent reellement dans la plateforme.
            assertThat(JwtClaimsReader.authorities(
                    claims(Map.of("roles", List.of("ROLE_SUPER_ADMIN", "ADMIN")))))
                    .containsExactly("ROLE_SUPER_ADMIN", "ROLE_ADMIN");
        }

        @Test
        @DisplayName("la hiérarchie résolue à l'émission est conservée en ENTIER")
        void hierarchieConservee() {
            // UserRole.expand() met [ROLE_SUPER_ADMIN, ROLE_ADMIN] dans le jeton
            // pour qu'aucun service aval n'ait de hierarchie a connaitre. Ne
            // garder que la premiere autorite — ce que faisait auth-service —
            // annule ce travail : le SUPER_ADMIN perd les droits de l'ADMIN.
            assertThat(JwtClaimsReader.authorities(
                    claims(Map.of("roles", List.of("ROLE_SUPER_ADMIN", "ROLE_ADMIN")))))
                    .hasSize(2);
        }

        @Test
        @DisplayName("un doublon ne produit qu'une autorité")
        void doublon() {
            assertThat(JwtClaimsReader.authorities(
                    claims(Map.of("roles", List.of("ADMIN", "ROLE_ADMIN")))))
                    .containsExactly("ROLE_ADMIN");
        }

        @Test
        @DisplayName("aucun rôle donne une liste vide, jamais null ni ROLE_ seul")
        void aucunRole() {
            // Un "ROLE_" seul serait une autorite fantome : elle ne satisferait
            // aucune regle, mais apparaitrait dans les journaux comme si le
            // porteur en avait une.
            assertThat(JwtClaimsReader.authorities(claims(Map.of("type", "user"))))
                    .isEmpty();
            assertThat(JwtClaimsReader.authorities(null)).isEmpty();
        }

        @Test
        @DisplayName("un claim de rôle blanc ne devient pas une autorité")
        void roleBlanc() {
            assertThat(JwtClaimsReader.authorities(
                    claims(Map.of("roles", List.of("   ", "ADMIN")))))
                    .containsExactly("ROLE_ADMIN");
        }
    }

    // ==================================================================
    @Nested
    @DisplayName("Lecture depuis une Map, et non un Claims")
    class LectureDepuisMap {

        @Test
        @DisplayName("une Map ordinaire est lue comme un Claims")
        void mapOrdinaire() {
            // auth-service n'expose que extractAllClaims(String), qui rend une
            // Map. Tant que la signature exigeait un Claims, ce service ne
            // POUVAIT PAS utiliser cette classe, et il en avait donc reecrit une
            // lecture divergente — exactement ce que cette classe existe pour
            // supprimer. Ce test verrouille l'ouverture.
            Map<String, Object> brut = Map.of(
                    "type", "user",
                    "roles", List.of("ROLE_ADMIN"));

            assertThat(JwtClaimsReader.isAccessToken(brut)).isTrue();
            assertThat(JwtClaimsReader.authorities(brut)).containsExactly("ROLE_ADMIN");
            assertThat(JwtClaimsReader.hasRole(brut, "ADMIN")).isTrue();
        }

        @Test
        @DisplayName("une Map vide ne lève pas")
        void mapVide() {
            // extractAllClaims rattrape ses exceptions et rend une map vide :
            // c'est une entree reelle, pas un cas theorique.
            assertThatCode(() -> {
                assertThat(JwtClaimsReader.isAccessToken(Map.of())).isFalse();
                assertThat(JwtClaimsReader.authorities(Map.of())).isEmpty();
            }).doesNotThrowAnyException();
        }
    }
}
