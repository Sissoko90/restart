package com.abdatytch.notification_service.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les chemins d'administration sont-ils reellement couverts par la chaine ?
 *
 * <h2>Le defaut que ce garde-fou vient de trouver</h2>
 *
 * La regle {@code .pathMatchers("/admin/**").hasRole("ADMIN")} <b>n'existait
 * pas</b> dans ce module. media-service, quotas-service, setting-service,
 * user-service et auth-service la portent tous ; celui-ci, non.
 *
 * <p>Pendant ce temps, le javadoc d'{@code AdminSmsController} affirmait en etre
 * protege, et presentait ses {@code @PreAuthorize} comme un simple doublon
 * « qui ne la remplace pas ». C'etait l'inverse : {@code /admin/sms/**} tombait
 * dans {@code anyExchange().authenticated()}, donc la chaine laissait passer
 * tout porteur authentifie et l'annotation etait la SEULE protection.
 *
 * <p>Elle fonctionnait — {@code @EnableReactiveMethodSecurity} est bien actif —
 * donc il n'y avait pas de trou ouvert. Mais ce depot a deja trouve
 * trente-trois {@code @PreAuthorize} rendues inertes par l'absence de cette
 * annotation, dont celle qui protegeait un effacement irreversible. Reposer sur
 * elle seule, c'est reposer sur la couche qui a deja disparu une fois.
 *
 * <h2>Ce que ce test est, et ce qu'il n'est pas</h2>
 *
 * Un garde-fou de construction, pas une couche d'autorisation : il n'applique
 * rien a l'execution. Il fait echouer la construction si la regle disparait, si
 * elle se retrouve apres {@code anyExchange} — donc inatteignable — ou si un
 * controleur d'administration cesse d'etre monte sous {@code /admin}.
 */
@DisplayName("Couverture des chemins d'administration")
class CouvertureDesCheminsAdminTest {

    private static final String PAQUET = "com.abdatytch.notification_service.web";

    private static final Path SECURITY_CONFIG = Path.of(
            "src/main/java/com/abdatytch/notification_service/config/SecurityConfig.java");

    /**
     * La source de {@code SecurityConfig}, COMMENTAIRES RETIRES.
     *
     * <h3>Pourquoi le retrait des commentaires n'est pas une precaution de style</h3>
     *
     * Le commentaire qui explique la regle ajoutee contient lui-meme le texte
     * {@code anyExchange().authenticated()} — pour raconter ce qui se passait
     * avant — et il figure AVANT la vraie ligne
     * {@code pathMatchers("/admin/**")}.
     *
     * <p>Un controle de position fait sur la source brute conclurait donc que la
     * regle arrive apres {@code anyExchange}, et echouerait sur du code correct.
     * Symetriquement, un commentaire citant la regle pourrait faire passer un
     * fichier qui ne la porte plus.
     *
     * <p>Ce depot a deja paye exactement cet angle mort : un garde-fou restait
     * vert parce qu'il lisait du texte au lieu de lire du code.
     *
     * <h3>Et pourquoi PAS une expression reguliere</h3>
     *
     * La premiere version de cette methode retirait les blocs par une expression
     * reguliere, et ce garde-fou etait ROUGE sur du code correct.
     *
     * <p>La raison : la regle a verifier s'ecrit
     * {@code pathMatchers("/admin/&#42;&#42;")}, et le motif de chemin
     * {@code /&#42;&#42;} commence par les deux caracteres qui ouvrent un
     * commentaire de bloc. L'expression prenait donc ce litteral de CHAINE pour
     * une ouverture de commentaire, et avalait tout jusqu'a la fermeture
     * suivante — c'est-a-dire la regle elle-meme.
     *
     * <p>Aucune expression reguliere ne peut distinguer ces deux caracteres dans
     * un commentaire de ces memes caracteres dans une chaine. Or le fichier a
     * lire contient precisement ce litteral, et c'est le plus important de tous.
     *
     * <p>D'ou une lecture ligne a ligne : seules les lignes dont le texte
     * COMMENCE par un marqueur de commentaire sont ecartees. Une ligne de code
     * est conservee entiere, quoi qu'elle contienne.
     *
     * <p>Note instructive : la premiere redaction de ce javadoc citait la
     * sequence de fermeture d'un commentaire de bloc, ce qui a ferme ce
     * commentaire au milieu d'une phrase et casse la compilation. La meme
     * famille de defaut, dans le texte qui la decrit.
     */
    private static String configSansCommentaires() throws IOException {
        assertThat(SECURITY_CONFIG)
                .withFailMessage("SecurityConfig introuvable a %s : ce garde-fou "
                        + "ne verifie plus rien", SECURITY_CONFIG)
                .exists();

        var code = new StringBuilder();
        boolean dansUnBloc = false;

        for (String ligne : Files.readAllLines(SECURITY_CONFIG, StandardCharsets.UTF_8)) {
            String nue = ligne.strip();

            if (dansUnBloc) {
                if (nue.contains("*/")) {
                    dansUnBloc = false;
                }
                continue;
            }
            if (nue.startsWith("/*")) {
                dansUnBloc = !nue.contains("*/");
                continue;
            }
            // Une ligne de continuation de javadoc commence par « * ».
            if (nue.startsWith("//") || nue.startsWith("*")) {
                continue;
            }
            code.append(ligne).append('\n');
        }

        String sansCommentaires = code.toString();

        assertThat(sansCommentaires)
                .withFailMessage("le retrait des commentaires a emporte le code : "
                        + "« authorizeExchange » a disparu")
                .contains("authorizeExchange");

        // Et la preuve que la prose est bien partie. « MANQUAIT » n'apparait que
        // dans le commentaire qui explique l'ajout de la regle : s'il survit, le
        // retrait n'a rien retire, et les controles de position ci-dessous
        // mesureraient des positions de commentaires.
        assertThat(sansCommentaires)
                .withFailMessage("le retrait des commentaires n'a rien retire : "
                        + "les controles de position porteraient sur de la prose")
                .doesNotContain("MANQUAIT");

        return sansCommentaires;
    }

    private record Controleur(String nom, String cheminDeBase) {
        boolean seDitAdmin() {
            return nom.contains("Admin");
        }

        boolean estSousAdmin() {
            return cheminDeBase.startsWith("/admin");
        }
    }

    private static List<Controleur> controleurs() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<Controleur> trouves = new ArrayList<>();
        Set<BeanDefinition> definitions = scanner.findCandidateComponents(PAQUET);

        for (BeanDefinition definition : definitions) {
            try {
                Class<?> classe = Class.forName(definition.getBeanClassName());
                RequestMapping mapping = classe.getAnnotation(RequestMapping.class);
                String chemin = (mapping != null && mapping.value().length > 0)
                        ? mapping.value()[0]
                        : "";
                trouves.add(new Controleur(classe.getSimpleName(), chemin));
            } catch (ClassNotFoundException introuvable) {
                throw new AssertionError("Controleur scanne mais non chargeable : "
                        + definition.getBeanClassName(), introuvable);
            }
        }
        return trouves;
    }

    @Nested
    @DisplayName("La règle de chemin")
    class RegleDeChemin {

        @Test
        @DisplayName("SecurityConfig exige le rôle ADMIN sur /admin/**")
        void regleAdminPresente() throws IOException {
            String config = configSansCommentaires();

            assertThat(config)
                    .withFailMessage("""
                            La regle /admin/** a disparu de SecurityConfig.

                            Sans elle, les chemins d'administration tombent dans \
                            anyExchange().authenticated() : la chaine laisse \
                            passer TOUT porteur authentifie, et seuls les \
                            @PreAuthorize refusent. C'etait l'etat de ce module, \
                            pendant que son javadoc affirmait le contraire.""")
                    .contains("pathMatchers(\"/admin/**\")")
                    .contains("hasRole(\"ADMIN\")");
        }

        /**
         * La position, et non seulement la presence.
         *
         * <p>Les matchers de {@code authorizeExchange} sont evalues DANS
         * L'ORDRE : le premier qui correspond decide. Une regle
         * {@code /admin/**} placee apres {@code anyExchange()} ne serait jamais
         * atteinte — presente dans le fichier, lisible en revue, et totalement
         * inerte.
         *
         * <p>C'est le motif dominant de ce depot, et c'est pourquoi la presence
         * seule ne suffit pas a verifier.
         */
        @Test
        @DisplayName("elle est déclarée AVANT anyExchange, donc atteignable")
        void regleAdminAvantAnyExchange() throws IOException {
            String config = configSansCommentaires();

            int admin = config.indexOf("pathMatchers(\"/admin/**\")");
            int anyExchange = config.indexOf("anyExchange()");

            assertThat(admin)
                    .withFailMessage("regle /admin/** absente")
                    .isGreaterThanOrEqualTo(0);
            assertThat(anyExchange)
                    .withFailMessage("anyExchange() absent de la chaine : elle "
                            + "n'a plus de regle par defaut")
                    .isGreaterThanOrEqualTo(0);

            assertThat(admin)
                    .withFailMessage("""
                            La regle /admin/** est declaree APRES anyExchange() \
                            (positions %d et %d).

                            Les matchers sont evalues dans l'ordre : anyExchange \
                            correspond a tout, donc cette regle ne sera JAMAIS \
                            atteinte. Elle est presente, lisible en revue, et \
                            inerte.""", admin, anyExchange)
                    .isLessThan(anyExchange);
        }
    }

    @Nested
    @DisplayName("Les contrôleurs")
    class Controleurs {

        @Test
        @DisplayName("le scan trouve bien des contrôleurs, dont des contrôleurs d'administration")
        void leScanTrouveQuelqueChose() {
            List<Controleur> trouves = controleurs();

            assertThat(trouves)
                    .withFailMessage("aucun controleur trouve dans %s : le scan "
                            + "est casse, et les tests suivants seraient "
                            + "vacuement verts", PAQUET)
                    .isNotEmpty();

            assertThat(trouves).filteredOn(Controleur::seDitAdmin)
                    .withFailMessage("aucun controleur d'administration trouve : "
                            + "le critere de nommage a change, ce test ne garde "
                            + "plus rien")
                    .isNotEmpty();
        }

        /**
         * Un controleur nomme « Admin » doit etre monte sous {@code /admin}.
         *
         * <p>Sinon il echappe a la regle de chemin sans qu'aucune erreur ne le
         * signale, et il serait servi a tout porteur de jeton.
         */
        @Test
        @DisplayName("tout contrôleur d'administration est monté sous /admin")
        void controleursAdminSousPrefixeAdmin() {
            for (Controleur controleur : controleurs()) {
                if (controleur.seDitAdmin()) {
                    assertThat(controleur.estSousAdmin())
                            .withFailMessage("""
                                    %s se presente comme un controleur \
                                    d'administration mais est monte sur « %s ». \
                                    Il echappe donc a la regle /admin/** de \
                                    SecurityConfig, et serait servi a tout \
                                    porteur de jeton.""",
                                    controleur.nom(), controleur.cheminDeBase())
                            .isTrue();
                }
            }
        }

        /**
         * Et reciproquement : aucun chemin metier ne commence par {@code /admin}.
         *
         * <p>Un controleur metier monte par erreur sous ce prefixe exigerait le
         * role ADMIN de tout le monde — la boite de reception cesserait de
         * fonctionner. Le meme soin que partout ailleurs dans ce depot :
         * designer exactement ce qu'on veut, ni plus ni moins.
         */
        @Test
        @DisplayName("aucun contrôleur métier n'est monté sous /admin")
        void cheminsMetierHorsDePrefixeAdmin() {
            List<Controleur> metier = controleurs().stream()
                    .filter(c -> !c.seDitAdmin())
                    .toList();

            assertThat(metier)
                    .withFailMessage("aucun controleur metier trouve : ce test "
                            + "ne garde rien")
                    .isNotEmpty();

            for (Controleur controleur : metier) {
                assertThat(controleur.estSousAdmin())
                        .withFailMessage("""
                                %s est un controleur metier monte sur « %s ». La \
                                regle /admin/** y exigerait donc le role ADMIN \
                                de tous les appelants.""",
                                controleur.nom(), controleur.cheminDeBase())
                        .isFalse();
            }
        }
    }
}
